package cool.jacoblin.particeps

import android.content.res.Resources

/**
 * A release build ships no demonstration study.
 *
 * The demo is signed with a published key and exports to a published HPKE key, so a study run under
 * it is neither authentic nor confidential. Keeping it out of the release variant means the only
 * study a released app can run is one a research team signed and handed to a participant. Both the
 * loader and the signed envelope live in the debug source set, so neither reaches the APK.
 */
internal object DemoStudy {
    val load: ((Resources) -> ByteArray)? = null
}
