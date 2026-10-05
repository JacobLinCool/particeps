package cool.jacoblin.particeps

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/** Data-only results survive Activity recreation while the application graph is still starting. */
internal class PendingActivityActions(private val savedState: SavedStateHandle) : ViewModel() {
    val pending: StateFlow<ArrayList<Bundle>> = savedState.getStateFlow(KEY, arrayListOf())

    fun enqueue(action: Bundle) {
        savedState[KEY] = ArrayList(pending.value).apply { add(Bundle(action)) }
    }

    fun removeFirst(): Bundle {
        val current = pending.value
        check(current.isNotEmpty()) { "No pending Activity result" }
        savedState[KEY] = ArrayList(current.drop(1))
        return current.first()
    }

    private companion object { const val KEY = "pending_activity_actions" }
}
