package dev.lmchat.data.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Starts protection while the user is still in the foreground, before opening SSE. */
class StreamingKeepAlive(private val context: Context) {
    @Volatile
    var serviceRunning = false
        internal set

    private val _activeChats = MutableStateFlow<Set<String>>(emptySet())
    val activeChats = _activeChats.asStateFlow()

    suspend fun acquire(conversationId: String) = withContext(Dispatchers.Main.immediate) {
        val previous = _activeChats.value
        _activeChats.value = previous + conversationId
        try {
            ContextCompat.startForegroundService(context, Intent(context, StreamingService::class.java))
        } catch (error: Exception) {
            _activeChats.value = previous
            throw error
        }
    }

    suspend fun release(conversationId: String) = withContext(Dispatchers.Main.immediate) {
        val previous = _activeChats.value
        _activeChats.value = previous - conversationId
        if (serviceRunning && previous.isNotEmpty() && _activeChats.value.isEmpty()) {
            // Deliver a final start ID so stopSelfResult cannot stop a newer request.
            try {
                context.startService(Intent(context, StreamingService::class.java))
            } catch (_: IllegalStateException) {
                // The service may already have been stopped by Android's FGS timeout.
                context.stopService(Intent(context, StreamingService::class.java))
            }
        }
    }
}
