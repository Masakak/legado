package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "audioChapterCache",
    primaryKeys = ["bookUrl", "chapterIndex"],
    indices = [
        Index(value = ["bookUrl"]),
        Index(value = ["status"]),
        Index(value = ["updateTime"])
    ]
)
data class AudioChapterCache(
    val bookUrl: String,
    val chapterIndex: Int,
    val chapterTitle: String = "",
    val audioUrl: String = "",
    val audioPath: String = "",
    val duration: Long = 0,
    val audioSize: Long = 0,
    val status: Int = 0,
    val errorMsg: String = "",
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_WAITING = 0
        const val STATUS_DOWNLOADING = 1
        const val STATUS_SUCCESS = 2
        const val STATUS_FAILED = 3
    }
}
