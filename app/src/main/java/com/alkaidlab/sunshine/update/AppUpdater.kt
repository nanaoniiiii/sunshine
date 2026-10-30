package com.alkaidlab.sunshine.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import com.alkaidlab.sunshine.BuildConfig
import com.alkaidlab.sunshine.ui.MainActivity
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class UpdateState(val release: ReleaseApk? = null, val checking: Boolean = false,
    val downloading: Boolean = false, val progress: Int = -1, val ready: Boolean = false,
    val downloadedBytes: Long = 0,
    val message: String = "自动检查官网发布版本，也可手动检查。")

/** One owner in the UI process; no networking or download polling in the stream engine. */
internal object AppUpdater {
    const val OPEN_UPDATE = "show_update"
    private const val CHANNEL = "sunshine_updates"
    private const val NOTIFICATION = 2042
    private const val JOB = 2043
    private const val INTERVAL = 6 * 60 * 60 * 1000L
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val transferLock = Mutex()
    private var downloadMonitor: Job? = null
    private var directJob: Job? = null
    private var directDownloader: UpdatePackageDownloader? = null
    @Volatile private var downloadEpoch = 0L
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    val dialogOpen = MutableStateFlow(false)
    private val prefs get() = app.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val downloads get() = app.getSystemService(DownloadManager::class.java)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    @Synchronized fun initialize(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "软件更新", NotificationManager.IMPORTANCE_DEFAULT))
        val saved = runCatching { gson.fromJson(prefs.getString("release", null), ReleaseApk::class.java) }.getOrNull()
        if (saved != null && ReleasePolicy.trustedAsset(saved.tag, saved.name, saved.url) &&
            ReleaseVersion.parse(saved.tag)!! > (ReleaseVersion.parse(BuildConfig.VERSION_NAME) ?: return)) {
            mutableState.value = UpdateState(release = saved, downloading = prefs.getBoolean("direct_pending", false),
                message = "发现新版本 ${saved.tag}")
        } else {
            val oldId = prefs.getLong("download_id", -1)
            if (oldId != -1L) runCatching { downloads.remove(oldId) }
            prefs.edit().remove("release").remove("download_id").remove("direct_pending").remove("direct_ready").apply()
            notifications.cancel(NOTIFICATION)
        }
        val scheduler = app.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(JOB, ComponentName(app, UpdateCheckJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(INTERVAL).setPersisted(true).build()
        if (scheduler.getPendingJob(JOB) == null) scheduler.schedule(job)
        scope.launch {
            if (mutableState.value.release != null && prefs.getString("download_backend", null) == "direct") {
                if (prefs.getBoolean("direct_pending", false)) {
                    try { UpdateDownloadService.start(app) }
                    catch (_: RuntimeException) { fail("下载已暂停，打开更新窗口后可继续下载") }
                } else if (prefs.getBoolean("direct_ready", false)) {
                    try {
                        lock.withLock {
                            verifyApk(checkNotNull(mutableState.value.release))
                            mutableState.value = mutableState.value.copy(ready = true, progress = 100, message = "下载完成，安装包校验通过")
                        }
                    } catch (_: Exception) { prefs.edit().remove("direct_ready").apply() }
                }
            } else { refreshDownload(); monitorDownload() }
            check(false)
        }
    }

    fun open() { dialogOpen.value = true }
    fun dismiss() { dialogOpen.value = false }
    fun checkNow() { open(); scope.launch { check(true) } }

    suspend fun check(force: Boolean): Boolean = lock.withLock {
        if (mutableState.value.downloading || mutableState.value.ready) return@withLock true
        if (!force && System.currentTimeMillis() - prefs.getLong("last_check", 0) < INTERVAL) return@withLock true
        mutableState.value = mutableState.value.copy(checking = true, message = "正在检查新版本…")
        try {
            val release = fetchLatest()
            prefs.edit().putLong("last_check", System.currentTimeMillis()).putString("release", release?.let(gson::toJson)).apply()
            mutableState.value = UpdateState(release = release, message = release?.let { "发现新版本 ${it.tag}" } ?: "已是最新发布版本（${BuildConfig.VERSION_NAME}）")
            if (release != null) notifyAvailable(release, false)
            else notifications.cancel(NOTIFICATION)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.value = mutableState.value.copy(checking = false, message = "检查失败：${e.message ?: "网络不可用"}。可稍后重试。")
            false
        }
    }

    private fun fetchLatest(): ReleaseApk? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        try {
            return ReleasePolicy.website(readPublicPage(ReleasePolicy.WEBSITE, deadline), BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.toList())
        } catch (error: Exception) {
            com.alkaidlab.sunshine.service.SunshineLogManager.log("UPDATE", "Website update source unavailable: ${error.javaClass.simpleName}; trying GitHub")
        }
        val request = Request.Builder().url(ReleasePolicy.API).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "Sunshine-Android/${BuildConfig.VERSION_NAME}").build()
        try {
            client.newCall(request).apply { timeout().deadlineNanoTime(deadline) }.execute().use { response ->
                if (response.isSuccessful) return ReleasePolicy.latest(response.body?.string() ?: error("GitHub 返回内容为空"), BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.toList())
                check(response.code == 403 || response.code == 429) { "GitHub 返回 HTTP ${response.code}" }
            }
        } catch (e: Exception) {
            com.alkaidlab.sunshine.service.SunshineLogManager.log("UPDATE", "Release API unavailable: ${e.javaClass.simpleName}; trying public release feed")
        }
        val candidates = ReleasePolicy.feedVersions(readPublicPage(ReleasePolicy.FEED, deadline), BuildConfig.VERSION_NAME)
        for ((tag, notes) in candidates.take(5)) {
            val html = readPublicPage("https://github.com/${ReleasePolicy.REPO}/releases/expanded_assets/$tag", deadline)
            ReleasePolicy.expandedAsset(tag, html, Build.SUPPORTED_ABIS.toList(), notes)?.let { return it }
        }
        return null
    }

    private fun readPublicPage(url: String, deadline: Long): String = client.newCall(Request.Builder().url(url)
        .header("User-Agent", "Sunshine-Android/${BuildConfig.VERSION_NAME}").build())
        .apply { timeout().deadlineNanoTime(deadline) }.execute().use { response ->
        check(response.isSuccessful) { "更新服务返回 HTTP ${response.code}" }
        val body = response.body ?: error("更新服务内容为空")
        check(body.contentLength() <= 1048576) { "发布列表过大" }
        val bytes = body.byteStream().use { it.readBytesLimited(1048576) }
        bytes.toString(Charsets.UTF_8)
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) { val count = read(buffer); if (count < 0) break; check(output.size() + count <= limit) { "发布列表过大" }; output.write(buffer, 0, count) }
        return output.toByteArray()
    }

    fun download(force: Boolean = false) { scope.launch { lock.withLock {
        val release = mutableState.value.release ?: return@withLock
        if (mutableState.value.downloading) return@withLock
        try {
            check(ReleasePolicy.trustedAsset(release.tag, release.name, release.url)) { "下载地址不是本项目的发布文件" }
            val oldId = prefs.getLong("download_id", -1)
            if (oldId != -1L) downloads.remove(oldId)
            downloadMonitor?.cancel(); downloadMonitor = null
            val file = apkFile(release)
            if (!force && file.isFile && runCatching { verifyApk(release) }.isSuccess) {
                prefs.edit().putString("download_backend", "direct").putBoolean("direct_pending", false).putBoolean("direct_ready", true).apply()
                mutableState.value = mutableState.value.copy(downloading = false, ready = true, progress = 100,
                    message = "下载完成，安装包校验通过")
                return@withLock
            }
            if (file.exists()) check(file.delete()) { "无法替换上次下载文件" }
            prefs.edit().remove("download_id").putString("download_backend", "direct")
                .putBoolean("direct_pending", true).putBoolean("direct_ready", false).commit()
            mutableState.value = mutableState.value.copy(downloading = true, ready = false,
                progress = if (mutableState.value.ready) 0 else mutableState.value.progress.coerceAtLeast(0),
                message = "正在连接下载源，将自动续传…")
            UpdateDownloadService.start(app)
        } catch (e: Exception) {
            prefs.edit().putBoolean("direct_pending", false).apply()
            fail("无法开始下载：${e.message}")
        }
    } } }

    @Synchronized fun runManagedDownload() {
        if (directJob?.isActive == true || !mutableState.value.downloading) return
        val release = mutableState.value.release ?: return
        val epoch = ++downloadEpoch
        val downloader = UpdatePackageDownloader()
        directDownloader = downloader
        directJob = scope.launch {
            try {
                val sources = ReleasePolicy.downloadSources(release)
                transferLock.withLock {
                    if (epoch != downloadEpoch) return@withLock
                    val file = apkFile(release)
                    if (!file.isFile || runCatching { verifyApk(release) }.isFailure) {
                        downloader.download(DownloadSpec(sources, release.size, release.sha256), file) { progress ->
                            mutableState.update { current ->
                                if (epoch != downloadEpoch || !current.downloading) current
                                else {
                                    val percentage = if (progress.total > 0) (progress.bytes * 100 / progress.total).toInt().coerceIn(0, 100) else -1
                                    val amount = "%.1f".format(progress.bytes / 1048576.0) + if (progress.total > 0) "/${"%.1f".format(progress.total / 1048576.0)} MB" else " MB"
                                    val speed = if (progress.bytesPerSecond >= 1048576) "%.1f MB/s".format(progress.bytesPerSecond / 1048576.0)
                                        else "%.0f KB/s".format(progress.bytesPerSecond / 1024.0)
                                    current.copy(progress = percentage, downloadedBytes = progress.bytes,
                                        message = "$amount · $speed · ${progress.source}")
                                }
                            }
                        }
                    }
                }
                lock.withLock {
                    if (epoch != downloadEpoch) return@withLock
                    verifyApk(release)
                    prefs.edit().putBoolean("direct_pending", false).putBoolean("direct_ready", true).commit()
                    mutableState.value = mutableState.value.copy(downloading = false, progress = 100, ready = true, message = "下载完成，安装包校验通过")
                    notifyAvailable(release, true)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == downloadEpoch) lock.withLock {
                    prefs.edit().putBoolean("direct_pending", false).apply()
                    fail("${error.message ?: "下载暂时中断"}；已保存断点，可继续下载")
                }
            } finally {
                if (epoch == downloadEpoch) { directDownloader = null; directJob = null }
            }
        }
    }

    fun cancelDownload() { scope.launch { lock.withLock {
        if (!mutableState.value.downloading) return@withLock
        suspendManagedDownload()
        val oldId = prefs.getLong("download_id", -1)
        if (oldId != -1L) runCatching { downloads.remove(oldId) }
        prefs.edit().remove("download_id").putBoolean("direct_pending", false).apply()
        mutableState.value = mutableState.value.copy(downloading = false, message = "下载已暂停，已保留断点")
    } } }

    @Synchronized fun suspendManagedDownload() {
        ++downloadEpoch
        directDownloader?.cancel(); directDownloader = null
        directJob?.cancel(); directJob = null
        if (mutableState.value.downloading)
            mutableState.value = mutableState.value.copy(downloading = false, message = "下载已暂停，可继续下载")
    }

    @Synchronized private fun monitorDownload() {
        if (downloadMonitor?.isActive == true || !mutableState.value.downloading) return
        downloadMonitor = scope.launch {
            while (isActive && mutableState.value.downloading) { refreshDownload(); delay(1000) }
        }
    }

    suspend fun refreshDownload(completedId: Long? = null) = lock.withLock {
        if (prefs.getString("download_backend", null) == "direct") return@withLock
        val id = prefs.getLong("download_id", -1)
        if (id == -1L || (completedId != null && completedId != id)) return@withLock
        val release = mutableState.value.release ?: return@withLock
        try {
            downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                check(cursor != null && cursor.moveToFirst()) { "下载任务已被移除，请重新下载" }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        verifyApk(release)
                        mutableState.value = mutableState.value.copy(downloading = false, progress = 100, ready = true, message = "下载完成，安装包校验通过")
                        notifyAvailable(release, true)
                    }
                    DownloadManager.STATUS_FAILED -> error("下载失败（${cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))}），请重试")
                    else -> {
                        val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val progress = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else -1
                        val text = if (status == DownloadManager.STATUS_PAUSED) "下载暂时暂停，等待网络恢复…" else "正在下载 ${release.tag}…"
                        mutableState.value = mutableState.value.copy(downloading = true, ready = false, progress = progress, message = text)
                    }
                }
            }
        } catch (e: Exception) {
            prefs.edit().remove("download_id").apply()
            runCatching { downloads.remove(id) }
            fail(e.message ?: "下载文件校验失败，请重新下载")
        }
    }

    suspend fun installationFile(): File? = withContext(Dispatchers.IO) { lock.withLock {
        val release = mutableState.value.release ?: return@withLock null
        try { verifyApk(release) } catch (e: Exception) { fail("安装包不可用：${e.message}。请重新下载。"); null }
    } }

    private fun apkFile(release: ReleaseApk): File {
        val directory = File(checkNotNull(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)), "updates")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建更新目录" }
        return File(directory, release.name)
    }

    private fun verifyApk(release: ReleaseApk): File {
        val file = apkFile(release)
        check(file.isFile && file.length() in 1..268435456 && (release.size == -1L || file.length() == release.size)) { "安装包大小不符" }
        if (release.sha256 != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) } }
            check(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "安装包 SHA-256 不符" }
        }
        val pm = app.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("不是有效的 APK")
        check(archive.packageName == app.packageName && archive.versionName == release.tag) { "安装包身份或版本不符" }
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(archive.longVersionCode > installed.longVersionCode) { "安装包不是更新的版本" }
        val signing = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val current = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        check(signing.isNotEmpty() && signing == current) { "安装包签名与当前应用不一致" }
        return file
    }

    fun installationError(message: String) { mutableState.value = mutableState.value.copy(message = message) }
    private fun fail(message: String) { mutableState.value = mutableState.value.copy(downloading = false, ready = false, progress = -1, message = message) }

    private fun notifyAvailable(release: ReleaseApk, ready: Boolean) {
        val key = release.tag + if (ready) ":ready" else ":available"
        if (prefs.getString("notified", null) == key) return
        val intent = Intent(app, MainActivity::class.java).putExtra(OPEN_UPDATE, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(app, NOTIFICATION, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (ready) "Sunshine 更新已下载" else "Sunshine 有新版本 ${release.tag}")
            .setContentText(if (ready) "点击返回应用安装更新" else "点击在应用内查看并下载更新")
            .setContentIntent(pending).setAutoCancel(true).build()
        try {
            if (androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled()) {
                notifications.notify(NOTIFICATION, notification)
                prefs.edit().putString("notified", key).apply()
            }
        } catch (_: SecurityException) { /* The in-app entry stays available without notification permission. */ }
    }
}
