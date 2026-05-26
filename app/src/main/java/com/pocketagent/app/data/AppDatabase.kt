package com.pocketagent.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.pocketagent.app.data.entity.Conversation
import com.pocketagent.app.data.entity.Message
import com.pocketagent.app.data.dao.ConversationDao
import com.pocketagent.app.data.dao.MessageDao

@Database(entities = [Conversation::class, Message::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pocket_agent.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
