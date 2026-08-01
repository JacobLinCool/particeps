package cool.linc.androiddatacollector.collector.keyboardime

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo

class ResearchInputMethodService : InputMethodService() {
    private var keyboardView: ResearchKeyboardView? = null
    private var collectionAllowed = false

    override fun onCreateInputView(): View = ResearchKeyboardView(this).also { view ->
        view.collectionAllowed = collectionAllowed
        view.onKeyCommitted = ::commitKey
        keyboardView = view
    }

    override fun onStartInput(
        attribute: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInput(attribute, restarting)
        collectionAllowed = attribute != null && !SensitiveFieldPolicy.isSensitive(attribute)
        keyboardView?.collectionAllowed = collectionAllowed
    }

    override fun onFinishInput() {
        collectionAllowed = false
        keyboardView?.collectionAllowed = false
        super.onFinishInput()
    }

    private fun commitKey(key: KeyboardKey) {
        val connection = currentInputConnection ?: return
        when (key.category) {
            KeyCategory.BACKSPACE -> connection.deleteSurroundingText(1, 0)
            KeyCategory.ENTER -> connection.commitText("\n", 1)
            KeyCategory.SPACE -> connection.commitText(" ", 1)
            KeyCategory.LETTER -> connection.commitText(key.text, 1)
        }
    }
}

internal object SensitiveFieldPolicy {
    fun isSensitive(editorInfo: EditorInfo): Boolean {
        val inputClass = editorInfo.inputType and InputType.TYPE_MASK_CLASS
        val variation = editorInfo.inputType and InputType.TYPE_MASK_VARIATION
        val password = when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in TEXT_PASSWORD_VARIATIONS
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        val requestsPrivateMode =
            editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        return password || requestsPrivateMode
    }

    private val TEXT_PASSWORD_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )
}
