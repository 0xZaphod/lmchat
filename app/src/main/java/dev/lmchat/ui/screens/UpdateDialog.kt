package dev.lmchat.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider

@Composable
fun UpdateDialog(viewModel: UpdateViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    fun install() {
        val apk = viewModel.state.value.apk ?: return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            viewModel.error("Couldn't open the installer. Please try again.")
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else viewModel.error("Allow LMChat to install updates, then tap Install again.")
    }
    if (!state.visible) return
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(state.release?.let { "LMChat ${it.version} available" } ?: "App updates") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.release?.let { release ->
                    Text("Installed: ${viewModel.installedVersion}")
                    if (release.notes.isNotBlank()) Text(release.notes)
                    if (state.apk != null) Text("Ready to install. Android will ask you to confirm the update.")
                }
                if (state.downloading) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading… ${(state.progress * 100).toInt()}%")
                }
                state.message?.let { Text(it) }
            }
        },
        confirmButton = {
            if (state.release != null) TextButton(enabled = !state.downloading, onClick = {
                if (state.apk == null) viewModel.download()
                else if (context.packageManager.canRequestPackageInstalls()) install()
                else try {
                    permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")))
                } catch (e: Exception) {
                    viewModel.error("Couldn't open install permissions. Enable them in Android Settings.")
                }
            }) { Text(if (state.apk == null) "Update" else "Install") }
            else TextButton(onClick = viewModel::dismiss) { Text("OK") }
        },
        dismissButton = {
            if (state.release != null) TextButton(enabled = !state.downloading, onClick = viewModel::dismiss) { Text("Later") }
        },
    )
}
