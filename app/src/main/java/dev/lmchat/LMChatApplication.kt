package dev.lmchat

import android.app.Application
import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dev.lmchat.data.local.AppDatabase
import dev.lmchat.data.remote.LmStudioClient
import dev.lmchat.data.repository.ChatRepository
import dev.lmchat.data.repository.ServerRepository
import dev.lmchat.data.repository.SettingsStore
import dev.lmchat.data.scanner.NetworkScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tiny manual DI container. Created once in [LMChatApplication] and handed to
 * ViewModels; avoids a full DI framework for an app this size.
 */
class AppContainer(context: Context) {

    val updater = dev.lmchat.data.update.AppUpdater(context.applicationContext)

    val database: AppDatabase = AppDatabase.get(context.applicationContext)
    val client: LmStudioClient = LmStudioClient(LmStudioClient.buildClient())
    val scanner: NetworkScanner = NetworkScanner(LmStudioClient.buildScannerClient(), context.applicationContext)

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val chatRepository: ChatRepository = ChatRepository(database, client, applicationScope)
    val serverRepository: ServerRepository = ServerRepository(database)
    val settings: SettingsStore = SettingsStore(context.applicationContext)
}

class LMChatApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
        // Mark any streaming rows left over from a previous run as interrupted.
        container.applicationScope.launch {
            container.chatRepository.cleanupStaleStreaming()
        }
    }
}
