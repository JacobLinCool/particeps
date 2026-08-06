package cool.jacoblin.particeps

import android.content.res.Resources
import java.util.Base64

/**
 * The built-in demonstration study, which exists in debug builds only.
 *
 * Its signing and export keys are public fixtures in `researcher-tools/examples`, so anything it
 * collects is encrypted to a key anyone can open. That is fine for development and emulator work
 * and wrong for anyone who downloaded a release, which is why neither this loader nor the signed
 * envelope it reads is compiled into the release variant — see the release source set for the
 * counterpart that reports the demo as absent.
 */
internal object DemoStudy {
    /** Null in a build that ships no demonstration study; the entry point is then not rendered. */
    val load: ((Resources) -> ByteArray)? = { resources ->
        val encoded = resources.openRawResource(R.raw.demo_study_envelope)
            .bufferedReader()
            .use { it.readText() }
        Base64.getDecoder().decode(encoded.trim())
    }
}
