package dev.lmchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.lmchat.ui.screens.ChatListScreen
import dev.lmchat.ui.screens.ChatListViewModel
import dev.lmchat.ui.screens.ChatScreen
import dev.lmchat.ui.screens.SettingsScreen
import dev.lmchat.ui.theme.LMChatTheme

class MainActivity : ComponentActivity() {

    private val updates by lazy {
        androidx.lifecycle.ViewModelProvider(this,
            dev.lmchat.ui.screens.UpdateViewModel.factory((application as LMChatApplication).container.updater)
        )[dev.lmchat.ui.screens.UpdateViewModel::class.java]
    }

    override fun onStart() {
        super.onStart()
        if (!isChangingConfigurations) updates.check()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LMChatTheme {
                LMChatApp(updates)
            }
        }
    }
}

@Composable
private fun LMChatApp(updates: dev.lmchat.ui.screens.UpdateViewModel) {
    dev.lmchat.ui.screens.UpdateDialog(updates)
    val container = (androidx.compose.ui.platform.LocalContext.current
        .applicationContext as LMChatApplication).container
    val navController = rememberNavController()
    val configuredHost = container.settings.host.trim()
    val needsServerSetup = configuredHost.isBlank() ||
        configuredHost.equals("127.0.0.1", ignoreCase = true) ||
        configuredHost.equals("localhost", ignoreCase = true)
    var initialSetupHandled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!initialSetupHandled) {
            initialSetupHandled = true
            if (needsServerSetup) navController.navigate("settings")
        }
    }

    NavHost(
        navController = navController,
        startDestination = "chats",
        // Keep route pushes still, but retain pop animation so predictive back can
        // reveal the destination beneath the current screen during the gesture.
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(150),
            )
        },
    ) {
        composable("chats") {
            ChatListScreen(
                viewModel = viewModel(factory = ChatListViewModel.factory(container)),
                onOpenChat = { id -> navController.navigate("chat/$id") },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable("chat/{conversationId}") { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId").orEmpty()
            ChatScreen(
                conversationId = conversationId,
                onBack = { navController.popBackStack() },
                onOpenScanner = { navController.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(
                updates = updates,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate("chats") {
                            popUpTo("settings") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
    }
}
