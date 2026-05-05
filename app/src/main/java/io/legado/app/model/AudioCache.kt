package io.legado.app.model

import android.content.Context
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.AudioChapterCache
import io.legado.app.service.AudioCacheService
import io.legado.app.utils.FileUtils
import io.legado.app.utils.startService

object AudioCache {

    fun start(context: Context, bookUrl: String, start: Int = 0, end: Int = -1) {
        context.startService<AudioCacheService> {
            action = IntentAction.start
            putExtra("bookUrl", bookUrl)
            putExtra("start", start)
            putExtra("end", end)
        }
    }

    fun remove(context: Context, bookUrl: String) {
        context.startService<AudioCacheService> {
            action = IntentAction.remove
            putExtra("bookUrl", bookUrl)
        }
    }


    suspend fun getChapterCache(bookUrl: String, chapterIndex: Int): AudioChapterCache? {
        return appDb.audioChapterCacheDao.get(bookUrl, chapterIndex)
    }

    suspend fun getCachedPath(bookUrl: String, chapterIndex: Int): String? {
        val cache = getChapterCache(bookUrl, chapterIndex) ?: return null
        if (cache.status != AudioChapterCache.STATUS_SUCCESS) return null
        if (cache.audioPath.isBlank()) return null
        return cache.audioPath
    }

    suspend fun saveWaiting(bookUrl: String, chapterIndex: Int, chapterTitle: String) {
        appDb.audioChapterCacheDao.insert(
            AudioChapterCache(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                status = AudioChapterCache.STATUS_WAITING
            )
        )
    }

    suspend fun saveSuccess(
        bookUrl: String,
        chapterIndex: Int,
        chapterTitle: String,
        audioUrl: String,
        audioPath: String,
        audioSize: Long,
        duration: Long
    ) {
        val now = System.currentTimeMillis()
        appDb.audioChapterCacheDao.insert(
            AudioChapterCache(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                audioUrl = audioUrl,
                audioPath = audioPath,
                audioSize = audioSize,
                duration = duration,
                status = AudioChapterCache.STATUS_SUCCESS,
                updateTime = now,
                createTime = now
            )
        )
    }

    suspend fun saveFailed(bookUrl: String, chapterIndex: Int, errorMsg: String) {
        appDb.audioChapterCacheDao.updateStatus(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            status = AudioChapterCache.STATUS_FAILED,
            errorMsg = errorMsg
        )
    }

    suspend fun clearBook(bookUrl: String) {
        appDb.audioChapterCacheDao.deleteByBook(bookUrl)
    }

    suspend fun clearBookWithFiles(bookUrl: String) {
        clearBook(bookUrl)
        val dir = java.io.File(FileUtils.getCachePath(), "audio/${bookUrl.hashCode()}")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}
