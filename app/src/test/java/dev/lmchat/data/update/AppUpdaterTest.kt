package dev.lmchat.data.update

import org.junit.Assert.*
import org.junit.Test

class AppUpdaterTest {
    @Test fun numericVersionsDoNotCompareLexically() {
        assertTrue(newerVersion("v1.0.10", "1.0.9"))
        assertFalse(newerVersion("1.0.2", "1.0.10"))
        assertFalse(newerVersion("1.0.2", "1.0.2"))
        assertFalse(newerVersion("1.1-beta", "1.0"))
        assertFalse(newerVersion("1.0.0", "1.0"))
    }

    private fun release(version: String = "1.0.3", prerelease: Boolean = false,
                        url: String = "https://github.com/0xZaphod/lmchat/releases/download/v1.0.3/LMChat-1.0.3.apk",
                        name: String = "LMChat-1.0.3.apk", size: Long = 100) =
        """{"tag_name":"v$version","prerelease":$prerelease,"draft":false,"body":"Changes","assets":[{"name":"$name","size":$size,"browser_download_url":"$url"}]}"""

    @Test fun selectsOfficialVersionedApk() {
        val update = parseRelease(release(), "1.0.2")!!
        assertEquals("1.0.3", update.version)
        assertEquals("Changes", update.notes)
        assertEquals(100L, update.size)
    }

    @Test fun skipsOldPrereleaseAndMissingAssets() {
        assertNull(parseRelease(release(version = "1.0.1"), "1.0.2"))
        assertNull(parseRelease(release(prerelease = true), "1.0.2"))
        assertNull(parseRelease(release(name = "app-debug.apk"), "1.0.2"))
    }

    @Test fun rejectsUnexpectedSourcesAndSizes() {
        for (body in listOf(release(url = "http://github.com/0xZaphod/lmchat/releases/download/update.apk"),
            release(url = "https://example.com/update.apk"), release(size = 0), release(size = Long.MAX_VALUE))) {
            assertThrows(IllegalArgumentException::class.java) { parseRelease(body, "1.0.2") }
        }
    }
}
