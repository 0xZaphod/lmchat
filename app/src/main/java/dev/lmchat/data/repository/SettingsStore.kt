package dev.lmchat.data.repository

import android.content.Context
import dev.lmchat.domain.ServerInfo
import dev.lmchat.domain.ServerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Simple SharedPreferences-backed store for "last used" defaults:
 * the server new chats should try to connect to, plus generation settings.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val imageOverrides = MutableStateFlow(prefs.getStringSet("image_model_overrides", emptySet())!!.toSet())
    val allowedImageModels = imageOverrides.asStateFlow()

    private fun imageModelKey(server: ServerInfo, model: String): String = JsonArray(listOf(
        JsonPrimitive(server.baseUrl.removeSuffix("/v1")), JsonPrimitive(model),
    )).toString()

    fun allowsImages(server: ServerInfo, model: String): Boolean =
        imageModelKey(server, model) in imageOverrides.value

    fun setAllowImages(server: ServerInfo, model: String, allow: Boolean) {
        val key = imageModelKey(server, model)
        val updated = if (allow) imageOverrides.value + key else imageOverrides.value - key
        prefs.edit().putStringSet("image_model_overrides", updated).apply()
        imageOverrides.value = updated
    }

    var host: String
        get() = prefs.getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1"
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var serverType: ServerType
        get() = ServerType.fromStorage(prefs.getString("server_type", null))
        set(value) = prefs.edit().putString("server_type", value.name).apply()

    var contextLimitOverride: Int?
        get() = prefs.getInt("context_limit_override", 0).takeIf { it > 0 }
        set(value) = prefs.edit().putInt("context_limit_override", value ?: 0).apply()

    fun serverInfo() = ServerInfo(host.trim(), port, apiKey.ifBlank { null }, serverType, contextLimitOverride)

    fun useServer(server: ServerInfo) {
        prefs.edit().putString(KEY_HOST, server.host.trim()).putInt(KEY_PORT, server.port)
            .putString(KEY_API_KEY, server.apiKey.orEmpty().trim())
            .putString("server_type", server.type.name)
            .putInt("context_limit_override", server.contextLimitOverride ?: 0).apply()
    }

    /** Model to prefer when starting a new chat (blank = auto-pick first). */
    var preferredModel: String
        get() = prefs.getString(KEY_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    companion object {
        const val PREFS_NAME = "lmchat_settings"
        const val DEFAULT_PORT = 1234
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "preferred_model"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
    }
}
