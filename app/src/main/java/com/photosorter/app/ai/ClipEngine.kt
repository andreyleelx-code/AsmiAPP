package com.photosorter.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/** CLIP на устройстве: переводит картинку и текст в векторы одного пространства. */
class ClipEngine(
    private val context: Context,
    private val models: ModelManager,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var visionSession: OrtSession? = null
    private var textSession: OrtSession? = null
    private var tokenizer: ClipTokenizer? = null

    /** Вектор «красивого» и «некрасивого» снимка для грубой оценки эстетики. Считается один раз. */
    val beautyVectors: Pair<FloatArray, FloatArray> by lazy {
        val positive = averaged(
            listOf(
                "a beautiful, stunning, high quality professional photograph",
                "an aesthetically pleasing photo with great composition and lighting",
            )
        )
        val negative = averaged(
            listOf(
                "an ugly, boring, low quality photo",
                "a blurry, dark, badly composed snapshot",
            )
        )
        positive to negative
    }

    @Synchronized
    private fun ensureLoaded() {
        if (visionSession != null && textSession != null && tokenizer != null) return
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        visionSession = env.createSession(models.file("vision.onnx").absolutePath, options)
        textSession = env.createSession(models.file("text.onnx").absolutePath, options)
        tokenizer = ClipTokenizer(models.file("vocab.json"), models.file("merges.txt"))
    }

    /** Вектор картинки (длина 512, нормализован) или null, если файл не читается. */
    fun encodeImage(uri: Uri): FloatArray? {
        val data = ImagePrep.load(context, uri) ?: return null
        ensureLoaded()
        val shape = longArrayOf(1, 3, ImagePrep.SIZE.toLong(), ImagePrep.SIZE.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            visionSession!!.run(mapOf("pixel_values" to input)).use { result ->
                return normalized(readVector(result[0] as OnnxTensor))
            }
        }
    }

    /** Вектор текста (длина 512, нормализован). Текст должен быть на английском. */
    fun encodeText(query: String): FloatArray {
        ensureLoaded()
        val ids = tokenizer!!.encode(query)
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
            textSession!!.run(mapOf("input_ids" to input)).use { result ->
                return normalized(readVector(result[0] as OnnxTensor))
            }
        }
    }

    private fun averaged(prompts: List<String>): FloatArray {
        val sum = FloatArray(EMBEDDING_SIZE)
        for (p in prompts) {
            val e = encodeText(p)
            for (i in sum.indices) sum[i] += e[i]
        }
        return normalized(sum)
    }

    private fun readVector(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    private fun normalized(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    @Synchronized
    override fun close() {
        visionSession?.close()
        textSession?.close()
        visionSession = null
        textSession = null
        tokenizer = null
    }

    companion object {
        const val EMBEDDING_SIZE = 512
    }
}
