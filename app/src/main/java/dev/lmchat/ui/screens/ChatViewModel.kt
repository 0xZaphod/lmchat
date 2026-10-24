package dev.lmchat.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.lmchat.AppContainer
import dev.lmchat.data.local.ConversationEntity
import dev.lmchat.data.local.ServerEntity
import dev.lmchat.domain.Attachment
import dev.lmchat.domain.AttachmentKind
import dev.lmchat.domain.ConnectionStatus
import dev.lmchat.domain.ModelMetadata
import dev.lmchat.domain.ServerType
import dev.lmchat.domain.ServerInfo
import dev.lmchat.domain.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val container: AppContainer,
    private val conversationId: String,
) : ViewModel() {

    private val repo = container.chatRepository
    private val serverRepo = container.serverRepository
    private val settings = container.settings

    val conversation: StateFlow<ConversationEntity?> =
        repo.observeConversation(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Live per-message text for in-flight responses (id -> full text). */
    private val liveText = MutableStateFlow<Map<String, String>>(emptyMap())
    private var retryingMessageId: String? = null
    private var sendInProgress = false
    private var exitInProgress = false
    private var contextLimitLookupJob: Job? = null
    private var modelRefreshJob: Job? = null
    private var refreshedContextDuringStream = false

    val messages: StateFlow<List<UiMessage>> =
        combine(repo.observeMessages(conversationId), liveText) { list, live ->
            list.map { m ->
                if (m.status == "streaming") {
                    m.copy(content = live[m.id] ?: m.content)
                } else {
                    m
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isStreaming: StateFlow<Boolean> =
        messages.map { list -> list.any { it.status == "streaming" } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Attachments waiting to be sent with the next message. */
    val pendingAttachments = MutableStateFlow<List<Attachment>>(emptyList())

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var noticeJob: Job? = null

    fun notice(text: String) {
        noticeJob?.cancel()
        _notice.value = text
        noticeJob = viewModelScope.launch {
            delay(5000)
            _notice.value = null
        }
    }

    val models = MutableStateFlow<List<String>>(emptyList())
    val selectedModel = MutableStateFlow<String?>(null)
    val currentContextTokens = conversation.map { it?.contextTokens }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val contextTokensEstimated = conversation.map { it?.contextTokensEstimated ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    private data class MetadataResult(val server: ServerInfo, val model: String, val details: ModelMetadata)
    private val metadataResult = MutableStateFlow<MetadataResult?>(null)

    /** Outcome of the server probe that last filled [models]; drives the top-bar dot. */
    private enum class ProbeOutcome {
        PENDING,
        OK,
        FAILED,
    }

    private val probeOutcome = MutableStateFlow(ProbeOutcome.PENDING)

    /**
     * Server resolution order:
     * (1) conversation host/port when present and valid;
     * (2) settings when the host is a real address (not the 127.0.0.1 placeholder) and port > 0;
     * (3) most recently created saved server (observeAll is ORDER BY createdAt DESC);
     * (4) most recent OTHER conversation with a usable host/port (first-run bootstrap);
     * (5) settings as-is (even the placeholder) when host non-blank and port > 0, else null.
     */
    private val serverFlow = combine(repo.observeConversation(conversationId), serverRepo.observeAll(), repo.observeConversations()) { conv, servers, convs ->
        val convHost = conv?.host?.trim()?.takeIf { it.isNotBlank() }
        val convPort = conv?.port
        if (convHost != null && convPort != null && convPort > 0) {
            return@combine servers.firstOrNull { it.host == convHost && it.port == convPort }?.info
                ?: ServerInfo(convHost, convPort,
                    settings.apiKey.takeIf { settings.host == convHost && settings.port == convPort },
                    ServerType.fromStorage(conv.serverType), conv.contextLimitOverride)
        }
        val settingsHost = settings.host
        val settingsPort = settings.port
        if (settingsHost.isNotBlank() && settingsHost != "127.0.0.1" && settingsPort > 0) {
            return@combine settings.serverInfo()
        }
        servers.firstOrNull()?.info?.let { return@combine it }
        // Bootstrap: inherit server from the most recent conversation that has
        // a usable one (first-run case: nothing saved in settings yet).
        val inherited = convs.firstOrNull {
            it.id != conversationId &&
                !it.host.isNullOrBlank() &&
                it.host != "127.0.0.1" &&
                (it.port ?: 0) > 0
        }
        if (inherited != null) {
            return@combine ServerInfo(inherited.host!!.trim(), inherited.port!!,
                type = ServerType.fromStorage(inherited.serverType), contextLimitOverride = inherited.contextLimitOverride)
        }
        if (settingsHost.isNotBlank() && settingsPort > 0) {
            return@combine settings.serverInfo()
        }
        null
    }

    val server: StateFlow<ServerInfo?> =
        serverFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Live server connection status for the top-bar indicator: ERROR when no server
     * resolves or the last probe failed, CHECKING while a probe is in flight.
     */
    val connection: StateFlow<ConnectionStatus> =
        combine(serverFlow, probeOutcome) { s, outcome ->
            when {
                s == null -> ConnectionStatus.ERROR
                outcome == ProbeOutcome.PENDING -> ConnectionStatus.CHECKING
                outcome == ProbeOutcome.FAILED -> ConnectionStatus.ERROR
                else -> ConnectionStatus.CONNECTED
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionStatus.CHECKING)

    private val activeMetadata = combine(server, selectedModel, metadataResult) { s, model, result ->
        result?.takeIf { it.server == s && it.model == model }?.details
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val allowImagesOverride = combine(server, selectedModel, settings.allowedImageModels) { s, model, _ ->
        s != null && model != null && settings.allowsImages(s, model)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAllowImages(allow: Boolean) {
        val s = server.value ?: return
        val model = selectedModel.value ?: return
        settings.setAllowImages(s, model, allow)
    }

    val supportsImages = combine(activeMetadata, allowImagesOverride) { metadata, allow ->
        if (allow) true else metadata?.supportsImages
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val contextLimit = combine(server, activeMetadata) { s, metadata ->
        s?.contextLimitOverride?.takeIf { it > 0 } ?: metadata?.contextLength
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val contextLimitLoading = combine(server, activeMetadata) { s, metadata ->
        s?.contextLimitOverride?.takeIf { it > 0 } == null && metadata == null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            combine(server, selectedModel) { s, model -> s to model }.collectLatest { (s, model) ->
                metadataResult.value = null
                contextLimitLookupJob?.cancel()
                if (s != null && model != null) refreshContextLimit(force = true)
            }
        }
        viewModelScope.launch {
            // Cold Room flow: the stateIn-backed `conversation` would return its
            // initial null before Room emits (race), skipping model refresh.
            val conv = repo.observeConversation(conversationId).first()
            selectedModel.value = conv?.model
            refreshModels()
        }
    }

    /* ---------------- actions ---------------- */

    fun refreshModels() {
        modelRefreshJob?.cancel()
        metadataResult.value = null
        probeOutcome.value = ProbeOutcome.PENDING
        modelRefreshJob = viewModelScope.launch {
            val s = serverFlow.first { it != null } ?: return@launch
            val result = repo.probe(s)
            if (serverFlow.first() != s) return@launch
            probeOutcome.value = if (result.isCompatible) ProbeOutcome.OK else ProbeOutcome.FAILED
            if (!result.isCompatible) {
                // A failed lookup says nothing about whether the saved model still exists.
                notice(result.error ?: "Server unreachable")
                return@launch
            }
            models.value = result.models
            // Keep the selection if the model still exists, otherwise pick first.
            val current = selectedModel.value
            if (current == null || current !in models.value) {
                val replacement = models.value.firstOrNull()
                selectedModel.value = replacement
                repo.updateModel(conversationId, replacement)
            } else {
                refreshContextLimit(force = true)
            }
        }
    }

    fun selectModel(model: String) {
        metadataResult.value = null
        if (selectedModel.value == model) refreshContextLimit(force = true)
        selectedModel.value = model
        viewModelScope.launch {
            repo.updateModel(conversationId, model)
        }
    }

    private fun refreshContextLimit(force: Boolean = false) {
        if (contextLimitLookupJob?.isActive == true) {
            if (!force) return
            contextLimitLookupJob?.cancel()
        }
        val model = selectedModel.value ?: return
        contextLimitLookupJob = viewModelScope.launch {
            val activeServer = serverFlow.first() ?: return@launch
            val details = container.client.modelMetadata(activeServer, model)
            if (selectedModel.value == model && serverFlow.first() == activeServer) {
                metadataResult.value = MetadataResult(activeServer, model, details)
            }
        }
    }

    private fun onStreamProgress(id: String, fullText: String) {
        liveText.value = liveText.value + (id to fullText)
        if (!refreshedContextDuringStream) {
            refreshedContextDuringStream = true
            // JIT-loaded models only expose loaded_instances while the request
            // is active, so fetch the configured limit on the first token.
            refreshContextLimit(force = true)
        }
    }

    fun send(userText: String): Boolean {
        if (sendInProgress || retryingMessageId != null || isStreaming.value) return false
        val text = userText.trim()
        val attachments = pendingAttachments.value
        if (text.isEmpty() && attachments.isEmpty()) return false
        if (!canSendImages(attachments + messages.value.flatMap { it.attachments })) return false

        val s = server.value ?: run {
            notice("No server configured — set one in the menu.")
            return false
        }
        val model = selectedModel.value
            ?: settings.preferredModel.takeIf { it.isNotBlank() }
        if (model == null) {
            notice("No model available — connect to the server and pick one.")
            return false
        }

        refreshedContextDuringStream = false
        sendInProgress = true
        viewModelScope.launch {
            try {
                val conv = conversation.first() ?: return@launch
                pendingAttachments.value = emptyList()
                val assistantId = repo.sendChat(
                    conversation = conv,
                    server = s,
                    model = model,
                    userText = text,
                    userAttachments = attachments,
                    onProgress = ::onStreamProgress,
                )
                // Wait until the assistant message settles (row either final or
                // dropped because the response was empty), then drop the live entry.
                repo.observeMessages(conversationId).first { list ->
                    val row = list.find { it.id == assistantId }
                    row == null || row.status != "streaming"
                }
                liveText.value = liveText.value - assistantId
                // LM Studio may JIT-load a model on its first request. Once it
                // has loaded, /api/v1/models can report the configured context.
                refreshContextLimit()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice(error.message?.let { "Couldn't send message: $it" } ?: "Couldn't send message.")
            } finally {
                sendInProgress = false
            }
        }
        return true
    }

    fun retryResponse(assistantMessageId: String) {
        if (sendInProgress || isStreaming.value || retryingMessageId != null) return
        val currentMessages = messages.value
        val responseIndex = currentMessages.indexOfLast { it.id == assistantMessageId }
        if (responseIndex != currentMessages.lastIndex || responseIndex < 0) return
        val interrupted = currentMessages[responseIndex]
        if (!interrupted.isAssistant || interrupted.status != "error") return
        val previousUser = currentMessages.take(responseIndex).lastOrNull { it.isUser } ?: run {
            notice("Can't retry: the original message wasn't found.")
            return
        }
        if (!canSendImages(currentMessages.flatMap { it.attachments })) return
        val s = server.value ?: run {
            notice("No server configured — set one in the menu.")
            return
        }
        val model = selectedModel.value
            ?: settings.preferredModel.takeIf { it.isNotBlank() }
            ?: run {
                notice("No model available — connect to the server and pick one.")
                return
            }

        refreshedContextDuringStream = false
        retryingMessageId = assistantMessageId
        viewModelScope.launch {
            try {
                val conv = conversation.first() ?: return@launch
                repo.deleteMessage(assistantMessageId)
                val assistantId = repo.sendChat(
                    conversation = conv,
                    server = s,
                    model = model,
                    userText = previousUser.content,
                    userAttachments = previousUser.attachments,
                    persistUserMessage = false,
                    onProgress = ::onStreamProgress,
                )
                repo.observeMessages(conversationId).first { list ->
                    val row = list.find { it.id == assistantId }
                    row == null || row.status != "streaming"
                }
                liveText.value = liveText.value - assistantId
                refreshContextLimit()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice(error.message?.let { "Couldn't retry response: $it" } ?: "Couldn't retry response.")
            } finally {
                retryingMessageId = null
            }
        }
    }

    fun stop() {
        repo.stopStreaming(conversationId)
    }

    fun addPendingAttachment(attachment: Attachment) {
        if (!canSendImages(listOf(attachment))) return
        pendingAttachments.value = pendingAttachments.value + attachment
    }

    private fun canSendImages(attachments: List<Attachment>): Boolean {
        if (attachments.none { it.kind == AttachmentKind.IMAGE }) return true
        val activeServer = server.value
        val model = selectedModel.value
        if (activeServer != null && model != null && settings.allowsImages(activeServer, model)) return true
        val result = metadataResult.value
        if (result?.server == server.value && result?.model == selectedModel.value && result?.details?.supportsImages == true) return true
        notice(if (supportsImages.value == false) "This model does not support images. Choose a vision-capable model to use image attachments."
            else "Image support has not been confirmed for this model. Enable Allow images for this model in the attachment menu if it supports vision.")
        return false
    }

    fun removePendingAttachment(attachment: Attachment) {
        pendingAttachments.value = pendingAttachments.value.filterNot { it === attachment }
    }

    fun updateServerInfo(info: ServerInfo) {
        val host = info.host.trim()
        val port = info.port
        if (host.isBlank() || port !in 1..65535) return
        settings.useServer(info)
        metadataResult.value = null
        selectedModel.value = null
        models.value = emptyList()
        viewModelScope.launch {
            val conv = repo.observeConversation(conversationId).first() ?: return@launch
            repo.updateConversation(
                conv.copy(
                    host = host.trim().takeIf { it.isNotBlank() },
                    port = port,
                    model = selectedModel.value,
                    serverType = info.type.name,
                    contextLimitOverride = info.contextLimitOverride,
                ),
            )
            serverRepo.save(
                ServerEntity(
                    id = "srv_${host}_${port}",
                    name = "$host:$port",
                    host = host,
                    port = port,
                    apiKey = info.apiKey,
                    createdAt = System.currentTimeMillis(),
                    serverType = info.type.name,
                    contextLimitOverride = info.contextLimitOverride,
                )
            )
            refreshModels()
        }
    }

    fun rename(title: String) {
        viewModelScope.launch { repo.renameConversation(conversationId, title) }
    }

    fun exitChat(onDone: () -> Unit) {
        if (exitInProgress) return
        exitInProgress = true
        onDone()
    }

    fun deleteConversation(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.stopStreaming(conversationId)
            repo.deleteConversation(conversationId)
            onDone()
        }
    }

    companion object {
        fun factory(container: AppContainer, conversationId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(container, conversationId) as T
            }
    }
}
