package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.NotificationExtractionModes
import dev.octoshrimpy.quik.model.NotificationExtractRule

/**
 * Per-sender notification line overrides: match [NotificationExtractRule.searchText], then show extracted span.
 */
interface NotificationExtractRuleRepository {

    /**
     * Persists a rule. Replaces any existing rule for the same [address] and [searchText].
     * [searchText] is the full user-selected sample.
     * [leftAnchor]/[rightAnchor] are the fixed parts around the selected extract substring.
     * [extractLength] is the selected extract length.
     * [pattern] is only used by [NotificationExtractionModes.REGEX] rules.
     */
    fun saveRule(
        address: String,
        applyToAllSenders: Boolean = false,
        sampleBody: String = "",
        searchText: String,
        leftAnchor: String,
        rightAnchor: String,
        extractionMode: Int = NotificationExtractionModes.INDEXED_SUBSTRING,
        extractLength: Int = 0,
        notificationPrefix: String = "",
        pattern: String = ""
    )

    /**
     * @return custom notification line, or null to keep the default summary
     */
    fun getNotificationLine(address: String, fullBody: String): String?

    /**
     * Applies a single (possibly unsaved) rule to [fullBody], ignoring its sender.
     * @return the notification line the rule would produce, or null if it does not match
     */
    fun applyRule(rule: NotificationExtractRule, fullBody: String): String?

    /**
     * Lists rules. If [addresses] is empty, returns all rules.
     */
    fun getRules(addresses: Collection<String> = emptyList()): List<NotificationExtractRule>

    fun deleteRule(id: Long)

    fun updateRule(
        id: Long,
        address: String,
        applyToAllSenders: Boolean = false,
        sampleBody: String = "",
        searchText: String,
        leftAnchor: String,
        rightAnchor: String,
        extractionMode: Int = NotificationExtractionModes.INDEXED_SUBSTRING,
        extractLength: Int = 0,
        notificationPrefix: String = "",
        pattern: String = ""
    )
}
