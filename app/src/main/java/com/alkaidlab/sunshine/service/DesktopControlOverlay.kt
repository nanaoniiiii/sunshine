package com.alkaidlab.sunshine.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Display-scoped shell overlay: navigation and exit remain reachable above another application. */
internal class DesktopControlOverlay(context: Context, private val action: (String) -> Unit, private val report: (String) -> Unit) : AutoCloseable {
    private val ui = Handler(Looper.getMainLooper())
    private val windowContext = if (Build.VERSION.SDK_INT >= 30)
        context.createWindowContext(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG, null) else context
    private val manager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var chooser: View? = null
    @Volatile private var closed = false
    fun show() {
        check(!closed)
        val done = CountDownLatch(1)
        var failure: Exception? = null
        ui.post {
            try {
                if (closed) return@post
                // User services are started through app_process rather than Zygote.
                // Android 12+ can therefore leave Typeface.DEFAULT uninitialized.
                initializeShellTypeface()
                val scale = windowContext.resources.displayMetrics.density
                val root = LinearLayout(windowContext).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((4 * scale).toInt(), (4 * scale).toInt(), (4 * scale).toInt(), (4 * scale).toInt())
                    background = GradientDrawable().apply { setColor(Color.rgb(23, 32, 53)); cornerRadius = 12 * scale }
                }
                val expanded = LinearLayout(windowContext).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
                fun button(parent: LinearLayout, label: String, click: () -> Unit) = Button(windowContext).apply {
                    text = label; textSize = 14f; isAllCaps = false; minHeight = (44 * scale).toInt()
                    setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(43, 58, 97))
                    setOnClickListener { click() }
                    parent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * scale).toInt()))
                }
                button(root, "Sunshine ▾") { expanded.visibility = if (expanded.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
                root.addView(expanded)
                for ((label, name) in listOf("返回" to "back", "桌面 / 应用" to "home", "最近任务" to "tasks", "快捷控制" to "quick"))
                    button(expanded, label) { expanded.visibility = View.GONE; action(name) }
                button(root, "退出桌面") { action("exit") }
                val params = WindowManager.LayoutParams((128 * scale).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
                    // The authorized shell already holds INTERNAL_SYSTEM_WINDOW. A system
                    // dialog belongs to that service rather than a different app UID's
                    // application-overlay AppOp (which can be hidden or fail attribution).
                    WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT).apply {
                        // WindowContext otherwise inherits the host application's package,
                        // whose UID is different from this shell UserService. AppOps then
                        // accepts addView but silently keeps the window hidden.
                        packageName = "com.android.shell"
                        gravity = Gravity.TOP or Gravity.END; x = (8 * scale).toInt(); y = (48 * scale).toInt(); title = "Sunshine 桌面控制"
                    }
                manager.addView(root, params)
                if (closed) manager.removeViewImmediate(root) else view = root
            } catch (e: Exception) {
                android.util.Log.e("SunshineDesktopControls", "Cannot attach desktop controls", e)
                failure = e
            } finally { done.countDown() }
        }
        check(done.await(3, TimeUnit.SECONDS)) { "桌面控制条启动超时" }
        failure?.let { throw it }
    }
    override fun close() {
        closed = true
        ui.post {
            dismissChooser()
            view?.let { try { manager.removeViewImmediate(it) } catch (_: Exception) {} }; view = null
        }
    }

    fun choose(title: String, choices: List<Pair<String, String>>) {
        report("Menu requested: $title (${choices.size} items)")
        ui.post {
            if (closed) return@post
            try {
                dismissChooser()
                val scale = windowContext.resources.displayMetrics.density
                val panel = LinearLayout(windowContext).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((16 * scale).toInt(), (16 * scale).toInt(), (16 * scale).toInt(), (16 * scale).toInt())
                    background = GradientDrawable().apply { setColor(Color.rgb(23, 32, 53)); cornerRadius = 12 * scale }
                    isFocusableInTouchMode = true
                    setOnKeyListener { _, code, event ->
                        if (code == android.view.KeyEvent.KEYCODE_BACK || code == android.view.KeyEvent.KEYCODE_ESCAPE) {
                            if (event.action == android.view.KeyEvent.ACTION_UP) dismissChooser()
                            true
                        } else false
                    }
                }
                panel.addView(android.widget.TextView(windowContext).apply { text = title; textSize = 20f; setTextColor(Color.WHITE); setPadding(8, 8, 8, 16) })
                val entries = LinearLayout(windowContext).apply { orientation = LinearLayout.VERTICAL }
                fun button(parent: LinearLayout, label: String, callback: () -> Unit) {
                    parent.addView(Button(windowContext).apply {
                        text = label; isAllCaps = false; setTextColor(Color.WHITE)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(43, 58, 97))
                        setOnClickListener { callback() }
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * scale).toInt()))
                }
                for ((label, command) in choices) button(entries, label) { dismissChooser(); action(command) }
                if (choices.isEmpty()) entries.addView(android.widget.TextView(windowContext).apply { text = "此屏幕没有其他应用任务"; setTextColor(Color.WHITE) })
                val scroll = android.widget.ScrollView(windowContext).apply { addView(entries) }
                val height = minOf((maxOf(1, choices.size) * 52 * scale).toInt(), (windowContext.resources.displayMetrics.heightPixels * .6).toInt())
                panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
                button(panel, "关闭") { dismissChooser() }
                button(panel, "退出桌面") { dismissChooser(); action("exit") }
                val params = WindowManager.LayoutParams(minOf((480 * scale).toInt(), (windowContext.resources.displayMetrics.widthPixels * .9).toInt()),
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
                    packageName = "com.android.shell"; gravity = Gravity.CENTER; this.title = "Sunshine $title"
                }
                manager.addView(panel, params); chooser = panel; panel.requestFocus()
                report("Menu attached: $title")
            } catch (e: Exception) {
                report("Menu failed: ${e.javaClass.simpleName}: ${e.message}")
                android.util.Log.e("SunshineDesktopControls", "Cannot show desktop menu", e)
            }
        }
    }

    private fun dismissChooser() {
        chooser?.let { try { manager.removeViewImmediate(it) } catch (_: Exception) {} }; chooser = null
    }

    fun isVisibleOn(displayId: Int): Boolean {
        val process = ProcessBuilder("dumpsys", "window", "Sunshine 桌面控制").redirectErrorStream(true).start()
        val read = java.util.concurrent.FutureTask {
            process.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val size = reader.read(buffer)
                    if (size < 0) break
                    val keep = minOf(size, (524288 - result.length).coerceAtLeast(0))
                    if (keep > 0) result.append(buffer, 0, keep)
                }
                result.toString()
            }
        }
        Thread(read, "Sunshine-Window-Visibility").apply { isDaemon = true; start() }
        return try {
            process.waitFor(2, TimeUnit.SECONDS) && DesktopWindowVisibility.visible(read.get(500, TimeUnit.MILLISECONDS), displayId)
        } catch (_: Exception) { false } finally { process.destroy(); read.cancel(true) }
    }

    @android.annotation.SuppressLint("BlockedPrivateApi")
    private fun initializeShellTypeface() {
        // This adapter runs only in the authorized shell/root UserService, where
        // framework initialization is allowed; never attempt it in the app UID.
        val uid = android.os.Process.myUid()
        check(uid == 2000 || uid == 0) { "桌面控制条需要授权的显示服务" }
        if (android.graphics.Typeface.DEFAULT == null)
            android.graphics.Typeface::class.java.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null)
    }
}
