package dev.lmchat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lmchat.LMChatApplication
import dev.lmchat.domain.DiscoveredServer
import dev.lmchat.data.local.ServerEntity

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScannerSection(onServerSaved: suspend (ServerEntity) -> Unit = {}) {
    val context = LocalContext.current
    val container = (context.applicationContext as LMChatApplication).container
    val viewModel: ScannerViewModel = viewModel(factory = ScannerViewModel.factory(container))

    val portText by viewModel.portText.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val scanned by viewModel.scanned.collectAsState()
    val total by viewModel.total.collectAsState()
    val results by viewModel.results.collectAsState()
    val finished by viewModel.finished.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val candidates by viewModel.candidates.collectAsState()
    val selectedPrefixes by viewModel.selectedPrefixes.collectAsState()
    val customSubnet by viewModel.customSubnet.collectAsState()
    var showCustomSubnet by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Find server", style = MaterialTheme.typography.titleMedium)
        Text(
            "Scan your local network for compatible LLM servers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        if (it.isEmpty() || it.all { c -> c.isDigit() }) viewModel.setPortText(it)
                    },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(110.dp),
                )
                Spacer(Modifier.width(12.dp))
                if (scanning) {
                    Button(onClick = { viewModel.stop() }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    }
                } else {
                    Button(onClick = { viewModel.start() }) {
                        Icon(Icons.Default.Hub, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan")
                    }
                }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("LM Studio" to 1234, "Ollama" to 11434, "llama.cpp / LocalAI" to 8080, "vLLM" to 8000)
                .chunked(2)
                .forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { (label, port) ->
                            Surface(
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = if (portText == port.toString()) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                enabled = !scanning,
                                onClick = { viewModel.setPortText(port.toString()) },
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "$label: $port",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                                    )
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
        }

        TextButton(
            modifier = Modifier.height(32.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            onClick = { showCustomSubnet = !showCustomSubnet },
        ) {
            Text(if (customSubnet.isBlank()) "Advanced: custom subnet" else "Using custom subnet")
        }
        if (showCustomSubnet || customSubnet.isNotBlank()) {
            OutlinedTextField(
                value = customSubnet,
                onValueChange = viewModel::setCustomSubnet,
                label = { Text("Custom subnet (CIDR)") },
                placeholder = { Text("192.168.68.0/22") },
                supportingText = { Text("Optional. Overrides detected subnets; prefix /16–/32.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            candidates.forEach { c ->
                val checked = c.prefix in selectedPrefixes
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (checked) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    onClick = { viewModel.togglePrefix(c.prefix) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.prefix, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            if (checked) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        if (scanning) {
            LinearProgressIndicator(
                progress = { (progress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Scanning… $scanned/$total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (results.isEmpty() && finished) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.Hub,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    notice ?: "No servers found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Make sure your phone and server are on the same network and the server accepts network connections.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        results.forEach { server ->
            ResultRow(
                server = server,
                onSave = { viewModel.save(server, onServerSaved) },
            )
        }
    }
}

@Composable
private fun ResultRow(
    server: DiscoveredServer,
    onSave: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${server.host}:${server.port}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (server.isCompatible) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Text(
                            if (server.isCompatible) "Compatible API" else "Open port",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (server.models.isNotEmpty()) {
                    Text(
                        server.models.first() +
                            if (server.models.size > 1) "  (+${server.models.size - 1} more)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSave) { Text("Add") }
        }
    }
}
