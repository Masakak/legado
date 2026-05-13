package io.legado.app.help.audio

import android.content.Context
import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import java.io.File
import java.io.InputStream

object HttpTtsAudioCache {

    private const val audioFolderName = "httpTTS"
    private const val streamCacheFolderName = "httpTTS_cache"
    private const val indexFileName = "httpTTS_index.json"

    private val lock = Any()
    private val indexType by lazy {
        object : TypeToken<MutableMap<String, ChapterCache>>() {}.type
    }

    data class ChapterCache(
        val bookUrl: String,
        val chapterIndex: Int,
        val chapterTitle: String,
        val ttsUrl: String,
        val speechRate: Int,
        val readAloudByPage: Boolean,
        val contentHash: String,
        val fileNames: List<String>,
        val complete: Boolean,
        val updatedAt: Long
    )

    fun getAudioDir(context: Context): File {
        return File(context.filesDir, audioFolderName).apply {
            if (!exists()) mkdirs()
        }
    }

    fun getAudioFolderPath(context: Context): String {
        return getAudioDir(context).absolutePath + File.separator
    }

    fun getStreamCacheDir(context: Context): File {
        return File(context.filesDir, streamCacheFolderName).apply {
            if (!exists()) mkdirs()
        }
    }

    fun getSpeakFile(context: Context, fileName: String): File {
        return File(getAudioDir(context), "$fileName.mp3")
    }

    fun hasSpeakFile(context: Context, fileName: String): Boolean {
        return getSpeakFile(context, fileName).exists()
    }

    fun createSpeakFile(context: Context, fileName: String): File {
        return FileUtils.createFileIfNotExist(getSpeakFile(context, fileName).absolutePath)
    }

    fun writeSpeakFile(context: Context, fileName: String, inputStream: InputStream) {
        createSpeakFile(context, fileName).outputStream().use { out ->
            inputStream.use {
                it.copyTo(out)
            }
        }
    }

    fun speakFileName(
        ttsUrl: String?,
        speechRate: Int,
        content: String,
        chapterTitle: String?
    ): String {
        return MD5Utils.md5Encode16(chapterTitle ?: "") + "_" +
                MD5Utils.md5Encode16("${ttsUrl}-|-$speechRate-|-$content")
    }

    fun contentHash(contents: List<String>): String {
        return MD5Utils.md5Encode16(contents.joinToString("\n"))
    }

    fun updateChapterCache(
        context: Context,
        book: Book,
        chapter: BookChapter,
        chapterTitle: String,
        ttsUrl: String,
        speechRate: Int,
        readAloudByPage: Boolean,
        contentHash: String,
        fileNames: Collection<String>,
        complete: Boolean
    ) = synchronized(lock) {
        if (fileNames.isEmpty() && !complete) return@synchronized
        val index = loadIndexLocked(context)
        val key = chapterKey(
            book.bookUrl,
            chapter.index,
            ttsUrl,
            speechRate,
            readAloudByPage,
            contentHash
        )
        val old = index[key]
        val mergedFileNames = (old?.fileNames.orEmpty() + fileNames)
            .distinct()
            .filter { hasSpeakFile(context, it) }
        val oldCompleteStillValid = old?.complete == true
                && old.fileNames.all { hasSpeakFile(context, it) }
        index[key] = ChapterCache(
            bookUrl = book.bookUrl,
            chapterIndex = chapter.index,
            chapterTitle = chapterTitle,
            ttsUrl = ttsUrl,
            speechRate = speechRate,
            readAloudByPage = readAloudByPage,
            contentHash = contentHash,
            fileNames = mergedFileNames,
            complete = complete || oldCompleteStillValid,
            updatedAt = System.currentTimeMillis()
        )
        saveIndexLocked(context, index)
    }

    fun getCachedChapterIndexes(
        context: Context,
        bookUrl: String,
        ttsUrl: String?,
        speechRate: Int
    ): Set<Int> = synchronized(lock) {
        loadIndexLocked(context).values
            .asSequence()
            .filter {
                it.bookUrl == bookUrl
                        && it.ttsUrl == ttsUrl
                        && it.speechRate == speechRate
                        && it.complete
                        && it.fileNames.all { fileName -> hasSpeakFile(context, fileName) }
            }
            .map { it.chapterIndex }
            .toSet()
    }

    fun deleteChapter(context: Context, bookUrl: String, chapterIndex: Int) = synchronized(lock) {
        val index = loadIndexLocked(context)
        val removing = index.filterValues {
            it.bookUrl == bookUrl && it.chapterIndex == chapterIndex
        }
        if (removing.isEmpty()) return@synchronized
        val removingKeys = removing.keys
        val removingFiles = removing.values.flatMap { it.fileNames }.toSet()
        removingKeys.forEach { index.remove(it) }
        val usedFiles = index.values.flatMap { it.fileNames }.toSet()
        removingFiles
            .filterNot { usedFiles.contains(it) }
            .forEach { getSpeakFile(context, it).delete() }
        saveIndexLocked(context, index)
    }

    fun deleteAll(context: Context) = synchronized(lock) {
        FileUtils.delete(getAudioDir(context), true)
        FileUtils.delete(getStreamCacheDir(context), true)
        getIndexFile(context).delete()
    }

    fun getTotalSize(context: Context): Long {
        return listOf(getAudioDir(context), getStreamCacheDir(context))
            .sumOf { dir ->
                dir.walkTopDown()
                    .filter { it.isFile }
                    .sumOf { it.length() }
            }
    }

    fun cleanSilentSounds(context: Context) {
        FileUtils.listFiles(getAudioDir(context).absolutePath)
            .filter { it.length() == 2160L }
            .forEach { it.delete() }
    }

    private fun getIndexFile(context: Context): File {
        return File(context.filesDir, indexFileName)
    }

    private fun loadIndexLocked(context: Context): MutableMap<String, ChapterCache> {
        val file = getIndexFile(context)
        if (!file.exists()) return mutableMapOf()
        return runCatching {
            GSON.fromJson<MutableMap<String, ChapterCache>>(file.readText(), indexType)
        }.getOrNull() ?: mutableMapOf()
    }

    private fun saveIndexLocked(context: Context, index: Map<String, ChapterCache>) {
        val file = getIndexFile(context)
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile, "$indexFileName.tmp")
        tempFile.writeText(GSON.toJson(index))
        if (file.exists()) file.delete()
        tempFile.renameTo(file)
    }

    private fun chapterKey(
        bookUrl: String,
        chapterIndex: Int,
        ttsUrl: String,
        speechRate: Int,
        readAloudByPage: Boolean,
        contentHash: String
    ): String {
        return MD5Utils.md5Encode16(
            "$bookUrl-|-$chapterIndex-|-$ttsUrl-|-$speechRate-|-$readAloudByPage-|-$contentHash"
        )
    }

}
