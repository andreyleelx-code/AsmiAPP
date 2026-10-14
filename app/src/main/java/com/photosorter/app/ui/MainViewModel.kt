package com.photosorter.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photosorter.app.ai.ClipEngine
import com.photosorter.app.ai.ModelManager
import com.photosorter.app.ai.QueryTranslator
import com.photosorter.app.data.db.IndexDb
import com.photosorter.app.data.folder.FolderPrefs
import com.photosorter.app.data.folder.FolderScanner
import com.photosorter.app.data.folder.ImageItem
import com.photosorter.app.index.Indexer
import com.photosorter.app.search.SearchResult
import com.photosorter.app.search.Searcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val folder: Uri? = null,
    val images: List<ImageItem> = emptyList(),
    val scanning: Boolean = false,

    val modelReady: Boolean = false,
    /** Доля скачанной модели (0..1) или null, если сейчас не качаем. */
    val modelProgress: Float? = null,

    val indexing: Indexer.Progress? = null,
    val indexedCount: Int = 0,

    val query: String = "",
    val searching: Boolean = false,
    val results: SearchResult? = null,
    /** Как запрос выглядел после перевода на английский. */
    val translated: String? = null,
    val preferBeautiful: Boolean = true,

    val note: String? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = FolderPrefs(app)
    private val models = ModelManager(app)
    private val db = IndexDb(app)
    private val engine = ClipEngine(app, models)
    private val indexer = Indexer(engine, db)
    private val translator = QueryTranslator()

    private val _state = MutableStateFlow(
        UiState(folder = prefs.get(), modelReady = models.isReady())
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var syncJob: Job? = null
    private var downloadJob: Job? = null
    private var searchJob: Job? = null

    private var cachedSearcher: Searcher? = null

    init {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { db.count() }
            _state.update { it.copy(indexedCount = count) }
        }
        if (prefs.get() != null) refresh()
    }

    fun onFolderPicked(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            _state.update { it.copy(error = "Не удалось сохранить доступ к папке: ${e.message}") }
            return
        }
        prefs.save(uri)
        cachedSearcher = null
        _state.update {
            it.copy(folder = uri, images = emptyList(), results = null, translated = null, note = null, error = null)
        }
        refresh()
    }

    /** Заново читает папку и достраивает индекс для новых картинок. */
    fun refresh() {
        val folder = _state.value.folder ?: return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            _state.update { it.copy(scanning = true, error = null) }
            val images = try {
                FolderScanner.scan(getApplication(), folder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(scanning = false, error = "Ошибка чтения папки: ${e.message}") }
                return@launch
            }
            _state.update { it.copy(images = images, scanning = false) }
            if (models.isReady()) runIndex(images)
        }
    }

    private suspend fun runIndex(images: List<ImageItem>) {
        try {
            indexer.sync(images) { progress -> _state.update { it.copy(indexing = progress) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update { it.copy(error = "Ошибка индексации: ${e.message}") }
        } finally {
            cachedSearcher = null
            _state.update { it.copy(indexing = null, indexedCount = db.count()) }
        }
    }

    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _state.update { it.copy(modelProgress = 0f, error = null) }
            try {
                models.download { p -> _state.update { s -> s.copy(modelProgress = p) } }
                _state.update { it.copy(modelReady = true, modelProgress = null) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(modelProgress = null, error = "Не удалось скачать модель: ${e.message}")
                }
            }
        }
    }

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text) }
        if (text.isBlank()) clearSearch()
    }

    fun setPreferBeautiful(value: Boolean) {
        _state.update { it.copy(preferBeautiful = value) }
        val q = _state.value.query
        if (q.isNotBlank() && _state.value.results != null) search(q)
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(results = null, translated = null, note = null, searching = false) }
    }

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            clearSearch()
            return
        }
        if (!models.isReady()) {
            _state.update { it.copy(error = "Сначала скачай модель") }
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null, note = null) }
            try {
                val translated = translator.toEnglish(q)
                // Короткие запросы CLIP лучше понимает в виде фразы «фото чего-то».
                val prompt = if (translated.text.trim().split(" ").size <= 4) {
                    "a photo of ${translated.text}"
                } else {
                    translated.text
                }
                val weight = if (_state.value.preferBeautiful) 0.6f else 0f
                val result = withContext(Dispatchers.Default) {
                    val embedding = engine.encodeText(prompt)
                    currentSearcher().search(embedding, weight)
                }
                _state.update {
                    it.copy(
                        searching = false,
                        results = result,
                        translated = translated.text.takeIf { t -> t != q },
                        note = translated.note,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(searching = false, error = "Ошибка поиска: ${e.message}") }
            }
        }
    }

    @Synchronized
    private fun currentSearcher(): Searcher {
        cachedSearcher?.let { return it }
        val (positive, negative) = engine.beautyVectors
        return Searcher(db.loadAll(), positive, negative).also { cachedSearcher = it }
    }

    override fun onCleared() {
        translator.close()
        engine.close()
        db.close()
    }
}
