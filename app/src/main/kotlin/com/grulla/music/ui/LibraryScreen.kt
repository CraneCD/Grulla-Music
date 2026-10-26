package com.grulla.music.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

@Composable
fun LibraryScreen(
    repository: LibraryRepository,
    onOpenEntry: (LibraryEntry) -> Unit,
    onOpenFile: () -> Unit,
    onOpenSample: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val library by repository.library.collectAsStateWithLifecycle()
    LibraryContent(
        entries = library.recent,
        thumbnail = { entry, modifier -> FileThumbnail(repository.thumbnailFile(entry), modifier) },
        onOpenEntry = onOpenEntry,
        onRemove = repository::remove,
        onOpenFile = onOpenFile,
        onOpenSample = onOpenSample,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun LibraryContent(
    entries: List<LibraryEntry>,
    thumbnail: @Composable (LibraryEntry, Modifier) -> Unit,
    onOpenEntry: (LibraryEntry) -> Unit,
    onRemove: (LibraryEntry) -> Unit,
    onOpenFile: () -> Unit,
    onOpenSample: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = Grulla.colors
    Scaffold(
        containerColor = c.paper,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(64.dp)
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CraneMark(size = 30.dp)
                Text("Grulla", style = MaterialTheme.typography.titleLarge.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize * 1.15f), modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) { Icon(AppIcons.Settings, contentDescription = "Settings") }
            }
        },
        floatingActionButton = {
            if (entries.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onOpenFile,
                    containerColor = c.ink,
                    contentColor = c.paper,
                    shape = RoundedCornerShape(28.dp),
                    icon = { Icon(AppIcons.FolderOpen, contentDescription = null) },
                    text = { Text("Open score", style = MaterialTheme.typography.labelLarge) },
                )
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyLibrary(Modifier.padding(padding), onOpenFile, onOpenSample)
            return@Scaffold
        }
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Your scores", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(bottom = 6.dp)) }
            item { ContinueCard(entries.first(), thumbnail, onOpen = { onOpenEntry(entries.first()) }) }
            if (entries.size > 1) {
                item { Overline("Recent", Modifier.padding(top = 10.dp)) }
                items(entries.drop(1), key = { it.id }) { entry ->
                    LibraryRow(entry, thumbnail, onClick = { onOpenEntry(entry) }, onRemove = { onRemove(entry) })
                }
            }
            item {
                TextButton(onClick = onOpenSample, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Open the sample score", color = c.indigo, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(entry: LibraryEntry, thumbnail: @Composable (LibraryEntry, Modifier) -> Unit, onOpen: () -> Unit) {
    val c = Grulla.colors
    SheetCard(Modifier.fillMaxWidth()) {
        Column {
            thumbnail(entry, Modifier.fillMaxWidth().height(148.dp))
            HorizontalDivider(color = c.line)
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Overline("Continue where you stopped")
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    entry.composer?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.graphite) }
                }
                val tags = settingsTags(entry)
                if (tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { (text, you) -> Tag(text, you) }
                    }
                }
                CrownButton(if (entry.settings.lastPositionMs > 1000) "Continue" else "Open", onClick = onOpen, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LibraryRow(entry: LibraryEntry, thumbnail: @Composable (LibraryEntry, Modifier) -> Unit, onClick: () -> Unit, onRemove: () -> Unit) {
    val c = Grulla.colors
    var menu by remember { mutableStateOf(false) }
    SheetCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            thumbnail(entry, Modifier.size(width = 54.dp, height = 70.dp).clip(RoundedCornerShape(8.dp)))
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(entry.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                entry.composer?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.graphite, maxLines = 1) }
                val opened = DateUtils.getRelativeTimeSpanString(entry.lastOpened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    .toString().replaceFirstChar { it.lowercase() }
                val extra = settingsTags(entry).firstOrNull()?.first
                Text(
                    listOfNotNull("Opened $opened", extra).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.graphite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(AppIcons.More, contentDescription = "More for ${entry.title}", tint = c.graphite) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.sheet) {
                    DropdownMenuItem(
                        text = { Text("Remove from library") },
                        leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

/** Short descriptions of what was saved for a score; the Boolean marks "you" (indigo) tags. */
private fun settingsTags(entry: LibraryEntry): List<Pair<String, Boolean>> {
    val s = entry.settings
    return buildList {
        if (s.lastPositionMs > 1000) add("At ${formatTime(s.lastPositionMs)}" to false)
        if (s.loopStartMs != null) add((if (s.loopEnabled) "Loop on" else "Loop set") to true)
        if (s.tempoPercent != 100) add("Tempo ${s.tempoPercent}%" to false)
        if (s.metronome) add("Metronome" to false)
        if (s.mutedTracks.isNotEmpty() || s.soloedTracks.isNotEmpty()) add("Custom mix" to true)
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onOpenFile: () -> Unit, onOpenSample: () -> Unit) {
    val c = Grulla.colors
    Column(
        modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CraneMark(size = 88.dp)
        Text("No scores yet", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 20.dp))
        Text(
            "Open a MusicXML file (.mxl, .musicxml or .xml). Scores you open appear here and remember their settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.graphite,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        InkButton("Open score", onClick = onOpenFile, icon = AppIcons.FolderOpen)
        Spacer(Modifier.height(10.dp))
        GhostButton("Try the sample", onClick = onOpenSample)
    }
}

/** Page-1 thumbnail from the library's thumbnail file, or the crane mark while there is none. */
@Composable
fun FileThumbnail(file: File?, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, file?.path, file?.lastModified()) {
        value = file?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    }
    ThumbnailImage(bitmap, modifier)
}

@Composable
fun ThumbnailImage(bitmap: ImageBitmap?, modifier: Modifier) {
    val c = Grulla.colors
    Box(modifier.background(if (bitmap != null) c.sheet else c.tag), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CraneMark(size = 32.dp, ink = c.graphite, crown = c.crown)
        }
    }
}
