package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.AudioChapterCache

@Dao
interface AudioChapterCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg cache: AudioChapterCache)

    @Update
    suspend fun update(vararg cache: AudioChapterCache)

    @Query("select * from audioChapterCache where bookUrl = :bookUrl order by chapterIndex")
    suspend fun getByBook(bookUrl: String): List<AudioChapterCache>

    @Query("select * from audioChapterCache where bookUrl = :bookUrl and chapterIndex = :chapterIndex limit 1")
    suspend fun get(bookUrl: String, chapterIndex: Int): AudioChapterCache?

    @Query("delete from audioChapterCache where bookUrl = :bookUrl")
    suspend fun deleteByBook(bookUrl: String)

    @Query("delete from audioChapterCache where bookUrl = :bookUrl and chapterIndex = :chapterIndex")
    suspend fun delete(bookUrl: String, chapterIndex: Int)

    @Query("update audioChapterCache set status = :status, errorMsg = :errorMsg, updateTime = :updateTime where bookUrl = :bookUrl and chapterIndex = :chapterIndex")
    suspend fun updateStatus(bookUrl: String, chapterIndex: Int, status: Int, errorMsg: String = "", updateTime: Long = System.currentTimeMillis())

    @Query("select count(1) from audioChapterCache where bookUrl = :bookUrl and status = :status")
    suspend fun countByStatus(bookUrl: String, status: Int): Int
}
