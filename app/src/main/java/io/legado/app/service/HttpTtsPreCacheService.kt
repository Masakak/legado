package io.legado.app.service

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.audio.HttpTtsAudioCache
import io.legado.app.help.audio.HttpTtsAudioDownloader
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.systemservices.notificationManager

/**
 * HTTP TTS 章节音频预缓存服务
 */
class HttpTtsPreCacheService : BaseService() {

    companion object {

        private const val extraBookUrl = "bookUrl"
        private const val extraChapters = "chapters"

        fun start(context: Context, bookUrl: String, chapterIndexes: List<Int>) {
            if (chapterIndexes.isEmpty()) return
            val intent = Intent(context, HttpTtsPreCacheService::class.java).apply {
                action = IntentAction.start
                putExtra(extraBookUrl, bookUrl)
                putIntegerArrayListExtra(extraChapters, ArrayList(chapterIndexes.distinct()))
            }
            context.startForegroundServiceCompat(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, HttpTtsPreCacheService::class.java).apply {
                action = IntentAction.stop
            })
        }

    }

    private data class ChapterTask(val bookUrl: String, val chapterIndex: Int)

    private val pendingTasks = linkedSetOf<ChapterTask>()
    private var cacheJob: Job? = null
    private var notificationContent = appCtx.getString(R.string.service_starting)
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle("HTTP TTS 音频缓存")
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<HttpTtsPreCacheService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.stop -> stopSelf()
            else -> {
                val bookUrl = intent?.getStringExtra(extraBookUrl)
                val chapters = intent?.getIntegerArrayListExtra(extraChapters)
                if (!bookUrl.isNullOrBlank() && !chapters.isNullOrEmpty()) {
                    addTasks(bookUrl, chapters)
                    startCacheJobIfNeed()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        cacheJob?.cancel()
        cacheJob = null
        pendingTasks.clear()
        notificationManager.cancel(NotificationId.HttpTtsPreCacheService)
        super.onDestroy()
    }

    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationContent)
        startForeground(NotificationId.HttpTtsPreCacheService, notificationBuilder.build())
    }

    @Synchronized
    private fun addTasks(bookUrl: String, chapterIndexes: List<Int>) {
        chapterIndexes.forEach { index ->
            pendingTasks.add(ChapterTask(bookUrl, index))
        }
        notificationContent = "等待缓存 ${pendingTasks.size} 章"
        upNotification()
    }

    @Synchronized
    private fun pollTask(): ChapterTask? {
        val task = pendingTasks.firstOrNull() ?: return null
        pendingTasks.remove(task)
        return task
    }

    @Synchronized
    private fun queueSize(): Int {
        return pendingTasks.size
    }

    private fun startCacheJobIfNeed() {
        if (cacheJob?.isActive == true) return
        cacheJob = lifecycleScope.launch(IO) {
            while (isActive) {
                val task = pollTask() ?: break
                cacheChapter(task)
            }
            withContext(IO) {
                cacheJob = null
                if (queueSize() == 0) {
                    stopSelf()
                } else {
                    startCacheJobIfNeed()
                }
            }
        }
    }

    private suspend fun cacheChapter(task: ChapterTask) {
        val book = appDb.bookDao.getBook(task.bookUrl) ?: return
        val bookSource = book.getBookSource()
        val chapter = appDb.bookChapterDao.getChapter(task.bookUrl, task.chapterIndex) ?: return
        if (chapter.isVolume) return
        val httpTts = getHttpTts(book) ?: run {
            appCtx.toastOnUi("当前朗读引擎不是 HTTP TTS")
            stopSelf()
            return
        }
        val speechRate = AppConfig.speechRatePlay + 5
        val readAloudByPage = getPrefBoolean(PreferKey.readAloudByPage)
        notificationContent = "缓存中：${chapter.title}"
        upNotification()
        val content = BookHelp.getContent(book, chapter)
            ?: if (bookSource != null) {
                WebBook.getContentAwait(bookSource, book, chapter)
            } else {
                null
            }
            ?: return
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val displayTitle = chapter.getDisplayTitle(
            contentProcessor.getTitleReplaceRules(),
            book.getUseReplaceRule()
        )
        val bookContent = contentProcessor.getContent(book, chapter, content, includeTitle = false)
        val textChapter = coroutineScope {
            val textChapter = ChapterProvider.getTextChapterAsync(
                this,
                book,
                chapter,
                displayTitle,
                bookContent,
                book.simulatedTotalChapterNum()
            )
            for (ignored in textChapter.layoutChannel) {
                currentCoroutineContext().ensureActive()
            }
            textChapter
        }
        val readAloudContents = textChapter.getNeedReadAloud(0, readAloudByPage, 0)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .toList()
        downloadChapterAudios(
            book,
            chapter,
            displayTitle,
            httpTts,
            speechRate,
            readAloudByPage,
            readAloudContents
        )
    }

    private suspend fun downloadChapterAudios(
        book: Book,
        chapter: BookChapter,
        displayTitle: String,
        httpTts: HttpTTS,
        speechRate: Int,
        readAloudByPage: Boolean,
        readAloudContents: List<String>
    ) {
        val errorCounter = HttpTtsAudioDownloader.ErrorCounter()
        val fileNames = linkedSetOf<String>()
        val speakableCount = readAloudContents.count {
            it.replace(AppPattern.notReadAloudRegex, "").isNotEmpty()
        }
        readAloudContents.forEachIndexed { index, content ->
            currentCoroutineContext().ensureActive()
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) return@forEachIndexed
            val fileName = HttpTtsAudioCache.speakFileName(
                httpTts.url,
                speechRate,
                content,
                displayTitle
            )
            if (!HttpTtsAudioCache.hasSpeakFile(this, fileName)) {
                notificationContent =
                    "缓存中 ${index + 1}/${readAloudContents.size}：${chapter.title}"
                upNotification()
                val stream = HttpTtsAudioDownloader.getSpeakStream(
                    httpTts,
                    speakText,
                    speechRate,
                    errorCounter
                )
                if (stream != null) {
                    HttpTtsAudioCache.writeSpeakFile(this, fileName, stream)
                }
            }
            if (HttpTtsAudioCache.hasSpeakFile(this, fileName)) {
                fileNames.add(fileName)
            }
        }
        HttpTtsAudioCache.updateChapterCache(
            this,
            book,
            chapter,
            displayTitle,
            httpTts.url,
            speechRate,
            readAloudByPage,
            HttpTtsAudioCache.contentHash(readAloudContents),
            fileNames,
            complete = fileNames.size == speakableCount
        )
        AppLog.putDebug("HTTP TTS缓存完成 ${book.name}-${chapter.title}")
        if (fileNames.size == speakableCount) {
            postEvent(EventBus.HTTP_TTS_CACHE, Pair(book.bookUrl, chapter.index))
        }
    }

    private fun getHttpTts(book: Book): HttpTTS? {
        val engine = book.getTtsEngine() ?: AppConfig.ttsEngine
        return engine?.toLongOrNull()?.let {
            appDb.httpTTSDao.get(it)
        }
    }

    private fun upNotification() {
        notificationBuilder.setContentText(notificationContent)
        notificationManager.notify(
            NotificationId.HttpTtsPreCacheService,
            notificationBuilder.build()
        )
    }

}
