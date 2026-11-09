package org.wirl

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * The one copy of the dictionary in the app. The Dictionary screen, quick add, cleanup, and sync all read and
 * write through here, so they always agree.
 *
 * [all] is the full list in the sync format, tombstones included. [entries] is the live entries only, which is
 * what everything except sync uses. Edits work on [entries]; [DictionarySync.reconcile] turns them into ids,
 * timestamps and tombstones.
 */
class DictionaryRepository(private val prefs: Prefs, private val clock: () -> Long = System::currentTimeMillis) {
    private val _all = MutableStateFlow(prefs.dictionary)
    val all: StateFlow<List<DictionaryEntry>> = _all.asStateFlow()

    private val _entries = MutableStateFlow(DictionarySync.visible(_all.value))
    val entries: StateFlow<List<DictionaryEntry>> = _entries.asStateFlow()

    /** Called after every local change (not after a sync). The app uses it to schedule a sync. */
    @Volatile var onLocalChange: (() -> Unit)? = null

    /** Applies [change] to the live entries and saves the result. */
    fun update(change: (List<DictionaryEntry>) -> List<DictionaryEntry>) {
        val changed = synchronized(this) {
            val before = _all.value
            val updated = DictionarySync.reconcile(before, change(_entries.value), clock())
            if (updated == before) return@synchronized false
            store(updated)
            true
        }
        if (changed) onLocalChange?.invoke()
    }

    /** Merges a dictionary received from a computer (per-id, newest wins) and saves it. Doesn't trigger a sync. */
    fun applyRemote(remote: List<DictionaryEntry>) = synchronized(this) {
        store(DictionarySync.purge(DictionarySync.merge(_all.value, remote), clock()))
    }

    /** Re-reads storage, in case it was changed outside this repository. */
    fun reload() = synchronized(this) {
        val stored = prefs.dictionary
        _all.value = stored
        _entries.value = DictionarySync.visible(stored)
    }

    private fun store(list: List<DictionaryEntry>) {
        prefs.dictionary = list
        _all.value = list
        _entries.value = DictionarySync.visible(list)
    }

    companion object {
        private var instance: DictionaryRepository? = null
        private var instanceContext: Context? = null

        /** The shared repository for this app. Rebuilt if the application context changes (only happens in tests). */
        fun get(context: Context): DictionaryRepository = synchronized(this) {
            val app = context.applicationContext
            if (instance == null || instanceContext !== app) {
                instance = DictionaryRepository(Prefs(app)).also { repo ->
                    repo.onLocalChange = { SyncScheduler.afterChange(app) }
                }
                instanceContext = app
            }
            instance!!
        }
    }
}

/** What the Dictionary screen shows. [total] counts every saved entry, so an empty state only appears when it's 0. */
data class DictionaryUiState(
    val entries: List<DictionaryEntry>,
    val query: String,
    val total: Int,
)

class DictionaryViewModel(private val repository: DictionaryRepository) {
    val query = MutableStateFlow("")

    val state: Flow<DictionaryUiState> = combine(repository.entries, query) { all, q ->
        DictionaryUiState(
            entries = DictionaryEditor.search(DictionaryEditor.sorted(all), q),
            query = q,
            total = all.size,
        )
    }
}
