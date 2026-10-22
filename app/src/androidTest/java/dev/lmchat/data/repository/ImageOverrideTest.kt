package dev.lmchat.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lmchat.domain.ServerInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageOverrideTest {
    @Test fun imagePermissionPersistsAndIsScopedToServerAndModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsStore(context)
        val server = ServerInfo("https://image-override-test.invalid", 443)
        val model = "override-regression-model"
        try {
            settings.setAllowImages(server, model, true)
            assertTrue(settings.allowsImages(server, model))
            assertTrue(SettingsStore(context).allowsImages(server, model))
            assertTrue(settings.allowsImages(server.copy(host = "https://image-override-test.invalid/v1/"), model))
            assertFalse(settings.allowsImages(server, "other-model"))
            assertFalse(settings.allowsImages(server.copy(host = "https://other-server.invalid"), model))
            settings.setAllowImages(server, model, false)
            assertFalse(settings.allowsImages(server, model))
            assertFalse(SettingsStore(context).allowsImages(server, model))
        } finally {
            settings.setAllowImages(server, model, false)
        }
    }
}
