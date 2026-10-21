package dev.lmchat.data.local

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val model: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val systemPrompt: String? = null,
    val contextTokens: Int? = null,
    @ColumnInfo(defaultValue = "0") val contextTokensEstimated: Boolean = false,
    @ColumnInfo(defaultValue = "'AUTO'") val serverType: String = "AUTO",
    val contextLimitOverride: Int? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val status: String = "done",
    val error: String? = null,
    val model: String? = null,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("messageId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val kind: String, // "image" | "document"
    val name: String?,
    val mimeType: String?,
    val data: String, // image: base64 image bytes; document: extracted text
)

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val apiKey: String? = null,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "'AUTO'") val serverType: String = "AUTO",
    val contextLimitOverride: Int? = null,
) {
    val info: dev.lmchat.domain.ServerInfo
        get() = dev.lmchat.domain.ServerInfo(host, port, apiKey,
            dev.lmchat.domain.ServerType.fromStorage(serverType), contextLimitOverride)
}
