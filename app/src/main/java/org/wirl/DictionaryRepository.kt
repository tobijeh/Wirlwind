package org.wirl

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * The one copy of the dictionary in the app. The Dictionary screen, quick add, and cleanup all read and
 * write through here, so they always agree.
 */
class DictionaryRepository(private val prefs: Prefs) {
    private val _entries = MutableStateFlow(prefs.dictionary)
    val entries: StateFlow<List<DictionaryEntry>> = _entries.asStateFlow()

    /** Applies [change] to the current list and saves the result. */
    fun update(change: (List<DictionaryEntry>) -> List<DictionaryEntry>) {
        val updated = change(_entries.value)
        prefs.dictionary = updated
        _entries.value = updated
    }

    /** Re-reads storage, in case it was changed outside this repository. */
    fun reload() {
        _entries.value = prefs.dictionary
    }

    companion object {
        @Volatile private var instance: DictionaryRepository? = null

        fun get(context: Context): DictionaryRepository =
            instance ?: synchronized(this) {
                instance ?: DictionaryRepository(Prefs(context.applicationContext)).also { instance = it }
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
