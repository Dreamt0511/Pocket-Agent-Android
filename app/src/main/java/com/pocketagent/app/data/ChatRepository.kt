package com.pocketagent.app.data

import com.pocketagent.app.data.entity.Conversation
import com.pocketagent.app.data.entity.Message
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val db: AppDatabase) {
    private val convDao = db.conversationDao()
    private val msgDao = db.messageDao()

    suspend fun createConversation(title: String): Conversation {
        val conv = Conversation(title = title)
        val id = convDao.insert(conv)
        return conv.copy(id = id)
    }

    suspend fun getConversations(): List<Conversation> = convDao.getAll()
    fun getConversationsFlow(): Flow<List<Conversation>> = convDao.getAllFlow()

    suspend fun getConversation(id: Long): Conversation? = convDao.getById(id)

    suspend fun deleteConversation(id: Long) = convDao.deleteById(id)

    suspend fun updateConversationTitle(id: Long, title: String) {
        val conv = convDao.getById(id) ?: return
        convDao.update(conv.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    suspend fun touchConversation(id: Long) {
        val conv = convDao.getById(id) ?: return
        convDao.update(conv.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun addMessage(convId: Long, role: String, content: String): Long {
        touchConversation(convId)
        return msgDao.insert(Message(conversationId = convId, role = role, content = content))
    }

    suspend fun getMessages(convId: Long): List<Message> = msgDao.getByConversation(convId)
    fun getMessagesFlow(convId: Long): Flow<List<Message>> = msgDao.getByConversationFlow(convId)

    suspend fun updateMessage(id: Long, content: String) {
        msgDao.updateContent(id, content)
    }
}
