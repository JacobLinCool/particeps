package cool.jacoblin.particeps

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingActivityActionsAndroidTest {
    @Test
    fun pendingJoinAndActivityResultsSurviveSavedStateSerializationInArrivalOrder() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val state = SavedStateHandle()
            val actions = PendingActivityActions(state)
            actions.enqueue(Bundle().apply {
                putString("type", "join")
                putString("join", "https://study.example.invalid/join?artifact=synthetic")
            })
            actions.enqueue(Bundle().apply {
                putString("type", "permission")
                putBundle("grants", Bundle().apply {
                    putBoolean("android.permission.POST_NOTIFICATIONS", true)
                    putBoolean("android.permission.ACCESS_FINE_LOCATION", false)
                })
            })
            actions.enqueue(Bundle().apply {
                putString("type", "export")
                putString("uri", "content://synthetic.documents/export.partexp")
            })

            // Recreate from actual marshalled saved state, not the original in-memory map.
            val restoredState = recreate(state)
            val restored = PendingActivityActions(restoredState)
            assertEquals(3, restored.pending.value.size)
            val join = restored.removeFirst()
            assertEquals("join", join.getString("type"))
            assertEquals("https://study.example.invalid/join?artifact=synthetic", join.getString("join"))

            // A second recreation must retain only the events that remain queued.
            val afterConsumption = PendingActivityActions(recreate(restoredState))
            assertEquals(2, afterConsumption.pending.value.size)
            val permission = afterConsumption.removeFirst()
            assertEquals("permission", permission.getString("type"))
            val grants = requireNotNull(permission.getBundle("grants"))
            assertTrue(grants.getBoolean("android.permission.POST_NOTIFICATIONS"))
            assertFalse(grants.getBoolean("android.permission.ACCESS_FINE_LOCATION"))
            assertEquals(2, grants.size())
            val export = afterConsumption.removeFirst()
            assertEquals("export", export.getString("type"))
            assertEquals("content://synthetic.documents/export.partexp", export.getString("uri"))
            assertTrue(afterConsumption.pending.value.isEmpty())
            assertThrows(IllegalStateException::class.java) { afterConsumption.removeFirst() }
        }
    }

    @Test
    fun enqueueOwnsTheActionEnvelopeAndPreservesCancelledPickerResult() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val state = SavedStateHandle()
            val actions = PendingActivityActions(state)
            val incoming = Bundle().apply {
                putString("type", "export")
                putString("uri", null)
            }
            actions.enqueue(incoming)
            incoming.putString("type", "changed-after-callback")
            incoming.putString("uri", "content://must-not-replace-the-result")

            val received = PendingActivityActions(recreate(state)).removeFirst()
            assertEquals("export", received.getString("type"))
            assertTrue("An explicit cancelled result must survive recreation", received.containsKey("uri"))
            assertEquals(null, received.getString("uri"))
        }
    }

    private fun recreate(state: SavedStateHandle): SavedStateHandle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(state.savedStateProvider().saveState())
            parcel.setDataPosition(0)
            SavedStateHandle.createHandle(
                requireNotNull(parcel.readBundle(javaClass.classLoader)),
                null,
            )
        } finally {
            parcel.recycle()
        }
    }
}
