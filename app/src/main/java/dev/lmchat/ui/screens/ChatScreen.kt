package dev.lmchat.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import dev.lmchat.ui.components.AssistantMarkdown
import dev.lmchat.LMChatApplication
import dev.lmchat.data.util.AttachmentFactory
import dev.lmchat.domain.Attachment
import dev.lmchat.domain.AttachmentKind
import dev.lmchat.domain.ServerInfo
import dev.lmchat.domain.serverPort
import dev.lmchat.domain.ServerType
import dev.lmchat.domain.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenScanner: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as LMChatApplication).container
    val savedServers by container.serverRepository.observeAll().collectAsState(initial = emptyList())
    val viewModel: ChatViewModel = viewModel(
        key = "chat_$conversationId",
        factory = ChatViewModel.factory(container, conversationId),
    )

    val conversation by viewModel.conversation.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val pending by viewModel.pendingAttachments.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val models by viewModel.models.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val supportsImages by viewModel.supportsImages.collectAsState()
    val allowImagesOverride by viewModel.allowImagesOverride.collectAsState()
    val contextLimit by viewModel.contextLimit.collectAsState()
    val contextLimitLoading by viewModel.contextLimitLoading.collectAsState()
    val currentContextTokens by viewModel.currentContextTokens.collectAsState()
    val contextTokensEstimated by viewModel.contextTokensEstimated.collectAsState()
    val server by viewModel.server.collectAsState()

    DisposableEffect(conversationId) {
        onDispose {
            container.applicationScope.launch {
                val deleted = container.chatRepository.deleteConversationIfEmpty(conversationId)
                if (deleted) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context.applicationContext, "Empty chat not saved", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    var text by remember { mutableStateOf("") }
    var showServerDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val listState = rememberLazyListState()
    val launchScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(notice) {
        notice?.let { snackbarHostState.showSnackbar(it) }
    }

    // ---- Attachment launchers ----
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            launchScope.launch {
                val attachment = withContext(Dispatchers.IO) {
                    AttachmentFactory.fromImage(context, uri)
                }
                if (attachment != null) viewModel.addPendingAttachment(attachment)
                else viewModel.notice("Couldn't read that image.")
            }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            launchScope.launch {
                val attachment = withContext(Dispatchers.IO) {
                    AttachmentFactory.fromDocument(context, uri)
                }
                if (attachment != null) viewModel.addPendingAttachment(attachment)
                else viewModel.notice("Couldn't extract text from that document.")
            }
        }
    }

    var cameraFile by remember { mutableStateOf<File?>(null) }
    val cameraPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = cameraFile
        if (!success || file == null || !file.exists()) {
            file?.delete()
        } else {
            launchScope.launch {
                try {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    val attachment = withContext(Dispatchers.IO) {
                        AttachmentFactory.fromImage(context, uri, file.name)
                    }
                    if (attachment != null) viewModel.addPendingAttachment(attachment)
                    else viewModel.notice("Couldn't read the captured photo.")
                } finally {
                    file.delete()
                }
            }
        }
    }

    fun openCamera() {
        try {
            val dir = File(context.cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            cameraFile = file
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            cameraPicker.launch(uri)
        } catch (_: Exception) {
            viewModel.notice("Camera unavailable.")
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchScope.launch {
                delay(250)
                openCamera()
            }
        } else {
            viewModel.notice("Camera permission is needed to take a photo.")
        }
    }

    fun launchPickerAfterKeyboardCloses(action: () -> Unit) {
        focusManager.clearFocus()
        keyboardController?.hide()
        launchScope.launch {
            delay(250)
            action()
        }
    }

    fun pickDocument() {
        documentPicker.launch(
            arrayOf(
                "application/pdf",
                "text/plain",
                "text/markdown",
                "text/csv",
                "application/json",
                "application/xml",
                "text/x-java-source",
                "text/html",
            ),
        )
    }

    // ---- Auto-scroll on new content ----
    val lastMessage = messages.lastOrNull()
    LaunchedEffect(lastMessage?.id, lastMessage?.content?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        conversation?.title ?: "Chat",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.exitChat(onBack) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    ModelDropdown(
                        models = models,
                        selected = selectedModel,
                        isStreaming = isStreaming,
                        onSelect = { viewModel.selectModel(it) },
                        onOpenServer = { showServerDialog = true },
                    )
                    IconButton(onClick = { viewModel.refreshModels() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh models")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Delete chat",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ContextUsageIndicator(
                estimatedTokens = estimateContextTokens(
                    systemPrompt = conversation?.systemPrompt,
                    messages = messages,
                    pendingAttachments = pending,
                ),
                exactContextTokens = currentContextTokens,
                contextTokensEstimated = contextTokensEstimated,
                contextLimit = contextLimit,
                contextLimitLoading = contextLimitLoading,
            )
            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp)) {
                            Text(
                                "Start the conversation — the model will stream its reply here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    val canRetry = index == messages.lastIndex &&
                        message.isAssistant &&
                        message.status == "error" &&
                        message.error?.startsWith("Interrupted") == true
                    MessageBubble(
                        message = message,
                        onRetry = if (canRetry) ({ viewModel.retryInterrupted(message.id) }) else null,
                    )
                }
            }

            // Pending attachments preview
            if (pending.isNotEmpty()) {
                PendingAttachmentsRow(
                    attachments = pending,
                    onRemove = { viewModel.removePendingAttachment(it) },
                )
            }

            // Input bar
            InputBar(
                text = text,
                onTextChange = { text = it },
                isStreaming = isStreaming,
                canSend = text.isNotBlank() || pending.isNotEmpty(),
                onSend = {
                    if (!isStreaming && viewModel.send(text)) {
                        text = ""
                    }
                },
                onStop = { viewModel.stop() },
                onAttachClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    launchScope.launch {
                        delay(250)
                        showAttachMenu = true
                    }
                },
                attachMenuExpanded = showAttachMenu,
                supportsImages = supportsImages,
                allowImagesOverride = allowImagesOverride,
                canOverrideImages = server != null && selectedModel != null,
                onAllowImagesChange = viewModel::setAllowImages,
                onAttachMenuDismiss = { showAttachMenu = false },
                onPickImage = {
                    showAttachMenu = false
                    launchPickerAfterKeyboardCloses { imagePicker.launch("image/*") }
                },
                onTakePhoto = {
                    showAttachMenu = false
                    launchPickerAfterKeyboardCloses {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                onPickDocument = {
                    showAttachMenu = false
                    launchPickerAfterKeyboardCloses(::pickDocument)
                },
            )

            Spacer(Modifier.navigationBarsPadding())
        }
    }

    // Server dialog
    if (showServerDialog) {
        ServerDialog(
            savedServers = savedServers,
            initialHost = conversation?.host ?: (server?.host ?: ""),
            initialPort = conversation?.port ?: (server?.port ?: 0),
            initialApiKey = server?.apiKey.orEmpty(),
            initialType = server?.type ?: ServerType.AUTO,
            initialContextLimit = server?.contextLimitOverride,
            onDismiss = { showServerDialog = false },
            onConnect = { info ->
                viewModel.updateServerInfo(info)
                showServerDialog = false
            },
            onScan = {
                showServerDialog = false
                onOpenScanner()
            },
        )
    }

    // Delete dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this chat?") },
            text = { Text("All messages in this conversation will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteConversation(onBack)
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/* ==================================================================== */

@Composable
private fun ModelDropdown(
    models: List<String>,
    selected: String?,
    isStreaming: Boolean,
    onSelect: (String) -> Unit,
    onOpenServer: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = {
                if (models.isEmpty()) onOpenServer() else expanded = true
            },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selected ?: "No models",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        DropdownMenu(
            expanded = expanded && !isStreaming,
            onDismissRequest = { expanded = false },
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            model,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onSelect(model)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuItemRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun estimateContextTokens(
    systemPrompt: String?,
    messages: List<UiMessage>,
    pendingAttachments: List<Attachment>,
): Int {
    val characterCount = (systemPrompt.orEmpty().length) +
        messages.sumOf { message ->
            message.content.length + message.attachments.sumOf { it.text?.length ?: 0 }
        } + pendingAttachments.sumOf { it.text?.length ?: 0 }
    return (characterCount / 4) + (messages.size * 4)
}

@Composable
private fun ContextUsageIndicator(
    estimatedTokens: Int,
    exactContextTokens: Int?,
    contextTokensEstimated: Boolean,
    contextLimit: Int?,
    contextLimitLoading: Boolean,
) {
    val usedTokens = exactContextTokens ?: estimatedTokens
    val approximate = exactContextTokens == null || contextTokensEstimated
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = if (contextLimit != null && contextLimit > 0) {
                "Context: ${if (approximate) "~" else ""}" +
                    "${formatTokens(usedTokens)} / ${formatTokens(contextLimit)} tokens"
            } else {
                "Context: ${if (approximate) "~" else ""}" +
                    "${formatTokens(usedTokens)} tokens · " +
                    if (contextLimitLoading) "loading limit…" else "limit unavailable"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun formatTokens(tokens: Int): String =
    if (tokens >= 1_000) "${"%.1f".format(tokens / 1_000f)}k" else tokens.toString()

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    isStreaming: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachClick: () -> Unit,
    attachMenuExpanded: Boolean,
    supportsImages: Boolean?,
    allowImagesOverride: Boolean,
    canOverrideImages: Boolean,
    onAllowImagesChange: (Boolean) -> Unit,
    onAttachMenuDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickDocument: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .imePadding(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                IconButton(onClick = onAttachClick) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "Attach",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = attachMenuExpanded,
                    onDismissRequest = onAttachMenuDismiss,
                ) {
                    if (supportsImages == true) {
                        MenuItemRow(Icons.Default.Image, "Choose image", onPickImage)
                        MenuItemRow(Icons.Default.Add, "Take photo", onTakePhoto)
                    } else {
                        Text(
                            if (supportsImages == false) "This model does not support images"
                            else "Image support not confirmed",
                            modifier = Modifier.widthIn(max = 260.dp).padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (canOverrideImages) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Allow images for this model")
                                    Text("Use when you know this model supports vision.",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.widthIn(max = 240.dp))
                                }
                            },
                            trailingIcon = {
                                androidx.compose.material3.Checkbox(
                                    checked = allowImagesOverride, onCheckedChange = null,
                                )
                            },
                            onClick = { onAllowImagesChange(!allowImagesOverride) },
                        )
                    }
                    MenuItemRow(Icons.Default.Description, "Attach document", onPickDocument)
                }
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    cursorBrush = Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary),
                        androidx.compose.ui.geometry.Offset.Zero,
                        androidx.compose.ui.geometry.Offset.Infinite,
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default.copy(
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { if (canSend && !isStreaming) onSend() },
                    ),
                ) { innerTextField ->
                    if (text.isEmpty()) {
                        Text(
                            "Message…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    innerTextField()
                }
            }
            Spacer(Modifier.width(4.dp))
            if (isStreaming) {
                Surface(
                    onClick = onStop,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            } else {
                val enabled = canSend
                Surface(
                    onClick = { if (enabled) onSend() },
                    shape = CircleShape,
                    color = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingAttachmentsRow(
    attachments: List<Attachment>,
    onRemove: (Attachment) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (attachment.kind == AttachmentKind.IMAGE) {
                        AsyncImage(
                            model = attachment.dataUri(),
                            contentDescription = attachment.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                        )
                    } else {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        attachment.name ?: "attachment",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    IconButton(onClick = { onRemove(attachment) }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface StoredImageState {
    data object Loading : StoredImageState
    data class Loaded(val bitmap: ImageBitmap) : StoredImageState
    data object Failed : StoredImageState
}

/** Decodes the image bytes persisted in Room, independent of the source URI or Coil data-URI support. */
@Composable
private fun StoredImage(
    base64: String?,
    cacheKey: String,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    val state by produceState<StoredImageState>(StoredImageState.Loading, cacheKey) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeStoredImage(base64) }
                .getOrNull()
                ?.let(StoredImageState::Loaded)
                ?: StoredImageState.Failed
        }
    }

    when (val imageState = state) {
        is StoredImageState.Loaded -> Image(
            bitmap = imageState.bitmap,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
        StoredImageState.Loading -> Box(
            modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        StoredImageState.Failed -> Box(
            modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Image unavailable",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun decodeStoredImage(base64: String?): ImageBitmap? {
    if (base64.isNullOrBlank()) return null
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (longestSide / sampleSize > 512) sampleSize *= 2
    val bitmap = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    ) ?: return null

    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
    }
    val oriented = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        matrix,
        true,
    ).also { if (it !== bitmap) bitmap.recycle() }
    return oriented.asImageBitmap()
}

/* ---------------- Message bubble ---------------- */

@Composable
private fun MessageBubble(message: UiMessage, onRetry: (() -> Unit)?) {
    val isUser = message.isUser
    val bubbleColor =
        if (isUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // Attachments above the text.
        if (message.attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                message.attachments.take(4).forEachIndexed { index, attachment ->
                    if (attachment.kind == AttachmentKind.IMAGE) {
                        StoredImage(
                            base64 = attachment.base64,
                            cacheKey = "${message.id}:$index",
                            contentDescription = attachment.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(12.dp),
                                ),
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    attachment.name ?: "attachment",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (message.status == "streaming" && message.content.isBlank()) {
            TypingIndicator()
        } else if (message.content.isNotBlank()) {
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp,
                ),
            ) {
                val contentModifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .widthIn(max = 560.dp)
                if (message.isAssistant) {
                    AssistantMarkdown(message.content, modifier = contentModifier)
                } else {
                    Text(
                        text = message.content,
                        modifier = contentModifier,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        if (message.status == "error" && message.error != null) {
            Text(
                message.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Retry")
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "typingAlpha",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp, topStart = 16.dp, bottomStart = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { i ->
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = alpha * (0.4f + 0.3f * i),
                            ),
                        ),
                )
                if (i < 2) Spacer(Modifier.width(6.dp))
            }
        }
    }
}

@Composable
private fun ServerDialog(
    savedServers: List<dev.lmchat.data.local.ServerEntity>,
    initialHost: String,
    initialPort: Int,
    initialApiKey: String,
    initialType: ServerType,
    initialContextLimit: Int?,
    onDismiss: () -> Unit,
    onConnect: (ServerInfo) -> Unit,
    onScan: () -> Unit,
) {
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort.toString()) }
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var showManualForm by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(initialType) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (showManualForm) "Enter server details" else "Choose a server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showManualForm) {
                    ServerTypePicker(type) { selected ->
                        if (port.toIntOrNull() == type.defaultPort || port.toIntOrNull() !in 1..65535) {
                            port = selected.defaultPort.toString()
                        }
                        type = selected
                    }
                    TextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host or full URL") },
                        supportingText = { Text("Full HTTP(S) URLs override the Port field.") },
                        placeholder = { Text("e.g. 192.168.1.20") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default.copy(
                            keyboardType = KeyboardType.Uri,
                        ),
                    )
                    TextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        placeholder = { Text("1234") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default.copy(
                            keyboardType = KeyboardType.Number,
                        ),
                    )
                    TextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API key (optional)") },
                        singleLine = true,
                    )
                } else if (savedServers.isEmpty()) {
                    Text("No saved servers yet. Scan your network in Settings or enter a server address.")
                } else {
                    Text("Select a saved server to connect.")
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(savedServers, key = { it.id }) { saved ->
                            Surface(
                                onClick = {
                                    onConnect(saved.info)
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(
                                        saved.name.ifBlank { "${saved.host}:${saved.port}" },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        "${saved.host}:${saved.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (showManualForm) {
                TextButton(enabled = host.isNotBlank() && serverPort(host, port) != null, onClick = {
                    val portValue = serverPort(host, port) ?: 0
                    if (host.isNotBlank() && portValue in 1..65535) {
                        onConnect(ServerInfo(host.trim(), portValue, apiKey, type,
                            initialContextLimit.takeIf { host.trim() == initialHost && portValue == initialPort }))
                    }
                }) { Text("Connect") }
            } else {
                TextButton(onClick = { showManualForm = true }) {
                    Text("Enter manually")
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onScan) { Text("Scan in Settings") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
