package com.photosorter.app.ai

import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * Токенизатор CLIP (byte-level BPE). Порт оригинального simple_tokenizer от OpenAI;
 * результат совпадает с эталонной реализацией tokenizers на тестовых фразах.
 */
class ClipTokenizer(vocabJson: File, mergesTxt: File) {

    private val vocab: Map<String, Int>
    private val ranks: Map<String, Int>
    private val byteToUnicode: Array<String> = buildByteMap()
    private val cache = HashMap<String, List<String>>()

    init {
        val obj = JSONObject(vocabJson.readText(Charsets.UTF_8))
        val v = HashMap<String, Int>(obj.length() * 2)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            v[k] = obj.getInt(k)
        }
        vocab = v

        val r = HashMap<String, Int>()
        var rank = 0
        mergesTxt.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
            if (index == 0 || line.isBlank()) return@forEachIndexed // первая строка: "#version"
            r[line.trim()] = rank++
        }
        ranks = r
    }

    /** Возвращает ровно [maxLen] id: <start> текст <end> и добивку <end>, как делает эталонный токенизатор. */
    fun encode(text: String, maxLen: Int = 77): LongArray {
        val clean = text.replace(WHITESPACE, " ").trim().lowercase()
        val ids = ArrayList<Int>()
        ids.add(BOS)

        val matcher = PATTERN.matcher(clean)
        while (matcher.find()) {
            val bytes = matcher.group().toByteArray(Charsets.UTF_8)
            val mapped = StringBuilder()
            for (b in bytes) mapped.append(byteToUnicode[b.toInt() and 0xFF])
            for (piece in bpe(mapped.toString())) {
                val id = vocab[piece] ?: continue
                ids.add(id)
            }
        }
        ids.add(EOS)

        val out = LongArray(maxLen) { EOS.toLong() }
        val n = minOf(ids.size, maxLen)
        for (i in 0 until n) out[i] = ids[i].toLong()
        if (ids.size > maxLen) out[maxLen - 1] = EOS.toLong()
        return out
    }

    private fun bpe(token: String): List<String> = cache.getOrPut(token) {
        var word = ArrayList<String>(token.length)
        for (i in 0 until token.length - 1) word.add(token[i].toString())
        word.add(token.last().toString() + "</w>")

        while (word.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIndex = -1
            for (i in 0 until word.size - 1) {
                val rank = ranks[word[i] + " " + word[i + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = i
                }
            }
            if (bestIndex < 0) break

            val first = word[bestIndex]
            val second = word[bestIndex + 1]
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    merged.add(first + second)
                    i += 2
                } else {
                    merged.add(word[i])
                    i++
                }
            }
            word = merged
        }
        word
    }

    private fun buildByteMap(): Array<String> {
        val bs = ArrayList<Int>()
        bs.addAll('!'.code..'~'.code)
        bs.addAll('¡'.code..'¬'.code)
        bs.addAll('®'.code..'ÿ'.code)
        val cs = ArrayList<Int>(bs)
        var n = 0
        for (b in 0 until 256) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        val out = Array(256) { "" }
        for (i in bs.indices) out[bs[i]] = cs[i].toChar().toString()
        return out
    }

    private companion object {
        const val BOS = 49406
        const val EOS = 49407
        val WHITESPACE = Regex("\\s+")
        val PATTERN: Pattern = Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|\\p{L}+|\\p{N}|[^\\s\\p{L}\\p{N}]+",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
        )
    }
}
