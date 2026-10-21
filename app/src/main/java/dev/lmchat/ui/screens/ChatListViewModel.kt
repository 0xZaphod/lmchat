package dev.lmchat.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.lmchat.AppContainer
import dev.lmchat.data.local.ConversationEntity
import dev.lmchat.domain.ServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatListUiState(
    val conversations: List<ConversationEntity> = emptyList(),
    val defaultHost: String = "",
    val defaultPort: Int = 0,
    val connected: Boolean = false,
)

class ChatListViewModel(private val container: AppContainer) : ViewModel() {

    private val repo = container.chatRepository
    private val settings = container.settings

    private val defaultHost = MutableStateFlow(settings.host)
    private val defaultPort = MutableStateFlow(settings.port)

    val uiState: StateFlow<ChatListUiState> =
        combine(
            repo.observeConversations(),
            defaultHost,
            defaultPort,
        ) { list, host, port ->
            ChatListUiState(
                conversations = list,
                defaultHost = host,
                defaultPort = port,
                connected = host.isNotBlank() && port > 0,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatListUiState())

    fun newChat(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            // Settings can change while this ViewModel remains on the navigation
            // back stack, so read the persisted values when creating the chat.
            val hostValue = settings.host
            val portValue = settings.port
            defaultHost.value = hostValue
            defaultPort.value = portValue
            val server = hostValue.trim().takeIf { it.isNotBlank() }?.let { host ->
                settings.serverInfo()
            }
            val conv = repo.createConversation(
                server = server,
                model = settings.preferredModel.takeIf { it.isNotBlank() },
                systemPrompt = settings.systemPrompt,
            )
            onCreated(conv.id)
        }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch { repo.renameConversation(id, title) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.deleteConversation(id) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatListViewModel(container) as T
            }
    }
}
