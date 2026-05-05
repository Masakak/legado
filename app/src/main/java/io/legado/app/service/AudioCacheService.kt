package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.AudioChapterCache
import io.legado.app.model.AudioCache
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.FileUtils
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.io.File

class AudioCacheService : BaseService() {

    companion object {
        var isRun = false
            private set
    }

    private var downloadJob: Job? = null
    private var notificationContent = appCtx.getString(R.string.service_starting)
    private var totalCount = 0
    private var finishedCount = 0
    private var failedCount = 0
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.audio_cache))
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<AudioCacheService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        isRun = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> {
                val bookUrl = intent.getStringExtra("bookUrl") ?: return super.onStartCommand(intent, flags, startId)
                val start = intent.getIntExtra("start", 0)
                val end = intent.getIntExtra("end", -1)
                startDownload(bookUrl, start, end)
            }
            IntentAction.remove -> {
                val bookUrl = intent.getStringExtra("bookUrl") ?: return super.onStartCommand(intent, flags, startId)
                removeBook(bookUrl)
            }
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun removeBook(bookUrl: String) {
        execute {
            AudioCache.clearBookWithFiles(bookUrl)
        }
    }

    private fun startDownload(bookUrl: String, start: Int, end: Int) {
        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch(Dispatchers.IO) {
            val book = appDb.bookDao.getBook(bookUrl) ?: return@launch
            val source = appDb.bookSourceDao.getBookSource(book.origin) ?: return@launch
            val chapterCount = appDb.bookChapterDao.getChapterCount(bookUrl)
            if (chapterCount <= 0) return@launch
            val startIndex = start.coerceAtLeast(0)
            val endIndex = if (end < 0) chapterCount - 1 else minOf(end, chapterCount - 1)
            if (startIndex > endIndex) return@launch
            totalCount = endIndex - startIndex + 1
            finishedCount = 0
            failedCount = 0
            notificationContent = book.name
            upAudioCacheNotification()
            for (index in startIndex..endIndex) {
                if (!isActive) break
                val chapter = appDb.bookChapterDao.getChapter(bookUrl, index) ?: continue
                notificationContent = chapter.title
                upAudioCacheNotification()
                AudioCache.saveWaiting(bookUrl, index, chapter.title)
                appDb.audioChapterCacheDao.updateStatus(
                    bookUrl = bookUrl,
                    chapterIndex = index,
                    status = AudioChapterCache.STATUS_DOWNLOADING
                )
                var lastError: Throwable? = null
                var downloaded = false
                repeat(3) {
                    if (!isActive || downloaded) return@repeat
                    kotlin.runCatching {
                        val cacheDir = File(FileUtils.getCachePath(), "audio/${bookUrl.hashCode()}")
                        if (!cacheDir.exists()) cacheDir.mkdirs()
                        val cacheFile = File(cacheDir, "$index.mp3")
                        val analyzeUrl = AnalyzeUrl(
                            chapter.url,
                            source = source,
                            ruleData = book,
                            chapter = chapter,
                            coroutineContext = coroutineContext
                        )
                        val resolvedUrl = analyzeUrl.url
                        analyzeUrl.getInputStream().use { input ->
                            cacheFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        AudioCache.saveSuccess(
                            bookUrl = bookUrl,
                            chapterIndex = index,
                            chapterTitle = chapter.title,
                            audioUrl = resolvedUrl,
                            audioPath = cacheFile.absolutePath,
                            audioSize = cacheFile.length(),
                            duration = 0
                        )
                        downloaded = true
                        finishedCount++
                        upAudioCacheNotification()
                    }.onFailure {
                        lastError = it
                    }
                    if (!downloaded) kotlinx.coroutines.delay(800L * (it + 1))
                }
                if (!downloaded) {
                    AudioCache.saveFailed(
                        bookUrl,
                        index,
                        lastError?.localizedMessage ?: appCtx.getString(android.R.string.unknownName)
                    )
                    failedCount++
                    finishedCount++
                    upAudioCacheNotification()
                }
            }
            stopSelf()
        }
    }


    private fun upAudioCacheNotification() {
        val progress = if (totalCount > 0) finishedCount else 0
        notificationBuilder
            .setContentText(notificationContent)
            .setSubText("$finishedCount/$totalCount 失败:$failedCount")
            .setProgress(totalCount, progress, totalCount <= 0)
        notificationManager.notify(NotificationId.AudioCacheService, notificationBuilder.build())
    }

    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationContent)
        startForeground(NotificationId.AudioCacheService, notificationBuilder.build())
    }

}
