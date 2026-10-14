package com.photosorter.app.data.folder

import android.net.Uri

/** Одна картинка из выбранной папки. На следующих шагах сюда добавятся теги, оценка и embedding. */
data class ImageItem(
    val uri: Uri,
    val name: String,
    val modified: Long,
    val size: Long,
)
