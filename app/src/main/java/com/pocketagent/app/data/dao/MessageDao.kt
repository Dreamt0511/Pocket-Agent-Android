package com.pocketagent.app.data.dao

import androidx.room.*
import com.pocketagent.app.data.entity.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversation_id = :convId ORDER BY timestamp ASC")
    fun getByConversationFlow(convId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversation_id = :convId ORDER BY timestamp ASC")
    suspend fun getByConversation(convId: Long): List<Message>

    @Insert
    suspend fun insert(message: Message): Long

    @Update
    suspend fun update(message: Message)

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)
}
