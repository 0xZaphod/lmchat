package dev.lmchat.data.update

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppUpdaterAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rejectsInvalidApk() {
        val file = File(context.cacheDir, "invalid-update.apk")
        try {
            file.writeText("not an APK")
            assertThrows(IllegalStateException::class.java) { AppUpdater(context).verify(file) }
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsInstalledBuildAsUpdate() {
        val file = File(context.applicationInfo.sourceDir)
        val failure = assertThrows(IllegalStateException::class.java) { AppUpdater(context).verify(file) }
        assertEquals("APK is not a newer build", failure.message)
    }

    @Test fun cachedUpdateCanBeSharedThroughFileProvider() {
        val file = File(context.cacheDir, "updates/provider-test.apk")
        file.parentFile!!.mkdirs()
        try {
            file.writeText("test update")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            assertEquals("content", uri.scheme)
            assertEquals("test update", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } finally {
            file.delete()
        }
    }
}
