package com.photosorter.app.index

import com.photosorter.app.ai.ClipEngine
import com.photosorter.app.data.db.IndexDb
import com.photosorter.app.data.folder.ImageItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Приводит индекс в соответствие с папкой: считает векторы для новых и изменённых картинок, убирает исчезнувшие. */
class Indexer(
    private val engine: ClipEngine,
    private val db: IndexDb,
) {
    data class Progress(val done: Int, val total: Int, val failed: Int)

    suspend fun sync(images: List<ImageItem>, onProgress: (Progress) -> Unit) =
        withContext(Dispatchers.Default) {
            val known = db.signatures()
            val currentKeys = images.mapTo(HashSet()) { it.uri.toString() }

            val stale = known.keys.filter { it !in currentKeys }
            if (stale.isNotEmpty()) db.delete(stale)

            val todo = images.filter {
                val sig = known[it.uri.toString()]
                sig == null || sig.modified != it.modified || sig.size != it.size
            }

            var done = 0
            var failed = 0
            onProgress(Progress(0, todo.size, 0))

            for (item in todo) {
                ensureActive()
                val embedding = try {
                    engine.encodeImage(item.uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }
                if (embedding != null) db.upsert(item, embedding) else failed++
                done++
                onProgress(Progress(done, todo.size, failed))
            }
        }
}
