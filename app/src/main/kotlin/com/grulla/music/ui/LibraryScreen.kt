package com.grulla.music.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grulla.music.core.library.Library
import com.grulla.music.core.library.LibraryEntry
import com.grulla.music.core.library.LibrarySort
import com.grulla.music.core.library.Setlist
import com.grulla.music.library.LibraryRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the library list shows: a search, a sort order and optionally one setlist. */
data class LibraryView(val query: String = "", val sort: LibrarySort = LibrarySort.RECENT, val setlistId: String? = null)

/** What the library screen can ask for. */
interface LibraryActions {
    fun open(entry: LibraryEntry)
    fun openFile()
    fun importFolder()
    fun openSample()
    fun openSettings()
    fun remove(entry: LibraryEntry)
    /** Creates a setlist (with [entry] in it, if given) and returns its id. */
    fun createSetlist(name: String, entry: LibraryEntry?): String
    fun renameSetlist(id: String, name: String)
    fun deleteSetlist(id: String)
    fun addToSetlist(setlistId: String, entry: LibraryEntry)
    fun removeFromSetlist(setlistId: String, entry: LibraryEntry)
    fun moveInSetlist(setlistId: String, entry: LibraryEntry, by: Int)
}

@Composable
fun LibraryScreen(
    repository: LibraryRepository,
    onOpenEntry: (LibraryEntry) -> Unit,
    onOpenFile: () -> Unit,
    onOpenSample: () -> Unit,
    onOpenSettings: () -> Unit,
    onRemoved: (LibraryEntry) -> Unit,
) {
    val library by repository.library.collectAsStateWithLifecycle()
    val importing by repository.importing.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.RECENT) }
    var setlistId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            repository.importFolder(tree) { count ->
                scope.launch {
                    snackbar.showSnackbar(if (count == 0) "No MusicXML scores in that folder." else "Added $count scores from the folder.")
                }
            }
        }
    }
    val actions = remember(repository) {
        object : LibraryActions {
            override fun open(entry: LibraryEntry) = onOpenEntry(entry)
            override fun openFile() = onOpenFile()
            override fun importFolder() = pickFolder.launch(null)
            override fun openSample() = onOpenSample()
            override fun openSettings() = onOpenSettings()
            override fun remove(entry: LibraryEntry) {
                repository.remove(entry)
                onRemoved(entry)
            }
            override fun createSetlist(name: String, entry: LibraryEntry?) = repository.createSetlist(name, entry?.id)
            override fun renameSetlist(id: String, name: String) = repository.renameSetlist(id, name)
            override fun deleteSetlist(id: String) {
                repository.deleteSetlist(id)
                if (setlistId == id) setlistId = null
            }
            override fun addToSetlist(setlistId: String, entry: LibraryEntry) = repository.addToSetlist(setlistId, entry.id)
            override fun removeFromSetlist(setlistId: String, entry: LibraryEntry) = repository.removeFromSetlist(setlistId, entry.id)
            override fun moveInSetlist(setlistId: String, entry: LibraryEntry, by: Int) = repository.moveInSetlist(setlistId, entry.id, by)
        }
    }
    LibraryContent(
        library = library,
        view = LibraryView(query, sort, setlistId?.takeIf { library.setlist(it) != null }),
        onView = {
            query = it.query
            sort = it.sort
            setlistId = it.setlistId
        },
        thumbnail = { entry, modifier -> FileThumbnail(repository.thumbnailFile(entry), modifier) },
        actions = actions,
        importing = importing,
        snackbarHost = { SnackbarHost(snackbar) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryContent(
    library: Library,
    view: LibraryView,
    onView: (LibraryView) -> Unit,
    thumbnail: @Composable (LibraryEntry, Modifier) -> Unit,
    actions: LibraryActions,
    importing: Int? = null,
    snackbarHost: @Composable () -> Unit = {},
) {
    val c = Grulla.colors
    val setlist = view.setlistId?.let(library::setlist)
    val base = if (setlist != null) library.entriesOf(setlist) else library.sorted(view.sort)
    val shown = library.search(view.query, base)
    // The big "continue" card only on the plain default view.
    val continueEntry = library.lastOpened.takeIf { setlist == null && view.query.isBlank() && view.sort == LibrarySort.RECENT }
    var naming by remember { mutableStateOf<NameRequest?>(null) }

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
                Text(
                    "Grulla",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize * 1.15f),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                IconButton(onClick = actions::openSettings) { Icon(AppIcons.Settings, contentDescription = "Settings") }
            }
        },
        floatingActionButton = {
            if (library.entries.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = actions::openFile,
                    containerColor = c.ink,
                    contentColor = c.paper,
                    shape = RoundedCornerShape(28.dp),
                    icon = { Icon(AppIcons.FolderOpen, contentDescription = null) },
                    text = { Text("Open score", style = MaterialTheme.typography.labelLarge) },
                )
            }
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        if (library.entries.isEmpty()) {
            EmptyLibrary(Modifier.padding(padding), actions, importing)
            return@Scaffold
        }
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Your scores",
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier
                        .padding(bottom = 2.dp)
                        .semantics { heading() },
                )
            }
            item { SearchField(view.query) { onView(view.copy(query = it)) } }
            item { ViewChips(library, view, onView, onNewSetlist = { naming = NameRequest.NewSetlist(null) }) }
            if (importing != null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.indigo)
                        Text("Importing… $importing scores found", style = MaterialTheme.typography.bodyMedium, color = c.graphite)
                    }
                }
            }
            if (setlist != null) {
                item { SetlistHeader(setlist, shown.size, actions, onRename = { naming = NameRequest.Rename(setlist) }) }
            }
            if (continueEntry != null) {
                item { ContinueCard(continueEntry, thumbnail, onOpen = { actions.open(continueEntry) }) }
                if (shown.size > 1) item { Overline("Recent", Modifier.padding(top = 10.dp)) }
            }
            val rows = if (continueEntry != null) shown.filter { it.id != continueEntry.id } else shown
            items(rows, key = { it.id }) { entry ->
                LibraryRow(
                    entry = entry,
                    thumbnail = thumbnail,
                    setlists = library.setlists,
                    inSetlist = setlist,
                    actions = actions,
                    onNewSetlist = { naming = NameRequest.NewSetlist(entry) },
                )
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        when {
                            view.query.isNotBlank() -> "No scores match “${view.query.trim()}”."
                            setlist != null -> "This setlist is empty. Add scores from their ⋮ menu."
                            else -> "No scores."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.graphite,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = actions::importFolder, enabled = importing == null) {
                        Text("Import a folder", color = c.indigo, style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = actions::openSample) {
                        Text("Open the sample score", color = c.indigo, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    naming?.let { request ->
        NameDialog(
            title = if (request is NameRequest.Rename) "Rename setlist" else "New setlist",
            initial = (request as? NameRequest.Rename)?.setlist?.name ?: "",
            onDone = { name ->
                naming = null
                when (request) {
                    is NameRequest.Rename -> actions.renameSetlist(request.setlist.id, name)
                    is NameRequest.NewSetlist -> {
                        val id = actions.createSetlist(name, request.entry)
                        if (request.entry == null) onView(view.copy(setlistId = id))
                    }
                }
            },
            onDismiss = { naming = null },
        )
    }
}

private sealed interface NameRequest {
    data class NewSetlist(val entry: LibraryEntry?) : NameRequest
    data class Rename(val setlist: Setlist) : NameRequest
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val c = Grulla.colors
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search titles and composers") },
        leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(AppIcons.Close, contentDescription = "Clear search") }
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

/** All scores / each setlist / new setlist, and the sort order. */
@Composable
private fun ViewChips(library: Library, view: LibraryView, onView: (LibraryView) -> Unit, onNewSetlist: () -> Unit) {
    val c = Grulla.colors
    var sortMenu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GrullaChip("All scores", view.setlistId == null, onClick = { onView(view.copy(setlistId = null)) })
            for (setlist in library.setlists) {
                GrullaChip(setlist.name, view.setlistId == setlist.id, onClick = { onView(view.copy(setlistId = setlist.id)) }, leadingIcon = AppIcons.Setlist)
            }
            GrullaChip("Setlist", selected = false, onClick = onNewSetlist, leadingIcon = AppIcons.Add)
        }
        if (view.setlistId == null) {
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(AppIcons.Sort, contentDescription = "Sort: ${sortName(view.sort)}") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }, containerColor = c.sheet) {
                    for (sort in LibrarySort.entries) {
                        DropdownMenuItem(
                            text = { Text(sortName(sort)) },
                            trailingIcon = { if (sort == view.sort) Icon(AppIcons.Check, contentDescription = null, tint = c.indigo) },
                            onClick = {
                                sortMenu = false
                                onView(view.copy(sort = sort))
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun sortName(sort: LibrarySort) = when (sort) {
    LibrarySort.RECENT -> "Recently opened"
    LibrarySort.TITLE -> "Title"
    LibrarySort.COMPOSER -> "Composer"
    LibrarySort.ADDED -> "Recently added"
}

@Composable
private fun SetlistHeader(setlist: Setlist, count: Int, actions: LibraryActions, onRename: () -> Unit) {
    val c = Grulla.colors
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(setlist.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(if (count == 1) "1 score" else "$count scores", style = MaterialTheme.typography.bodySmall, color = c.graphite)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(AppIcons.More, contentDescription = "More for ${setlist.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.sheet) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = {
                    menu = false
                    onRename()
                })
                DropdownMenuItem(
                    text = { Text("Delete setlist") },
                    leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                    onClick = {
                        menu = false
                        actions.deleteSetlist(setlist.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Grulla.colors
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sheet,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("e.g. Spring recital") })
        },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
private fun LibraryRow(
    entry: LibraryEntry,
    thumbnail: @Composable (LibraryEntry, Modifier) -> Unit,
    setlists: List<Setlist>,
    inSetlist: Setlist?,
    actions: LibraryActions,
    onNewSetlist: () -> Unit,
) {
    val c = Grulla.colors
    var menu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    SheetCard(Modifier.fillMaxWidth(), onClick = { actions.open(entry) }) {
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
                val opened = if (entry.lastOpened > 0) {
                    "Opened " + DateUtils.getRelativeTimeSpanString(entry.lastOpened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        .toString().replaceFirstChar { it.lowercase() }
                } else {
                    "Not opened yet"
                }
                val extra = settingsTags(entry).firstOrNull()?.first
                Text(
                    listOfNotNull(opened, extra).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.graphite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(AppIcons.More, contentDescription = "More for ${entry.title}", tint = c.graphite) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.sheet) {
                    if (inSetlist != null) {
                        DropdownMenuItem(text = { Text("Move up") }, onClick = {
                            menu = false
                            actions.moveInSetlist(inSetlist.id, entry, -1)
                        })
                        DropdownMenuItem(text = { Text("Move down") }, onClick = {
                            menu = false
                            actions.moveInSetlist(inSetlist.id, entry, 1)
                        })
                        DropdownMenuItem(text = { Text("Remove from “${inSetlist.name}”") }, onClick = {
                            menu = false
                            actions.removeFromSetlist(inSetlist.id, entry)
                        })
                    } else {
                        DropdownMenuItem(
                            text = { Text("Add to setlist") },
                            leadingIcon = { Icon(AppIcons.Setlist, contentDescription = null) },
                            onClick = {
                                menu = false
                                addMenu = true
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remove from library") },
                        leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            actions.remove(entry)
                        },
                    )
                }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }, containerColor = c.sheet) {
                    for (setlist in setlists) {
                        val already = entry.id in setlist.entryIds
                        DropdownMenuItem(
                            text = { Text(setlist.name) },
                            trailingIcon = { if (already) Icon(AppIcons.Check, contentDescription = "Already in it", tint = c.indigo) },
                            enabled = !already,
                            onClick = {
                                addMenu = false
                                actions.addToSetlist(setlist.id, entry)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("New setlist…") },
                        leadingIcon = { Icon(AppIcons.Add, contentDescription = null) },
                        onClick = {
                            addMenu = false
                            onNewSetlist()
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
private fun EmptyLibrary(modifier: Modifier, actions: LibraryActions, importing: Int?) {
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
            "Open a MusicXML file (.mxl, .musicxml or .xml), or import a whole folder. Your scores appear here and remember their settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.graphite,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        InkButton("Open score", onClick = actions::openFile, icon = AppIcons.FolderOpen)
        Spacer(Modifier.height(10.dp))
        GhostButton(if (importing != null) "Importing… $importing found" else "Import a folder", onClick = actions::importFolder, enabled = importing == null)
        Spacer(Modifier.height(10.dp))
        GhostButton("Try the sample", onClick = actions::openSample)
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
