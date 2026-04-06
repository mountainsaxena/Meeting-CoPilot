package com.example.meetingcopilot

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "meetings")
data class MeetingSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val title: String,
    val transcript: String,
    val summary: String,
    val actionItems: String,
    val classification: String = "Unknown",
    val latestDecision: String = "",
    val decisionConfidence: String = "",
    val decisionMissing: String = "",
    val whatChanged: String = "",
    val nudges: String = "",
    val generatedQuestions: String = ""
)

@Dao
interface MeetingDao {
    @Query("SELECT * FROM meetings ORDER BY timestamp DESC")
    fun getAllMeetings(): Flow<List<MeetingSession>>

    @Insert
    suspend fun insertMeeting(meeting: MeetingSession)

    @Query("DELETE FROM meetings WHERE id = :id")
    suspend fun deleteMeeting(id: Int)
}

@Database(entities = [MeetingSession::class], version = 4) // Bumped version to 4 for questions
abstract class MeetingDatabase : RoomDatabase() {
    abstract fun meetingDao(): MeetingDao

    companion object {
        @Volatile
        private var INSTANCE: MeetingDatabase? = null

        fun getDatabase(context: Context): MeetingDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MeetingDatabase::class.java,
                    "meeting_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
