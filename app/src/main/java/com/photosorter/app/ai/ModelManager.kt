package com.photosorter.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Файлы модели CLIP (ViT-B/32, квантованная int8). Версия закреплена, чтобы апстрим не сломал приложение. */
object ModelFiles {
    private const val REVISION = "d15189d7028b43f1d3e65039190477f6af591c2a"
    const val BASE_URL = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/$REVISION/"

    class Spec(val remote: String, val local: String, val bytes: Long)

    val ALL = listOf(
        Spec("onnx/vision_model_quantized.onnx", "vision.onnx", 89_117_001L),
        Spec("onnx/text_model_quantized.onnx", "text.onnx", 64_504_507L),
        Spec("vocab.json", "vocab.json", 862_328L),
        Spec("merges.txt", "merges.txt", 524_619L),
    )
}

/** Хранит модель в приватной папке приложения и скачивает её на телефон один раз. */
class ModelManager(context: Context) {
    private val dir = File(context.filesDir, "models/clip")

    fun file(name: String): File = File(dir, name)

    fun isReady(): Boolean = ModelFiles.ALL.all {
        val f = file(it.local)
        f.exists() && f.length() == it.bytes
    }

    /** [onProgress] получает долю от 0 до 1 (не чаще, чем раз в полпроцента). */
    suspend fun download(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val total = ModelFiles.ALL.sumOf { it.bytes }
        var done = 0L
        var lastEmitted = -1f

        fun emit() {
            val fraction = done.toFloat() / total
            if (fraction - lastEmitted >= 0.005f || fraction >= 1f) {
                lastEmitted = fraction
                onProgress(fraction)
            }
        }

        for (spec in ModelFiles.ALL) {
            val target = file(spec.local)
            if (target.exists() && target.length() == spec.bytes) {
                done += spec.bytes
                emit()
                continue
            }

            val tmp = File(dir, spec.local + ".part")
            val conn = (URL(ModelFiles.BASE_URL + spec.remote).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) {
                    error("сервер ответил ${conn.responseCode} для ${spec.remote}")
                }
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            emit()
                            ensureActive()
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (tmp.length() != spec.bytes) {
                tmp.delete()
                error("файл ${spec.local} скачался не полностью, попробуй ещё раз")
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) error("не удалось сохранить ${spec.local}")
        }
        onProgress(1f)
    }
}
