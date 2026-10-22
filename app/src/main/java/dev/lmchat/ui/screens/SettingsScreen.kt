package dev.lmchat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.lmchat.LMChatApplication
import dev.lmchat.data.local.ServerEntity
import dev.lmchat.data.repository.SettingsStore
import dev.lmchat.domain.serverPort
import dev.lmchat.domain.ServerType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    updates: UpdateViewModel,
    onBack: () -> Unit,
    onConfigurationComplete: () -> Unit = {},
) {

    val context = androidx.compose.ui.platform.LocalContext.current
    val container = (context.applicationContext as LMChatApplication).container
    val settings = container.settings
    val servers by container.serverRepository.observeAll().collectAsState(initial = emptyList())
    val serverRepo = container.serverRepository

    var confirmDelete by remember { mutableStateOf<ServerEntity?>(null) }
    var editingServer by remember { mutableStateOf<ServerEntity?>(null) }
    var addingServer by remember { mutableStateOf(false) }
    var selectedDefault by remember { mutableStateOf(settings.host to settings.port) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            item {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        ScannerSection()
                    }
                }
            }

            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { addingServer = true },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add server manually")
                }
            }

            item {
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionTitle("Saved servers", Icons.Default.Dns)
                        if (servers.isEmpty()) {
                            Text(
                                text = "No saved servers yet. Scan for a server or add one manually.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            servers.forEach { server ->
                                val isDefault = selectedDefault.first.equals(server.host, ignoreCase = true) && selectedDefault.second == server.port
                                Card(
                                    colors = androidx.compose.material3.CardDefaults.cardColors(
                                        containerColor = if (isDefault) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                    ),
                                ) {
                                    Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        if (isDefault) Text("Default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        Text(
                                            text = server.name.ifBlank { "${server.host}:${server.port}" },
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            text = "${server.host}:${server.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            settings.useServer(server.info)
                                            selectedDefault = server.host to server.port
                                            onConfigurationComplete()
                                        },
                                    ) {
                                        Text("Use")
                                    }
                                    IconButton(onClick = { editingServer = server }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit ${server.name}")
                                    }
                                    IconButton(
                                        onClick = { confirmDelete = server },
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionTitle("About", Icons.Default.Save)
                        val updateState by updates.state.collectAsState()
                        Text("Version ${updates.installedVersion}")
                        OutlinedButton(onClick = { updates.check(manual = true) }, enabled = !updateState.checking && !updateState.downloading) {
                            Text(if (updateState.checking) "Checking…" else "Check for updates")
                        }
                        Text(
                            text = "LMChat — chat with LM Studio, Ollama, llama.cpp, or an OpenAI-compatible server such as vLLM or LocalAI. " +
                                    "Use a reachable server address, on your local network or VPN. " +
                                    "Use a vision-capable model to send images. " +
                                    "Documents (PDF, TXT, MD, CSV, JSON, code) are converted to text and included in the prompt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete server?") },
            text = { Text("Remove ${server.host}:${server.port} from saved servers?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val deletingDefault = settings.host.equals(server.host, ignoreCase = true) &&
                            settings.port == server.port
                        container.serverRepository.delete(server.id)
                        if (deletingDefault) {
                            val nextDefault = container.serverRepository.observeAll().first().firstOrNull()
                            settings.host = nextDefault?.host ?: "127.0.0.1"
                            settings.port = nextDefault?.port ?: SettingsStore.DEFAULT_PORT
                            settings.apiKey = nextDefault?.apiKey ?: ""
                            settings.serverType = nextDefault?.info?.type ?: ServerType.AUTO
                            settings.contextLimitOverride = nextDefault?.contextLimitOverride
                            selectedDefault = settings.host to settings.port
                        }
                        confirmDelete = null
                        snackbarHostState.showSnackbar(
                            if (deletingDefault) {
                                "Server deleted. Default updated to ${settings.host}:${settings.port}."
                            } else {
                                "Server deleted"
                            },
                        )
                    }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }

    editingServer?.let { server ->
        EditServerDialog(
            server = server,
            settings = settings,
            onDismiss = { editingServer = null },
            onSave = { updated, preferredModel, systemPrompt ->
                scope.launch {
                    serverRepo.save(updated)
                    val isDefault = settings.host.equals(server.host, ignoreCase = true) && settings.port == server.port
                    if (isDefault) {
                        settings.useServer(updated.info)
                        selectedDefault = updated.host to updated.port
                    }
                    settings.preferredModel = preferredModel.trim()
                    settings.systemPrompt = systemPrompt
                    editingServer = null
                    snackbarHostState.showSnackbar("Server updated")
                }
            },
        )
    }

    if (addingServer) {
        EditServerDialog(
            server = null,
            settings = settings,
            onDismiss = { addingServer = false },
            onSave = { added, preferredModel, systemPrompt ->
                scope.launch {
                    val existing = servers.firstOrNull { it.host.equals(added.host, ignoreCase = true) && it.port == added.port }
                    val serverToSave = if (existing == null) added else added.copy(id = existing.id)
                    serverRepo.save(serverToSave)
                    settings.preferredModel = preferredModel.trim()
                    settings.systemPrompt = systemPrompt
                    if (servers.isEmpty() || settings.host == "127.0.0.1") {
                        settings.useServer(serverToSave.info)
                        selectedDefault = serverToSave.host to serverToSave.port
                        onConfigurationComplete()
                    }
                    addingServer = false
                    snackbarHostState.showSnackbar("Server added")
                }
            },
        )
    }
}

@Composable
private fun EditServerDialog(
    server: ServerEntity?,
    settings: SettingsStore,
    onDismiss: () -> Unit,
    onSave: (ServerEntity, String, String) -> Unit,
) {
    val key = server?.id ?: "new"
    var name by remember(key) { mutableStateOf(server?.name.orEmpty()) }
    var host by remember(key) { mutableStateOf(server?.host ?: settings.host.takeUnless { it == "127.0.0.1" }.orEmpty()) }
    var portText by remember(key) { mutableStateOf((server?.port ?: settings.port).toString()) }
    var apiKey by remember(key) { mutableStateOf(server?.apiKey ?: settings.apiKey) }
    var type by remember(key) { mutableStateOf(server?.info?.type ?: settings.serverType) }
    var contextLimitText by remember(key) { mutableStateOf((server?.contextLimitOverride ?: settings.contextLimitOverride)?.toString().orEmpty()) }
    var preferredModel by remember(key) { mutableStateOf(settings.preferredModel) }
    var systemPrompt by remember(key) { mutableStateOf(settings.systemPrompt) }
    val port = serverPort(host, portText)
    val contextLimit = when {
        contextLimitText.isBlank() -> null
        else -> contextLimitText.toIntOrNull()?.takeIf { it > 0 }
    }
    val canSave = host.isNotBlank() && port != null && (contextLimitText.isBlank() || contextLimit != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (server == null) "Add server manually" else "Edit saved server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                ServerTypePicker(type) { type = it }
                OutlinedTextField(host, { host = it }, label = { Text("Host or full URL") }, supportingText = { Text("Full HTTP(S) URLs override the Port field.") }, singleLine = true)
                OutlinedTextField(
                    portText, { portText = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key (optional)") }, singleLine = true)
                OutlinedTextField(
                    contextLimitText, { contextLimitText = it.filter(Char::isDigit) },
                    label = { Text("Context limit override") }, singleLine = true,
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    preferredModel, { preferredModel = it }, label = { Text("Preferred model (optional)") }, singleLine = true,
                    placeholder = { Text("Auto-picks the first model") },
                )
                OutlinedTextField(
                    systemPrompt, { systemPrompt = it }, label = { Text("System prompt") },
                    placeholder = { Text("Optional instructions sent with every chat") }, minLines = 3, maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        (server ?: ServerEntity(
                            id = "srv_${host.trim()}_${port}", name = name.trim(), host = host.trim(), port = port!!,
                            apiKey = null, createdAt = System.currentTimeMillis(),
                        )).copy(
                            id = "srv_${host.trim()}_${port}",
                            name = name.trim().ifBlank { "${host.trim()}:$port" },
                            host = host.trim(),
                            port = port!!,
                            apiKey = apiKey.trim().ifBlank { null },
                            serverType = type.name,
                            contextLimitOverride = contextLimit,
                        ),
                        preferredModel,
                        systemPrompt,
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(4.dp))
}
