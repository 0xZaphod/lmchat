package dev.lmchat.data.repository

import dev.lmchat.data.local.AppDatabase
import dev.lmchat.data.local.ServerEntity
import dev.lmchat.domain.DiscoveredServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

/** CRUD for user-saved server endpoints (manual entry + scan results). */
class ServerRepository(private val db: AppDatabase) {

    private val dao get() = db.serverDao()

    fun observeAll(): Flow<List<ServerEntity>> = dao.observeAll()

    suspend fun save(server: ServerEntity) = dao.upsert(server)

    suspend fun delete(id: String) = dao.delete(id)

    /** Persist a discovered server under a stable id so re-scans don't duplicate it. */
    suspend fun saveDiscovered(discovered: DiscoveredServer, name: String?): ServerEntity {
        // A re-scan must not erase credentials, provider, or a manually set limit.
        dao.observeAll().first().firstOrNull { it.host == discovered.host && it.port == discovered.port }
            ?.let { return it }
        val entity = ServerEntity(
            id = "srv_${discovered.host}_${discovered.port}",
            name = name?.trim()?.ifBlank { null } ?: discovered.label,
            host = discovered.host,
            port = discovered.port,
            apiKey = null,
            createdAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        return entity
    }
}

private val DiscoveredServer.label: String get() = "$host:$port"
