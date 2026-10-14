package com.photosorter.app.data.folder

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** Запоминает выбранную папку между запусками приложения. */
class FolderPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("folder", Context.MODE_PRIVATE)

    fun get(): Uri? = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun save(uri: Uri) = prefs.edit { putString(KEY_TREE_URI, uri.toString()) }

    fun clear() = prefs.edit { remove(KEY_TREE_URI) }

    private companion object {
        const val KEY_TREE_URI = "tree_uri"
    }
}
