package io.legado.app.help.audio

import android.content.Context
import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.appDb
import io.legado.app.help.book.ContentProcessor
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import java.io.File
import java.io.InputStream
import java.io.IOException

object HttpTtsAudioCache {

    private const val audioFolderName = "httpTTS"
    private const val streamCacheFolderName = "httpTTS_cache"
    private const val indexFileName = "httpTTS_index.json"
    private const val fileLockSize = 64

    private val lock = Any()
    private val fileLocks = Array(fileLockSize) { Any() }
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
        synchronized(fileLock(fileName)) {
            val destFile = getSpeakFile(context, fileName)
            if (destFile.exists()) {
                inputStream.close()
                return@synchronized
            }
            destFile.parentFile?.mkdirs()
            val tempFile = File(destFile.parentFile, "${destFile.name}.${System.nanoTime()}.tmp")
            try {
                tempFile.outputStream().use { out ->
                    inputStream.use {
                        it.copyTo(out)
                    }
                }
                if (destFile.exists()) {
                    tempFile.delete()
                } else if (!tempFile.renameTo(destFile)) {
                    throw IOException("Rename ${tempFile.name} to ${destFile.name} failed")
                }
            } catch (e: Throwable) {
                tempFile.delete()
                throw e
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

    fun deleteBook(context: Context, bookUrl: String) = synchronized(lock) {
        deleteBookLocked(context, bookUrl, emptySet())
    }

    fun deleteBook(context: Context, book: Book) {
        val titlePrefixes = getBookTitlePrefixes(book)
        synchronized(lock) {
            deleteBookLocked(context, book.bookUrl, titlePrefixes)
        }
    }

    private fun deleteBookLocked(
        context: Context,
        bookUrl: String,
        titlePrefixes: Set<String>
    ) {
        val index = loadIndexLocked(context)
        val removing = index.filterValues {
            it.bookUrl == bookUrl
        }
        val prefixFiles = getPrefixFiles(context, titlePrefixes)
        if (removing.isEmpty() && prefixFiles.isEmpty()) return
        val removingKeys = removing.keys
        val removingFiles = removing.values.flatMap { it.fileNames }.toSet() + prefixFiles
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

    private fun fileLock(fileName: String): Any {
        val index = (fileName.hashCode() and Int.MAX_VALUE) % fileLockSize
        return fileLocks[index]
    }

    private fun getBookTitlePrefixes(book: Book): Set<String> {
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val titleReplaceRules = contentProcessor.getTitleReplaceRules()
        val useReplace = book.getUseReplaceRule()
        return appDb.bookChapterDao.getChapterList(book.bookUrl)
            .map {
                MD5Utils.md5Encode16(it.getDisplayTitle(titleReplaceRules, useReplace)) + "_"
            }
            .toSet()
    }

    private fun getPrefixFiles(context: Context, titlePrefixes: Set<String>): Set<String> {
        if (titlePrefixes.isEmpty()) return emptySet()
        return getAudioDir(context).listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == "mp3" }
            ?.map { it.name.removeSuffix(".mp3") }
            ?.filter { fileName -> titlePrefixes.any { fileName.startsWith(it) } }
            ?.toSet()
            .orEmpty()
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
