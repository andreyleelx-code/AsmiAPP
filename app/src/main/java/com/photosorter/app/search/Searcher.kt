package com.photosorter.app.search

import com.photosorter.app.data.db.IndexDb
import com.photosorter.app.data.folder.ImageItem
import kotlin.math.sqrt

data class SearchResult(
    /** Самые близкие к запросу. */
    val exact: List<ImageItem>,
    /** «Возможно подойдут»: похожи на найденные, но в первую группу не попали. */
    val maybe: List<ImageItem>,
)

/** Поиск по готовому индексу: косинусная близость к запросу плюс (по желанию) бонус за красоту. */
class Searcher(
    private val rows: List<IndexDb.Row>,
    beautyPositive: FloatArray,
    beautyNegative: FloatArray,
) {
    val size: Int get() = rows.size

    /** Насколько снимок ближе к «красивому», чем к «некрасивому». */
    private val beauty = FloatArray(rows.size) {
        dot(rows[it].embedding, beautyPositive) - dot(rows[it].embedding, beautyNegative)
    }

    fun search(
        query: FloatArray,
        beautyWeight: Float,
        exactCount: Int = 30,
        maybeCount: Int = 20,
    ): SearchResult {
        if (rows.isEmpty()) return SearchResult(emptyList(), emptyList())

        val similarity = FloatArray(rows.size) { dot(rows[it].embedding, query) }
        val zSimilarity = zScores(similarity)
        val zBeauty = zScores(beauty)
        val score = FloatArray(rows.size) { zSimilarity[it] + beautyWeight * zBeauty[it] }

        val ranked = rows.indices.sortedByDescending { score[it] }
        val exactIndices = ranked.take(exactCount)

        // «Свои варианты»: берём центр лучших результатов и ищем среди остальных самые близкие к нему.
        val centroid = FloatArray(query.size)
        for (i in exactIndices.take(5)) {
            val e = rows[i].embedding
            for (k in centroid.indices) centroid[k] += e[k]
        }
        normalize(centroid)

        val exactSet = exactIndices.toHashSet()
        val centroidSimilarity = FloatArray(rows.size) { dot(rows[it].embedding, centroid) }
        val maybeIndices = rows.indices
            .filter { it !in exactSet }
            .sortedByDescending { centroidSimilarity[it] }
            .take(maybeCount)

        return SearchResult(
            exact = exactIndices.map { rows[it].item },
            maybe = maybeIndices.map { rows[it].item },
        )
    }

    private fun zScores(values: FloatArray): FloatArray {
        var mean = 0f
        for (v in values) mean += v
        mean /= values.size
        var variance = 0f
        for (v in values) variance += (v - mean) * (v - mean)
        val std = sqrt(variance / values.size)
        return FloatArray(values.size) { if (std < 1e-6f) 0f else (values[it] - mean) / std }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun normalize(v: FloatArray) {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s)
        if (n > 0f) for (i in v.indices) v[i] /= n
    }
}
