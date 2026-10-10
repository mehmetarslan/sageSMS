package dev.octoshrimpy.quik.model

import io.realm.RealmObject
import io.realm.annotations.Index
import io.realm.annotations.PrimaryKey

/**
 * searchText: the full sample text selected by the user from the original SMS.
 * leftAnchor/rightAnchor: prefix/suffix inside searchText around the extract part.
 * extractLength: length of the selected extract part in the sample.
 * notificationPrefix: optional free text shown before extracted value in the notification.
 */
open class NotificationExtractRule(
    @PrimaryKey var id: Long = 0,
    @Index var address: String = "",
    var applyToAllSenders: Boolean = false,
    var sampleBody: String = "",
    var searchText: String = "",
    var leftAnchor: String = "",
    var rightAnchor: String = "",
    var extractionMode: Int = NotificationExtractionModes.INDEXED_SUBSTRING,
    var extractLength: Int = 0,
    var notificationPrefix: String = "",
    /** Generated (or hand-edited) regex for [NotificationExtractionModes.REGEX]; group 1 is the value. */
    var pattern: String = ""
) : RealmObject()

object NotificationExtractionModes {
    /** Value has exactly [NotificationExtractRule.extractLength] characters (original behaviour). */
    const val INDEXED_SUBSTRING = 2
    /** Any-length number between the anchors, e.g. amounts like 123 or 12.312,50. */
    const val NUMBER = 3
    /** Any-length run of letters/digits, e.g. codes like 4821 or AB12-CD. */
    const val WORD = 4
    /** Everything between the anchors (or to the end of the line without a right anchor). */
    const val UNTIL_RIGHT_ANCHOR = 5
    /** [NotificationExtractRule.pattern] built from a text selection in a message. */
    const val REGEX = 6

    val ALL = listOf(NUMBER, WORD, UNTIL_RIGHT_ANCHOR, INDEXED_SUBSTRING, REGEX)

    /** Markers in the notification text that are replaced by the extracted value. */
    val VALUE_PLACEHOLDERS = listOf("{değer}", "{value}", "{}")

    /**
     * Builds the notification line. A template with a placeholder ("💳 {değer} TL") puts the value
     * there; older rules store a plain prefix, which goes in front of the value.
     */
    fun format(template: String, value: String): String {
        val trimmed = template.trim()
        VALUE_PLACEHOLDERS.firstOrNull { trimmed.contains(it) }?.let { placeholder ->
            return trimmed.replace(placeholder, value)
        }
        return if (trimmed.isBlank()) value else "$trimmed $value"
    }

    /** Picks the value type that fits a highlighted sample value, e.g. "12.312,50" → [NUMBER]. */
    fun suggestFor(value: String): Int = when {
        value.trim().matches(Regex("""\d(?:[\d.,]*\d)?""")) -> NUMBER
        value.trim().matches(Regex("""[\p{L}\p{N}]+(?:[-_/][\p{L}\p{N}]+)*""")) -> WORD
        else -> UNTIL_RIGHT_ANCHOR
    }
}
