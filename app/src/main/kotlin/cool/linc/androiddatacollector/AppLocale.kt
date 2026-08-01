package cool.linc.androiddatacollector

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import java.util.Locale

/**
 * The app's language, as one setting rather than two.
 *
 * The picker in the header writes through [LocaleManager], which is the same store Android's own
 * per-app language screen edits, so changing it in either place is visible in the other. With no
 * override the system language decides, which is the default a participant should not have to
 * discover.
 *
 * The offered list comes from the manifest's `localeConfig` rather than a second list in Kotlin,
 * so adding a translation means adding a `values-*` directory and one line of XML.
 */
object AppLocale {

    /** Language tags this build ships, in the order the manifest declares them. */
    fun supported(context: Context): List<String> {
        val locales = LocaleConfig(context).supportedLocales ?: return emptyList()
        return List(locales.size()) { locales[it].toLanguageTag() }
    }

    /** The chosen tag, or null when the app follows the system language. */
    fun selected(context: Context): String? =
        context.getSystemService(LocaleManager::class.java)
            .applicationLocales
            .takeUnless { it.isEmpty }
            ?.get(0)
            ?.toLanguageTag()

    /** Passing null hands the choice back to the system. */
    fun select(context: Context, tag: String?) {
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    /**
     * Each language names itself. A picker labelled in a language the reader cannot read is the one
     * screen where following the current locale would defeat the purpose.
     */
    fun endonym(tag: String): String = Locale.forLanguageTag(tag).let { it.getDisplayName(it) }
        .replaceFirstChar { first -> first.titlecase(Locale.forLanguageTag(tag)) }
}
