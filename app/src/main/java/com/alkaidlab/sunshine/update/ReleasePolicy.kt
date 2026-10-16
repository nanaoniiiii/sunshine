package com.alkaidlab.sunshine.update

import com.google.gson.JsonParser
import java.net.URI
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal data class ReleaseVersion(val date: LocalDate, val revision: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other, { it.date }, { it.revision })
    companion object {
        fun parse(tag: String): ReleaseVersion? = try {
            val match = Regex("v(\\d{4})\\.(\\d{2})\\.(\\d{2})\\.(\\d{1,6})").matchEntire(tag)
            match?.destructured?.let { (y, m, d, r) -> ReleaseVersion(LocalDate.of(y.toInt(), m.toInt(), d.toInt()), r.toInt()) }
        } catch (_: Exception) { null }
    }
}

internal data class ReleaseApk(val tag: String, val notes: String, val name: String, val url: String,
    val size: Long, val sha256: String?, val prerelease: Boolean)

internal object ReleasePolicy {
    const val REPO = "nanaoniiiii/sunshine"
    const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"
    const val FEED = "https://github.com/$REPO/releases.atom"

    // The public release feed is independent of the unauthenticated REST quota.
    // It still lists releases rather than source commits, including previews.
    fun feedVersions(xml: String, current: String): List<Pair<String, String>> {
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "发布列表格式不正确" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val entries = doc.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "entry")
        val installed = ReleaseVersion.parse(current) ?: return emptyList()
        return (0 until entries.length).mapNotNull { index ->
            val entry = entries.item(index) as Element
            val links = entry.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "link")
            val tag = (0 until links.length).map { (links.item(it) as Element).getAttribute("href") }
                .firstNotNullOfOrNull { url -> url.removePrefix("https://github.com/$REPO/releases/tag/").takeIf {
                    url == "https://github.com/$REPO/releases/tag/$it" && ReleaseVersion.parse(it)?.let { v -> v > installed } == true
                } } ?: return@mapNotNull null
            val content = entry.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "content").item(0)?.textContent.orEmpty()
            tag to content.replace(Regex("</(p|li|h[1-6])>"), "\n").replace(Regex("<[^>]*>"), "").trim().take(12000)
        }.distinctBy { it.first }.sortedByDescending { ReleaseVersion.parse(it.first) }
    }

    fun expandedAsset(tag: String, html: String, abis: List<String>, notes: String): ReleaseApk? {
        if (ReleaseVersion.parse(tag) == null) return null
        val rows = Regex("<li\\b[^>]*>([\\s\\S]*?)</li>").findAll(html).map { it.value }.toList()
        return (abis.filter { it == "arm64-v8a" || it == "armeabi-v7a" }.distinct() + "universal").firstNotNullOfOrNull { abi ->
            val name = "Sunshine-$tag-$abi.apk"
            val path = "/$REPO/releases/download/$tag/$name"
            rows.firstNotNullOfOrNull { row ->
                if (!Regex("href=[\"']${Regex.escape(path)}[\"']").containsMatchIn(row)) null
                else {
                    // Require GitHub's exact asset digest when the rounded HTML
                    // display size cannot supply an exact byte length.
                    val digest = Regex("sha256:([0-9a-fA-F]{64})(?![0-9a-fA-F])").find(row)?.groupValues?.get(1)?.lowercase()
                    if (digest == null) null else ReleaseApk(tag, notes, name, "https://github.com$path", -1, digest, false)
                }
            }
        }
    }

    fun trustedAsset(tag: String, name: String, url: String): Boolean = try {
        val uri = URI(url)
        ReleaseVersion.parse(tag) != null && name.matches(Regex("Sunshine-${Regex.escape(tag)}-(arm64-v8a|armeabi-v7a|universal)\\.apk")) &&
            uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
            uri.rawQuery == null && uri.fragment == null && uri.path == "/$REPO/releases/download/$tag/$name"
    } catch (_: Exception) { false }

    /** Include published previews: this project distributes its device-tested candidates that way. */
    fun latest(json: String, current: String, abis: List<String>): ReleaseApk? {
        val installed = ReleaseVersion.parse(current) ?: return null
        return JsonParser.parseString(json).asJsonArray.mapNotNull { element ->
            runCatching {
                val release = element.asJsonObject
                if (release.get("draft")?.asBoolean == true || release.get("published_at")?.isJsonNull != false) return@runCatching null
                val tag = release.get("tag_name").asString
                val version = ReleaseVersion.parse(tag) ?: return@runCatching null
                if (version <= installed) return@runCatching null
                val assets = release.getAsJsonArray("assets") ?: return@runCatching null
                val preferred = abis.filter { it == "arm64-v8a" || it == "armeabi-v7a" }.distinct() + "universal"
                preferred.firstNotNullOfOrNull { abi -> assets.firstNotNullOfOrNull { asset ->
                    val obj = asset.asJsonObject
                    val name = obj.get("name").asString
                    val url = obj.get("browser_download_url").asString
                    val size = obj.get("size").asLong
                    if (name != "Sunshine-$tag-$abi.apk" || !trustedAsset(tag, name, url) || size !in 1..268435456 || obj.get("state")?.asString != "uploaded") null
                    else {
                        val digest = obj.get("digest")?.takeUnless { it.isJsonNull }?.asString
                        if (digest != null && !digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) null
                        else ReleaseApk(tag, release.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(12000), name, url, size,
                            digest?.removePrefix("sha256:")?.lowercase(), release.get("prerelease")?.asBoolean == true)
                    }
                } }
            }.getOrNull()
        }.maxByOrNull { ReleaseVersion.parse(it.tag)!! }
    }
}
