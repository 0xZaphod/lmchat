package dev.lmchat.data.remote

import dev.lmchat.domain.ModelMetadata
import dev.lmchat.domain.ServerInfo
import dev.lmchat.domain.ServerType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Optional metadata must never prevent ordinary OpenAI-compatible chat. */
class ModelMetadataReader(client: OkHttpClient) {
    private val metadataClient = client.newBuilder()
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()

    suspend fun read(server: ServerInfo, model: String): ModelMetadata = withContext(Dispatchers.IO) {
        fun request(path: String, body: JsonObject? = null, queryModel: Boolean = false): JsonObject? {
            coroutineContext.ensureActive()
            return try {
                val url = (server.baseUrl + path).toHttpUrl().newBuilder().apply {
                    if (queryModel) addQueryParameter("model", model)
                }.build()
                val builder = Request.Builder().url(url).apply {
                    server.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                    body?.let { post(it.toString().toRequestBody("application/json".toMediaType())) }
                }
                metadataClient.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) null
                    else JSON.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

        fun lmStudio() = request("/api/v1/models")?.let { MetadataParser.lmStudio(it, model) }
        fun ollama(): ModelMetadata? {
            val details = request("/api/show", buildJsonObject { put("model", model) }) ?: return null
            // Only a loaded runner reports the actual configured limit. Model training
            // limits and Modelfile defaults are not necessarily the running limit.
            val running = request("/api/ps")
            return MetadataParser.ollama(details, running, model)
        }
        fun llamaCpp() = request("/props", queryModel = true)?.let(MetadataParser::llamaCpp)
        fun generic() = request("/v1/models")?.let { MetadataParser.generic(it, model) }

        val result = when (server.type) {
            ServerType.LM_STUDIO -> lmStudio()
            ServerType.OLLAMA -> ollama()
            ServerType.LLAMA_CPP -> llamaCpp()
            ServerType.OPENAI_COMPATIBLE -> generic()
            ServerType.AUTO -> lmStudio() ?: ollama() ?: llamaCpp() ?: generic()
        } ?: ModelMetadata()
        // Older Strata servers expose vision only through health, before the
        // llama.cpp-compatible discovery endpoints were added.
        val images = if (result.supportsImages == null) {
            request("/health")?.let { MetadataParser.strataHealth(it, model) }?.supportsImages
        } else result.supportsImages
        result.copy(supportsImages = images,
            contextLength = server.contextLimitOverride?.takeIf { it > 0 } ?: result.contextLength)
    }
}

/** Parse only explicit capability/configuration fields; never guess from model names. */
internal object MetadataParser {
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.positiveInt(key: String) = (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    private fun JsonObject.boolean(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.strings(key: String) = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    fun strataHealth(root: JsonObject, modelId: String): ModelMetadata? {
        if (root.string("service") != "strata" || root.string("model") != modelId) return null
        return ModelMetadata(supportsImages = root.boolean("images"))
    }

    fun lmStudio(root: JsonObject, modelId: String): ModelMetadata? {
        val models = root.objects("models")
        fun instances(model: JsonObject) = model.objects("loaded_instances")
        fun normalized(id: String?) = id?.substringAfterLast('/')?.substringBefore('@')?.lowercase()
        val exactInstanceOwner = models.firstOrNull { model -> instances(model).any { it.string("id") == modelId } }
        val matchingVariants = models.filter { model ->
            normalized(model.string("key")) == normalized(modelId) ||
                instances(model).any { normalized(it.string("id")) == normalized(modelId) }
        }
        val model = exactInstanceOwner ?: models.firstOrNull { it.string("key") == modelId || it.string("id") == modelId }
            ?: matchingVariants.singleOrNull()
        if (model == null) {
            if (matchingVariants.isEmpty()) return null
            // Unloaded quantizations can share a public model id. Confirm vision
            // only when every possible variant reports the same capability.
            return ModelMetadata(supportsImages = matchingVariants.map {
                it.obj("capabilities")?.boolean("vision")
            }.distinct().singleOrNull())
        }
        val instance = instances(model).firstOrNull { it.string("id") == modelId }
            ?: instances(model).singleOrNull()
        val config = instance?.obj("config")
        return ModelMetadata(
            supportsImages = model.obj("capabilities")?.boolean("vision"),
            contextLength = config?.positiveInt("context_length") ?: config?.positiveInt("contextLength"),
        )
    }

    fun ollama(details: JsonObject, running: JsonObject?, modelId: String): ModelMetadata? {
        if (details["capabilities"] == null && details["model_info"] == null) return null
        fun normalized(id: String?) = id?.let { if (':' in it.substringAfterLast('/')) it else "$it:latest" }
        val runner = running?.objects("models")?.firstOrNull {
            normalized(it.string("name")) == normalized(modelId) || normalized(it.string("model")) == normalized(modelId)
        }
        return ModelMetadata(
            supportsImages = details.strings("capabilities")?.contains("vision"),
            contextLength = runner?.positiveInt("context_length"),
        )
    }

    fun llamaCpp(root: JsonObject): ModelMetadata? {
        if (root["default_generation_settings"] == null && root["modalities"] == null) return null
        return ModelMetadata(
            supportsImages = root.obj("modalities")?.boolean("vision"),
            contextLength = root.obj("default_generation_settings")?.positiveInt("n_ctx"),
        )
    }

    fun generic(root: JsonObject, modelId: String): ModelMetadata? {
        val model = root.objects("data").firstOrNull { it.string("id") == modelId } ?: return null
        val modalities = model.obj("architecture")?.strings("input_modalities") ?: model.strings("input_modalities")
        return ModelMetadata(
            supportsImages = model.obj("capabilities")?.boolean("vision")
                ?: modalities?.contains("image"),
            // vLLM reports the serving limit here. Do not use max_context_length
            // or n_ctx_train: those may describe training, not server settings.
            contextLength = model.positiveInt("max_model_len"),
        )
    }
}
