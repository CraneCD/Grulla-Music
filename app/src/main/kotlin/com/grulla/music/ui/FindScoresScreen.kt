package com.grulla.music.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grulla.music.R
import com.grulla.music.catalog.CatalogRepository
import com.grulla.music.catalog.CatalogState
import com.grulla.music.catalog.DownloadState
import com.grulla.music.core.catalog.Catalog
import com.grulla.music.core.catalog.CatalogFilter
import com.grulla.music.core.catalog.CatalogScore
import kotlinx.coroutines.launch

/** Search the online catalogue; tapping a score downloads it into the library and opens it. */
@Composable
fun FindScoresScreen(repository: CatalogRepository, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    val downloads by repository.downloads.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var instrument by rememberSaveable { mutableStateOf<String?>(null) }
    var difficulty by rememberSaveable { mutableStateOf<Int?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    LaunchedEffect(repository) { repository.load() }
    FindScoresContent(
        state = state,
        query = query,
        filter = CatalogFilter(instrument = instrument, difficulty = difficulty),
        downloads = downloads,
        isDownloaded = repository::isDownloaded,
        onQuery = { query = it },
        onFilter = {
            instrument = it.instrument
            difficulty = it.difficulty
        },
        onChoose = { score ->
            repository.download(score) { uri ->
                if (uri != null) {
                    onOpen(uri)
                } else {
                    scope.launch { snackbar.showSnackbar(resources.getString(R.string.download_failed)) }
                }
            }
        },
        onRetry = { repository.load(force = true) },
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    )
}

@Composable
fun FindScoresContent(
    state: CatalogState,
    query: String,
    filter: CatalogFilter,
    downloads: Map<String, DownloadState>,
    isDownloaded: (String) -> Boolean,
    onQuery: (String) -> Unit,
    onFilter: (CatalogFilter) -> Unit,
    onChoose: (CatalogScore) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
) {
    val c = Grulla.colors
    Scaffold(
        containerColor = c.paper,
        snackbarHost = snackbarHost,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Text(
                    stringResource(R.string.find_scores),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
    ) { padding ->
        when (state) {
            CatalogState.Loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.indigo)
            }
            CatalogState.Offline -> Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.catalog_offline), style = MaterialTheme.typography.bodyLarge, color = c.graphite)
                GhostButton(stringResource(R.string.try_again), onClick = onRetry, modifier = Modifier.padding(top = 16.dp))
            }
            is CatalogState.Ready -> Results(state.catalog, query, filter, downloads, isDownloaded, onQuery, onFilter, onChoose, Modifier.padding(padding))
        }
    }
}

@Composable
private fun Results(
    catalog: Catalog,
    query: String,
    filter: CatalogFilter,
    downloads: Map<String, DownloadState>,
    isDownloaded: (String) -> Boolean,
    onQuery: (String) -> Unit,
    onFilter: (CatalogFilter) -> Unit,
    onChoose: (CatalogScore) -> Unit,
    modifier: Modifier,
) {
    val c = Grulla.colors
    val results = remember(catalog, query, filter) { catalog.search(query, filter, limit = MAX_RESULTS) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.find_hint)) },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.clear_search)) }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.sheet,
                    unfocusedContainerColor = c.sheet,
                    focusedBorderColor = c.indigo,
                    unfocusedBorderColor = c.line,
                ),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (name in catalog.instruments.take(INSTRUMENT_CHIPS)) {
                    GrullaChip(
                        instrumentName(name),
                        filter.instrument == name,
                        onClick = { onFilter(filter.copy(instrument = if (filter.instrument == name) null else name)) },
                    )
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((level, label) in listOf(1 to R.string.level_easy, 2 to R.string.level_medium, 3 to R.string.level_hard)) {
                    GrullaChip(
                        stringResource(label),
                        filter.difficulty == level,
                        onClick = { onFilter(filter.copy(difficulty = if (filter.difficulty == level) null else level)) },
                    )
                }
            }
        }
        item {
            Text(
                if (results.size >= MAX_RESULTS) {
                    stringResource(R.string.results_many, MAX_RESULTS)
                } else {
                    pluralStringResource(R.plurals.results, results.size, results.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = c.graphite,
            )
        }
        items(results, key = { it.id }) { score ->
            ResultRow(score, catalog, downloads[score.id], isDownloaded(score.id)) { onChoose(score) }
        }
        item {
            Text(
                stringResource(R.string.catalog_credits),
                style = MaterialTheme.typography.bodySmall,
                color = c.graphite,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ResultRow(score: CatalogScore, catalog: Catalog, download: DownloadState?, downloaded: Boolean, onClick: () -> Unit) {
    val c = Grulla.colors
    SheetCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(score.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(score.composer, style = MaterialTheme.typography.bodyMedium, color = c.graphite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(
                    score.instruments.map { instrumentName(it) }.joinToString(", ").ifEmpty { null },
                    pluralStringResource(R.plurals.bars, score.bars, score.bars).takeIf { score.bars > 0 },
                    score.difficulty?.let { stringResource(listOf(R.string.level_easy, R.string.level_medium, R.string.level_hard)[it - 1]) },
                ).joinToString(" · ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = c.graphite)
                Text(
                    listOfNotNull(catalog.sources[score.src]?.name, if (score.lic == "CC0") "CC0" else stringResource(R.string.public_domain))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.graphite,
                )
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when {
                    download == DownloadState.DOWNLOADING -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.indigo)
                    downloaded -> Icon(AppIcons.Check, contentDescription = stringResource(R.string.in_library), tint = c.indigo)
                    download == DownloadState.FAILED -> Icon(AppIcons.Warning, contentDescription = stringResource(R.string.download_failed), tint = c.amber)
                    else -> Icon(AppIcons.Download, contentDescription = stringResource(R.string.download), tint = c.ink)
                }
            }
        }
    }
}

/** Catalogue instrument families in the app's language. */
@Composable
private fun instrumentName(family: String): String {
    val id = when (family) {
        "Piano" -> R.string.instrument_piano
        "Voice" -> R.string.instrument_voice
        "Violin" -> R.string.instrument_violin
        "Viola" -> R.string.instrument_viola
        "Cello" -> R.string.instrument_cello
        "Guitar" -> R.string.instrument_guitar
        "Flute" -> R.string.instrument_flute
        "Organ" -> R.string.instrument_organ
        "Clarinet" -> R.string.instrument_clarinet
        "Recorder" -> R.string.instrument_recorder
        "Trumpet" -> R.string.instrument_trumpet
        "Harp" -> R.string.instrument_harp
        else -> null
    }
    return if (id != null) stringResource(id) else family
}

private const val MAX_RESULTS = 200
private const val INSTRUMENT_CHIPS = 8
