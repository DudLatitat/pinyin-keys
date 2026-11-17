package com.pinyinkeys.ime.saved

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

/**
 * One saved sentence. Kept deliberately simple so it's easy to export later.
 * `chunksJson` is the breakdown as JSON (a list of {hanzi, pinyin, role}).
 */
@Entity(tableName = "saved_sentences")
data class SavedSentence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val en: String,
    val hanzi: String,
    val pinyin: String,
    val chunksJson: String,
    /** Milliseconds since 1970 (System.currentTimeMillis()). */
    val savedAt: Long,
)

/** Room turns these annotated functions into SQL at build time. */
@Dao
interface SavedSentenceDao {
    @Insert
    suspend fun insert(s: SavedSentence)

    @Query("DELETE FROM saved_sentences WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) > 0 FROM saved_sentences WHERE en = :en AND hanzi = :hanzi")
    suspend fun exists(en: String, hanzi: String): Boolean

    /** A Flow re-emits the list whenever the table changes, so the screen updates by itself. */
    @Query("SELECT * FROM saved_sentences ORDER BY savedAt DESC")
    fun all(): Flow<List<SavedSentence>>
}

/** The database file lives in the app's private storage and is excluded from backups. */
@Database(entities = [SavedSentence::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun saved(): SavedSentenceDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** One shared instance: the keyboard and the app screen both use it. */
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pinyin_keys.db")
                .build().also { instance = it }
        }
    }
}
