package com.photosorter.app.data.folder

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Рекурсивно обходит папку, выбранную через Storage Access Framework, и собирает картинки. */
object FolderScanner {

    suspend fun scan(context: Context, treeUri: Uri): List<ImageItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ImageItem>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        walk(context, treeUri, rootId, result)
        result.sortedByDescending { it.modified }
    }

    private fun walk(context: Context, treeUri: Uri, parentId: String, out: MutableList<ImageItem>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: ""
                val mime = c.getString(2) ?: ""
                val modified = if (c.isNull(3)) 0L else c.getLong(3)
                val size = if (c.isNull(4)) 0L else c.getLong(4)

                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> walk(context, treeUri, id, out)
                    mime.startsWith("image/") -> out += ImageItem(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        name = name,
                        modified = modified,
                        size = size,
                    )
                }
            }
        }
    }
}
