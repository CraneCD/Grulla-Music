package com.grulla.music.library

import android.content.Context
import com.grulla.music.core.practice.PracticeLog
import com.grulla.music.core.practice.ScorePractice
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The practice log (time, loop passes, Follow me accuracy per bar), stored as JSON
 * next to the library. Updates are applied in memory at once and written a moment
 * later, so frequent small updates while practising cost one write.
 */
class PracticeRepository(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.applicationContext.filesDir, "practice.json")
    private val mutableLog = MutableStateFlow(PracticeLog())
    val log: StateFlow<PracticeLog> = mutableLog.asStateFlow()
    private var loaded = false

    init {
        scope.launch {
            val stored = withContext(Dispatchers.IO) { if (file.isFile) PracticeLog.decode(file.readText()) else PracticeLog() }
            // Keep anything recorded while the file was being read.
            mutableLog.value = PracticeLog(stored.scores + mutableLog.value.scores)
            loaded = true
            persist()
        }
    }

    fun update(id: String, change: (ScorePractice) -> ScorePractice) {
        mutableLog.value = mutableLog.value.update(id, change)
    }

    fun remove(id: String) {
        mutableLog.value = mutableLog.value.without(id)
    }

    @OptIn(FlowPreview::class)
    private suspend fun persist() {
        log.drop(1).debounce(WRITE_DELAY_MS).collect { value ->
            if (!loaded) return@collect
            withContext(Dispatchers.IO) {
                val partial = File(file.path + ".tmp")
                partial.writeText(value.encode())
                partial.renameTo(file)
            }
        }
    }

    private companion object {
        const val WRITE_DELAY_MS = 2_000L
    }
}
