package dev.lmchat.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lmchat.LMChatApplication
import dev.lmchat.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun androidBackFromInitialSetupReturnsToChatsAfterRecreation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LMChatApplication
        val settings = app.container.settings
        val previousHost = settings.host
        try {
            settings.host = ""
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithText("Settings").assertIsDisplayed()
                scenario.recreate()
                compose.onNodeWithText("Settings").assertIsDisplayed()
                pressBack()
                compose.onNodeWithContentDescription("New chat").assertIsDisplayed()
                scenario.recreate()
                compose.onNodeWithContentDescription("New chat").assertIsDisplayed()
            }
        } finally {
            settings.host = previousHost
        }
    }
}
