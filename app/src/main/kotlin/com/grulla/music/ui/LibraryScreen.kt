package com.grulla.music.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grulla.music.core.library.LibraryEntry
import com.grulla.music.library.LibraryRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: LibraryRepository,
    onOpenEntry: (LibraryEntry) -> Unit,
    onOpenFile: () -> Unit,
    onOpenSample: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val library by repository.library.collectAsStateWithLifecycle()
    val entries = library.recent
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Grulla Music") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(AppIcons.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenFile,
                icon = { Icon(AppIcons.FolderOpen, contentDescription = null) },
                text = { Text("Open score") },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyLibrary(Modifier.padding(padding), onOpenFile, onOpenSample)
        } else {
            LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("Recent scores", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
                }
                items(entries, key = { it.id }) { entry ->
                    LibraryRow(
                        entry = entry,
                        thumbnail = repository.thumbnailFile(entry),
                        onClick = { onOpenEntry(entry) },
                        onRemove = { repository.remove(entry) },
                    )
                }
                item {
                    OutlinedButton(onClick = onOpenSample, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Open the sample score")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onOpenFile: () -> Unit, onOpenSample: () -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.MusicNote, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("No scores yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Open a MusicXML file (.mxl, .musicxml or .xml). Scores you open appear here with their settings.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onOpenFile) { Text("Open score") }
        OutlinedButton(onClick = onOpenSample, modifier = Modifier.padding(top = 8.dp)) { Text("Try the sample") }
    }
}

@Composable
private fun LibraryRow(entry: LibraryEntry, thumbnail: File?, onClick: () -> Unit, onRemove: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(thumbnail)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                entry.composer?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "Opened " + DateUtils.getRelativeTimeSpanString(entry.lastOpened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    style = MaterialTheme.typography.labelSmall,
                )
                settingsSummary(entry)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            IconButton(onClick = onRemove) { Icon(AppIcons.Delete, contentDescription = "Remove from library") }
        }
    }
}

private fun settingsSummary(entry: LibraryEntry): String? {
    val s = entry.settings
    val parts = buildList {
        if (s.tempoPercent != 100) add("tempo ${s.tempoPercent}%")
        if (s.loopStartMs != null) add(if (s.loopEnabled) "loop on" else "loop set")
        if (s.mutedTracks.isNotEmpty() || s.soloedTracks.isNotEmpty()) add("custom mix")
        if (s.metronome) add("metronome")
        if (s.lastPositionMs > 1000) add("at ${formatTime(s.lastPositionMs)}")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun Thumbnail(file: File?) {
    val bitmap by produceState<ImageBitmap?>(null, file?.path, file?.lastModified()) {
        value = file?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    }
    Box(
        Modifier
            .size(width = 64.dp, height = 84.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        } else {
            Icon(AppIcons.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
