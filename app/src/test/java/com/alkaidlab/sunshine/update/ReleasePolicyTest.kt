package com.alkaidlab.sunshine.update

import org.junit.Assert.*
import org.junit.Test

class ReleasePolicyTest {
    private fun release(tag: String, abis: List<String> = listOf("arm64-v8a", "armeabi-v7a", "universal"),
        draft: Boolean = false, published: String = "\"2026-10-17T12:00:00Z\"", preview: Boolean = false): String =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$preview,"published_at":$published,"body":"说明","assets":[${abis.joinToString { abi ->
            """{"name":"Sunshine-$tag-$abi.apk","browser_download_url":"https://github.com/nanaoniiiii/sunshine/releases/download/$tag/Sunshine-$tag-$abi.apk","size":12345,"state":"uploaded","digest":null}"""
        }}]}"""
    private fun select(json: String, abis: List<String> = listOf("arm64-v8a", "armeabi-v7a")) = ReleasePolicy.latest(json, "v2026.10.16.1", abis)

    @Test fun calendarValidationAndNumericRevision() {
        assertNull(ReleaseVersion.parse("v2026.02.30.1"))
        assertNull(ReleaseVersion.parse("v2026.10.17.1-rc1"))
        assertNull(ReleaseVersion.parse("2026.10.17.1"))
        assertTrue(ReleaseVersion.parse("v2026.10.17.10")!! > ReleaseVersion.parse("v2026.10.17.9")!!)
        assertTrue(ReleaseVersion.parse("v2027.01.01.1")!! > ReleaseVersion.parse("v2026.12.31.99")!!)
    }
    @Test fun newestUsableReleaseRegardlessOfApiOrder() {
        val json = "[${release("v2026.10.17.2")},${release("v2026.10.16.1")},${release("v2026.10.17.10")},${release("v2026.10.17.9")} ]"
        assertEquals("v2026.10.17.10", select(json)?.tag)
    }
    @Test fun noDowngradeOrSameVersion() {
        assertNull(select("[${release("v2026.10.16.1")},${release("v2026.10.15.3")}]"))
    }
    @Test fun draftUnpublishedAndNoApkAreIgnored() {
        assertNull(select("[${release("v2026.10.17.1", draft = true)},${release("v2026.10.18.1", published = "null")},${release("v2026.10.19.1", abis = emptyList())}]"))
    }
    @Test fun publishedPreviewIsDiscoverable() {
        assertTrue(select("[${release("v2026.10.17.2", preview = true)}]")!!.prerelease)
    }
    @Test fun respectsDeviceAbiOrderAndFallsBackToUniversal() {
        val json = "[${release("v2026.10.17.2")}]"
        assertTrue(select(json)!!.name.endsWith("arm64-v8a.apk"))
        assertTrue(select(json, listOf("armeabi-v7a"))!!.name.endsWith("armeabi-v7a.apk"))
        assertTrue(select("[${release("v2026.10.17.2", listOf("universal"))}]")!!.name.endsWith("universal.apk"))
    }
    @Test fun releaseWithInvalidAssetDoesNotHideOlderUsableUpdate() {
        val bad = release("v2026.10.18.1").replace("https://github.com/", "https://attacker.example/")
        assertEquals("v2026.10.17.2", select("[$bad,${release("v2026.10.17.2")}]")?.tag)
    }
    @Test fun refusesWrongRepositoryUrlQueryAndArchiveName() {
        val tag = "v2026.10.17.2"
        val name = "Sunshine-$tag-arm64-v8a.apk"
        val base = "https://github.com/nanaoniiiii/sunshine/releases/download/$tag/$name"
        assertTrue(ReleasePolicy.trustedAsset(tag, name, base))
        for (url in listOf(base.replace("https:", "http:"), base.replace("github.com", "github.com.attacker.example"),
            base.replace("nanaoniiiii", "other"), "$base?redirect=evil", "$base#fragment", base.replace("github.com/", "github.com:444/"))) {
            assertFalse(url, ReleasePolicy.trustedAsset(tag, name, url))
        }
        assertFalse(ReleasePolicy.trustedAsset(tag, "../$name", base))
    }
    @Test fun invalidDigestAndEmptyFileAreIgnored() {
        val json = "[${release("v2026.10.17.2")}]"
        assertNull(select(json.replace("\"digest\":null", "\"digest\":\"sha256:bad\"")))
        assertNull(select(json.replace("\"size\":12345", "\"size\":0")))
        assertNull(select(json.replace("\"state\":\"uploaded\"", "\"state\":\"starter\"")))
        val digest = "a".repeat(64)
        assertEquals(digest, select(json.replace("\"digest\":null", "\"digest\":\"sha256:$digest\""))?.sha256)
    }
    @Test fun feedOnlyAcceptsNewReleaseLinksFromThisRepository() {
        val feed = """<feed xmlns="http://www.w3.org/2005/Atom">
          <entry><link href="https://github.com/nanaoniiiii/sunshine/releases/tag/v2026.10.17.2"/><content type="html">&lt;p&gt;新版本&lt;/p&gt;</content></entry>
          <entry><link href="https://github.com/nanaoniiiii/sunshine/releases/tag/v2026.10.16.1"/></entry>
          <entry><link href="https://github.com/other/sunshine/releases/tag/v2099.01.01.1"/></entry>
          <entry><link href="https://github.com/nanaoniiiii/sunshine/commit/abcd"/></entry></feed>"""
        assertEquals(listOf("v2026.10.17.2" to "新版本"), ReleasePolicy.feedVersions(feed, "v2026.10.16.1"))
    }
    @Test(expected = IllegalArgumentException::class) fun feedRejectsExternalEntities() {
        ReleasePolicy.feedVersions("<!DOCTYPE feed [<!ENTITY file SYSTEM 'file:///private'>]><feed/>", "v2026.10.16.1")
    }
    @Test fun expandedAssetsRequireDigestFromTheMatchingApkRow() {
        val tag = "v2026.10.17.2"; val digest = "a".repeat(64)
        val html = """<ul><li><a href="/nanaoniiiii/sunshine/releases/download/$tag/Sunshine-$tag-arm64-v8a.apk">APK</a><span>sha256:$digest</span></li></ul>"""
        val result = ReleasePolicy.expandedAsset(tag, html, listOf("arm64-v8a"), "更新说明")!!
        assertEquals(digest, result.sha256); assertEquals(-1L, result.size)
        assertTrue(ReleasePolicy.trustedAsset(result.tag, result.name, result.url))
        assertNull(ReleasePolicy.expandedAsset(tag, html.replace("sha256:$digest", ""), listOf("arm64-v8a"), ""))
        assertNull(ReleasePolicy.expandedAsset(tag, html.replace("nanaoniiiii", "other"), listOf("arm64-v8a"), ""))
        assertNull(ReleasePolicy.expandedAsset(tag, html.replace("<span>", "</li><li><span>"), listOf("arm64-v8a"), ""))
    }
}
