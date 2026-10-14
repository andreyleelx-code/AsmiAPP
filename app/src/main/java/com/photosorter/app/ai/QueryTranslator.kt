package com.photosorter.app.ai

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

data class Translated(val text: String, val note: String? = null)

/**
 * Переводит запрос на английский, потому что CLIP лучше всего понимает английский.
 * Перевод идёт на устройстве (ML Kit); языковая модель (~30 МБ) скачивается один раз.
 */
class QueryTranslator {

    private val translators = HashMap<String, Translator>()

    suspend fun toEnglish(text: String): Translated {
        val source = when {
            text.any { it in 'Ѐ'..'ӿ' } -> TranslateLanguage.RUSSIAN
            text.any { it in '가'..'힯' || it in 'ᄀ'..'ᇿ' } -> TranslateLanguage.KOREAN
            else -> return Translated(text)
        }
        return try {
            val translator = translators.getOrPut(source) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(TranslateLanguage.ENGLISH)
                        .build()
                )
            }
            translator.downloadModelIfNeeded().await()
            Translated(translator.translate(text).await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Translated(
                text,
                "Не удалось перевести запрос. При первом поиске на русском нужен интернет, чтобы скачать языковой пакет.",
            )
        }
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}
