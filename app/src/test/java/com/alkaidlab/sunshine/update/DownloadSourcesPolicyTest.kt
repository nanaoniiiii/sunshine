package com.alkaidlab.sunshine.update

import org.junit.Assert.*
import org.junit.Test

class DownloadSourcesPolicyTest {
    private val tag = "v2026.10.31.2"
    private val name = "Sunshine-$tag-arm64-v8a.apk"
    private fun release(url: String) = ReleaseApk(tag, "", name, url, 7341302, "a".repeat(64), false)
    @Test fun officialSourceFallsBackOnlyToTheSameProjectsIdenticalVersionAndAbi() {
        val sources = ReleasePolicy.downloadSources(release("https://nanaoni.tech/downloads/$tag/$name"))
        assertEquals(listOf("https://nanaoni.tech/downloads/$tag/$name", "https://github.com/nanaoniiiii/sunshine/releases/download/$tag/$name"), sources.map { it.url })
    }
    @Test fun untrustedMirrorCannotSelectAnyDownloadSource() {
        try { ReleasePolicy.downloadSources(release("https://other.example/$name")); fail("Untrusted mirror accepted") }
        catch (_: IllegalArgumentException) {}
    }
}
