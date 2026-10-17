package com.alkaidlab.sunshine.update

import org.junit.Assert.*
import org.junit.Test

class WebsiteUpdatePolicyTest {
    private val tag = "v2026.10.17.3"
    private val name = "Sunshine-$tag-arm64-v8a.apk"
    private val url = "https://nanaoni.tech/downloads/$tag/$name"
    private fun manifest(abi: String = "arm64-v8a") = """{
      "schema_version":1,"repository":"nanaoniiiii/sunshine","release":{
      "tag_name":"$tag","draft":false,"prerelease":true,"published_at":"2026-10-17T00:00:00Z","body":"更新说明",
      "assets":[{"name":"Sunshine-$tag-$abi.apk","browser_download_url":"https://nanaoni.tech/downloads/$tag/Sunshine-$tag-$abi.apk",
      "size":12345,"digest":"sha256:${"a".repeat(64)}"}]}}"""
    private fun select(json: String = manifest()) = ReleasePolicy.website(json, "v2026.10.17.2", listOf("arm64-v8a"))
    private fun rejected(json: String) { assertTrue(runCatching { select(json) }.isFailure) }

    @Test fun websiteApkCarriesChecksumAndPreviewLabel() {
        val result = select()!!
        assertEquals(url, result.url); assertEquals("a".repeat(64), result.sha256)
        assertTrue(result.prerelease); assertEquals(12345L, result.size)
    }
    @Test fun websiteNeverOffersDowngradeOrSameVersion() {
        assertNull(ReleasePolicy.website(manifest(), tag, listOf("arm64-v8a")))
        assertNull(ReleasePolicy.website(manifest(), "v2026.10.17.5", listOf("arm64-v8a")))
    }
    @Test fun unsupportedAbiCanUseUniversal() {
        assertTrue(select(manifest("universal"))!!.name.endsWith("universal.apk"))
        rejected(manifest("armeabi-v7a"))
    }
    @Test fun websiteRequiresPublishedProjectManifestAndStrongDigest() {
        rejected(manifest().replace("\"schema_version\":1", "\"schema_version\":2"))
        rejected(manifest().replace("nanaoniiiii/sunshine", "other/project"))
        rejected(manifest().replace("\"draft\":false", "\"draft\":true"))
        rejected(manifest().replace("2026-10-17T00:00:00Z", ""))
        rejected(manifest().replace("sha256:${"a".repeat(64)}", "sha256:bad"))
        rejected(manifest().replace("\"size\":12345", "\"size\":0"))
    }
    @Test fun onlyExactHttpsOfficialVersionedPathsAreTrusted() {
        assertTrue(ReleasePolicy.trustedAsset(tag, name, url))
        for (bad in listOf(url.replace("https:", "http:"), url.replace("nanaoni.tech", "nanaoni.tech.evil.example"),
            url.replace("nanaoni.tech", "user@nanaoni.tech"), url.replace("nanaoni.tech", "nanaoni.tech:444"),
            "$url?version=2", "$url#fragment", url.replace("/downloads/", "/download/"), url.replace("v2026", "%762026"))) {
            assertFalse(bad, ReleasePolicy.trustedAsset(tag, name, bad))
        }
        rejected(manifest().replace("https://nanaoni.tech", "https://github.com"))
    }
    @Test fun websiteApksCannotBeInjectedThroughGithubAssetParser() {
        val release = """[{"tag_name":"$tag","draft":false,"published_at":"2026-10-17T00:00:00Z","assets":[
          {"name":"$name","browser_download_url":"$url","size":12345,"state":"uploaded"}]}]"""
        assertNull(ReleasePolicy.latest(release, "v2026.10.17.2", listOf("arm64-v8a")))
    }
}
