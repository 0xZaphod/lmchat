package dev.lmchat.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.lmchat.domain.ServerType

@Composable
fun ServerTypePicker(value: ServerType, onChange: (ServerType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Server type: ${value.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ServerType.entries.forEach { type ->
                DropdownMenuItem(text = { Text(type.label) }, onClick = {
                    expanded = false
                    onChange(type)
                })
            }
        }
    }
}
