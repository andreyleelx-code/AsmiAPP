package com.photosorter.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.photosorter.app.data.folder.ImageItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Пока идёт скачивание модели или анализ картинок, экран не гасим:
    // телефон иначе может усыпить приложение и остановить работу.
    val view = LocalView.current
    val busy = state.indexing != null || state.modelProgress != null
    SideEffect { view.keepScreenOn = busy }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.onFolderPicked(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Сортировщик") },
                actions = {
                    if (state.folder != null) {
                        TextButton(onClick = { vm.refresh() }) { Text("Обновить") }
                        TextButton(onClick = { pickFolder.launch(null) }) { Text("Папка") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.folder == null) {
                EmptyState(onPick = { pickFolder.launch(null) })
            } else {
                StatusPanel(state, onDownload = vm::downloadModel)
                SearchPanel(state, vm)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ImageGrid(state)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Выбери папку с картинками, которые нужно разбирать",
            style = MaterialTheme.typography.titleMedium,
        )
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Text("Выбрать папку")
        }
    }
}

@Composable
private fun StatusPanel(state: UiState, onDownload: () -> Unit) {
    val downloadProgress = state.modelProgress
    val indexing = state.indexing

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        when {
            downloadProgress != null -> {
                Text("Скачиваю модель: ${(downloadProgress * 100).toInt()}%")
                LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
            }
            !state.modelReady -> {
                Text("Для поиска нужна модель (около 150 МБ, скачивается один раз). Лучше по Wi-Fi.")
                Button(onClick = onDownload, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Скачать модель")
                }
            }
            indexing != null -> {
                Text("Анализирую картинки: ${indexing.done} из ${indexing.total}")
                val fraction = if (indexing.total == 0) 1f else indexing.done.toFloat() / indexing.total
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                if (indexing.failed > 0) {
                    Text("Не удалось прочитать: ${indexing.failed}", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.scanning -> Text("Читаю папку…")
            else -> Text(
                "Картинок в папке: ${state.images.size}, проанализировано: ${state.indexedCount}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val error = state.error
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SearchPanel(state: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Например: красивые пейзажи") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(state.query) }),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.search(state.query) },
                enabled = state.modelReady && !state.searching,
            ) { Text("Найти") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Switch(checked = state.preferBeautiful, onCheckedChange = vm::setPreferBeautiful)
            Spacer(Modifier.width(8.dp))
            Text("Красивые выше")
        }
        val translated = state.translated
        if (translated != null) {
            Text("Ищу как: $translated", style = MaterialTheme.typography.bodySmall)
        }
        val note = state.note
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.searching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable
private fun ImageGrid(state: UiState) {
    val results = state.results
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (results == null) {
            items(state.images, key = { "all:" + it.uri }) { Thumbnail(it) }
        } else {
            item(key = "title-exact", span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle(
                    if (results.exact.isEmpty()) "Ничего не найдено. Проанализировано: ${state.indexedCount}"
                    else "Подходит (${results.exact.size})"
                )
            }
            items(results.exact, key = { "exact:" + it.uri }) { Thumbnail(it) }

            if (results.maybe.isNotEmpty()) {
                item(key = "title-maybe", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle("Возможно подойдёт (${results.maybe.size})")
                }
                items(results.maybe, key = { "maybe:" + it.uri }) { Thumbnail(it) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun Thumbnail(image: ImageItem) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(image.uri)
            .size(256)
            .crossfade(true)
            .build(),
        contentDescription = image.name,
        contentScale = ContentScale.Crop,
        modifier = Modifier.aspectRatio(1f),
    )
}
