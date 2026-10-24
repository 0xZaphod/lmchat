package dev.lmchat.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everySupportedVersionPreservesHistoryAttachmentsAndServers() = runBlocking {
        for (version in 1..3) {
            val name = "migration-$version.db"
            context.deleteDatabase(name)
            try {
                createOldDatabase(name, version)
                val db = AppDatabase.build(context, name)
                try {
                    val conversation = db.conversationDao().getById("chat")!!
                    assertEquals("Saved chat", conversation.title)
                    assertEquals("test-model", conversation.model)
                    assertEquals("system instruction", conversation.systemPrompt)
                    assertEquals(if (version >= 2) 42 else null, conversation.contextTokens)
                    assertFalse(conversation.contextTokensEstimated)
                    assertEquals(if (version >= 3) "OLLAMA" else "AUTO", conversation.serverType)
                    assertEquals(if (version >= 3) 4096 else null, conversation.contextLimitOverride)
                    val messages = db.messageDao().observeByConversation("chat").first()
                    assertEquals("original text", messages.single().message.content)
                    assertEquals("extracted text", messages.single().attachments.single().data)
                    val server = db.serverDao().observeAll().first().single()
                    assertEquals("test-key", server.apiKey)
                    assertEquals(if (version >= 3) "OLLAMA" else "AUTO", server.serverType)
                    // Relationships still enforce cascading deletion after upgrading.
                    db.conversationDao().delete("chat")
                    assertTrue(db.messageDao().observeByConversation("chat").first().isEmpty())
                    db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM attachments").use {
                        assertTrue(it.moveToFirst())
                        assertEquals(0, it.getInt(0))
                    }
                } finally {
                    db.close()
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test fun unknownVersionFailsWithoutErasingHistory() {
        val name = "unsupported-schema.db"
        context.deleteDatabase(name)
        try {
            createOldDatabase(name, 1)
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { it.version = 99 }
            val db = AppDatabase.build(context, name)
            try {
                try {
                    db.openHelper.writableDatabase
                    fail("An unknown version must require an explicit migration")
                } catch (_: IllegalStateException) {
                    // Expected: no destructive migration fallback.
                }
            } finally {
                db.close()
            }
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                old.rawQuery("SELECT content FROM messages WHERE id = 'message'", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("original text", it.getString(0))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    // Historical schemas predate Room schema export. These fixtures model the shipped
    // tables, then let Room run the real migrations and validate the resulting schema.
    private fun createOldDatabase(name: String, version: Int) {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, model TEXT, host TEXT, port INTEGER, systemPrompt TEXT)")
            db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, status TEXT NOT NULL, error TEXT, model TEXT, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
            db.execSQL("CREATE TABLE attachments (id TEXT NOT NULL PRIMARY KEY, messageId TEXT NOT NULL, kind TEXT NOT NULL, name TEXT, mimeType TEXT, data TEXT NOT NULL, FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_attachments_messageId ON attachments(messageId)")
            db.execSQL("CREATE TABLE servers (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, apiKey TEXT, createdAt INTEGER NOT NULL)")
            db.execSQL("INSERT INTO conversations VALUES ('chat', 'Saved chat', 1, 2, 'test-model', '127.0.0.1', 1234, 'system instruction')")
            db.execSQL("INSERT INTO messages VALUES ('message', 'chat', 'user', 'original text', 1, 'done', NULL, 'test-model')")
            db.execSQL("INSERT INTO attachments VALUES ('attachment', 'message', 'document', 'note.txt', 'text/plain', 'extracted text')")
            db.execSQL("INSERT INTO servers VALUES ('server', 'Saved server', '127.0.0.1', 1234, 'test-key', 1)")
            if (version >= 2) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN contextTokens INTEGER")
                db.execSQL("UPDATE conversations SET contextTokens = 42")
            }
            if (version >= 3) {
                for (table in listOf("conversations", "servers")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN serverType TEXT NOT NULL DEFAULT 'AUTO'")
                    db.execSQL("ALTER TABLE $table ADD COLUMN contextLimitOverride INTEGER")
                    db.execSQL("UPDATE $table SET serverType = 'OLLAMA', contextLimitOverride = 4096")
                }
            }
            db.version = version
        }
    }
}
