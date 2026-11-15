package ch.tomigorn.wgchores.ui.chore

import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.api.ApiClient
import ch.tomigorn.wgchores.ui.ErrorText
import kotlinx.coroutines.CancellationException

/** Saving an edited chore: the chore first, then (when asked) the category for the other chores. */
object ChoreEditor {
    sealed interface Result {
        data object Saved : Result
        data class SaveFailed(val text: Int) : Result
        /** The chore is saved, renaming the others did not work. */
        data class RenameFailed(val text: Int) : Result
    }

    suspend fun save(
        api: ApiClient,
        choreId: Long,
        householdId: Long,
        original: String?,
        name: String,
        category: String?,
        interval: String?,
        renameAll: Boolean,
    ): Result {
        try {
            api.patchChore(choreId, name, category, interval)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.SaveFailed(ErrorText.of(e, R.string.done_connect))
        }
        if (!renameAll || original == null || category == null) return Result.Saved
        return try {
            api.renameCategory(householdId, original, category)
            Result.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.RenameFailed(ErrorText.of(e, R.string.done_connect))
        }
    }
}
