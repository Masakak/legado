package io.legado.app.model

import io.legado.app.data.appDb
import io.legado.app.data.entities.AudioChapterCache

object AudioCache {

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
}
