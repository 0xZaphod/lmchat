package dev.lmchat.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class MessageWithAttachments(
    @Embedded val message: MessageEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "messageId",
    )
    val attachments: List<AttachmentEntity>,
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET contextTokens = :tokens, contextTokensEstimated = :estimated WHERE id = :id")
    suspend fun updateContextTokens(id: String, tokens: Int, estimated: Boolean = false)

    @Query("UPDATE conversations SET contextTokens = NULL, contextTokensEstimated = 1 WHERE id = :id")
    suspend fun invalidateContextTokens(id: String)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateAfterResponse(id: String, updatedAt: Long)

    @Query("UPDATE conversations SET model = :model WHERE id = :id")
    suspend fun updateModel(id: String, model: String?)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Transaction
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, rowid ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageWithAttachments>>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET content = :content, status = :status, error = :error WHERE id = :id")
    suspend fun updateContent(id: String, content: String, status: String, error: String?)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND status != 'done' LIMIT 1")
    suspend fun findActive(conversationId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE status = 'streaming'")
    suspend fun findActiveAll(): List<MessageEntity>

    @Query("UPDATE messages SET status = 'error', error = :error WHERE id = :id AND status = 'streaming'")
    suspend fun interruptStreaming(id: String, error: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latest(conversationId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countByConversation(conversationId: String): Int

    @Query("SELECT conversationId FROM messages WHERE id = :id")
    suspend fun conversationIdForMessage(id: String): String?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND rowid > (SELECT rowid FROM messages WHERE id = :afterId)")
    suspend fun deleteAfter(afterId: String, conversationId: String)
}

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(attachment: AttachmentEntity)

    @Insert
    suspend fun insertAll(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun deleteByMessage(messageId: String)
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(server: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun delete(id: String)
}
