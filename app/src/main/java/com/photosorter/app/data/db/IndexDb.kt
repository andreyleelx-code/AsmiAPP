package com.photosorter.app.data.db

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.photosorter.app.data.folder.ImageItem
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Локальный индекс: для каждой картинки хранится вектор CLIP. Имя базы содержит название модели,
 * поэтому при смене модели индекс просто строится заново.
 */
class IndexDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {

    data class Signature(val modified: Long, val size: Long)

    class Row(val item: ImageItem, val embedding: FloatArray)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE images (
                uri TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                modified INTEGER NOT NULL,
                size INTEGER NOT NULL,
                embedding BLOB NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun count(): Int = DatabaseUtils.queryNumEntries(readableDatabase, "images").toInt()

    /** uri -> (дата изменения, размер): по ним понимаем, какие файлы новые или изменились. */
    fun signatures(): Map<String, Signature> {
        val out = HashMap<String, Signature>()
        readableDatabase.rawQuery("SELECT uri, modified, size FROM images", null).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = Signature(c.getLong(1), c.getLong(2))
            }
        }
        return out
    }

    fun upsert(item: ImageItem, embedding: FloatArray) {
        val values = ContentValues().apply {
            put("uri", item.uri.toString())
            put("name", item.name)
            put("modified", item.modified)
            put("size", item.size)
            put("embedding", toBytes(embedding))
        }
        writableDatabase.insertWithOnConflict("images", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(uris: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (u in uris) db.delete("images", "uri = ?", arrayOf(u))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadAll(): List<Row> {
        val out = ArrayList<Row>()
        readableDatabase.rawQuery(
            "SELECT uri, name, modified, size, embedding FROM images", null
        ).use { c ->
            while (c.moveToNext()) {
                out += Row(
                    item = ImageItem(
                        uri = Uri.parse(c.getString(0)),
                        name = c.getString(1),
                        modified = c.getLong(2),
                        size = c.getLong(3),
                    ),
                    embedding = fromBytes(c.getBlob(4)),
                )
            }
        }
        return out
    }

    private fun toBytes(v: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(v)
        return buffer.array()
    }

    private fun fromBytes(blob: ByteArray): FloatArray {
        val floats = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }

    private companion object {
        const val DB_NAME = "index_clip_b32_int8.db"
    }
}
