package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.NotificationExtractionModes
import dev.octoshrimpy.quik.model.NotificationExtractRule
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import io.realm.Realm
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationExtractRuleRepositoryImpl @Inject constructor(
    private val phoneNumberUtils: PhoneNumberUtils
) : NotificationExtractRuleRepository {

    override fun saveRule(
        address: String,
        applyToAllSenders: Boolean,
        sampleBody: String,
        searchText: String,
        leftAnchor: String,
        rightAnchor: String,
        extractionMode: Int,
        extractLength: Int,
        notificationPrefix: String
    ) {
        val trimmedAddress = address.trim()
        if (trimmedAddress.isEmpty() || searchText.isEmpty()) {
            return
        }
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { bgRealm ->
                bgRealm.where(NotificationExtractRule::class.java)
                    .equalTo("address", trimmedAddress)
                    .equalTo("searchText", searchText)
                    .findAll()
                    .deleteAllFromRealm()
                val maxId = bgRealm.where(NotificationExtractRule::class.java)
                    .max("id")?.toLong() ?: -1L
                bgRealm.insert(
                    NotificationExtractRule(
                        id = maxId + 1,
                        address = trimmedAddress,
                        applyToAllSenders = applyToAllSenders,
                        sampleBody = sampleBody,
                        searchText = searchText,
                        leftAnchor = leftAnchor,
                        rightAnchor = rightAnchor,
                        extractionMode = extractionMode,
                        extractLength = extractLength,
                        notificationPrefix = notificationPrefix
                    )
                )
            }
        }
    }

    override fun getNotificationLine(address: String, fullBody: String): String? {
        if (fullBody.isEmpty()) {
            return null
        }
        return Realm.getDefaultInstance().use { realm ->
            val unmanaged = realm.copyFromRealm(
                realm.where(NotificationExtractRule::class.java).findAll()
            )
            unmanaged
                .filter { r ->
                    r.applyToAllSenders || r.address == GLOBAL_RULE_ADDRESS || phoneNumberUtils.compare(r.address, address)
                }
                // Sender-specific rules win over global ones
                .sortedBy { r -> r.applyToAllSenders || r.address == GLOBAL_RULE_ADDRESS }
                .asSequence()
                .mapNotNull { rule -> applyRule(rule, fullBody) }
                .firstOrNull { it.isNotBlank() }
        }
    }

    override fun applyRule(rule: NotificationExtractRule, fullBody: String): String? {
        if (rule.searchText.isEmpty() || fullBody.isEmpty()) {
            return null
        }
        val extracted = when (rule.extractionMode) {
            NotificationExtractionModes.INDEXED_SUBSTRING -> extractByIndexedPattern(
                body = fullBody,
                searchText = rule.searchText,
                leftAnchor = rule.leftAnchor,
                rightAnchor = rule.rightAnchor,
                extractLength = rule.extractLength
            )
            else -> extractByAnchors(
                body = fullBody,
                leftAnchor = rule.leftAnchor,
                rightAnchor = rule.rightAnchor,
                mode = rule.extractionMode
            )
        } ?: return null
        val prefix = rule.notificationPrefix.trim()
        return if (prefix.isBlank()) extracted else "$prefix $extracted"
    }

    override fun getRules(addresses: Collection<String>): List<NotificationExtractRule> {
        return Realm.getDefaultInstance().use { realm ->
            val unmanaged = realm.copyFromRealm(
                realm.where(NotificationExtractRule::class.java).findAll()
            )
            if (addresses.isEmpty()) {
                unmanaged.sortedBy { it.id }
            } else {
                unmanaged
                    .filter { rule ->
                        addresses.any { addr -> phoneNumberUtils.compare(rule.address, addr) }
                    }
                    .sortedBy { it.id }
            }
        }
    }

    override fun deleteRule(id: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { bgRealm ->
                bgRealm.where(NotificationExtractRule::class.java)
                    .equalTo("id", id)
                    .findAll()
                    .deleteAllFromRealm()
            }
        }
    }

    override fun updateRule(
        id: Long,
        address: String,
        applyToAllSenders: Boolean,
        sampleBody: String,
        searchText: String,
        leftAnchor: String,
        rightAnchor: String,
        extractionMode: Int,
        extractLength: Int,
        notificationPrefix: String
    ) {
        val trimmedAddress = address.trim()
        if (trimmedAddress.isEmpty() || searchText.isEmpty()) return
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { bgRealm ->
                bgRealm.where(NotificationExtractRule::class.java)
                    .equalTo("id", id)
                    .findAll()
                    .deleteAllFromRealm()
                bgRealm.insert(
                    NotificationExtractRule(
                        id = id,
                        address = trimmedAddress,
                        applyToAllSenders = applyToAllSenders,
                        sampleBody = sampleBody,
                        searchText = searchText,
                        leftAnchor = leftAnchor,
                        rightAnchor = rightAnchor,
                        extractionMode = extractionMode,
                        extractLength = extractLength,
                        notificationPrefix = notificationPrefix
                    )
                )
            }
        }
    }

}

private const val GLOBAL_RULE_ADDRESS = "*"

internal fun extractByIndexedPattern(
    body: String,
    searchText: String,
    leftAnchor: String,
    rightAnchor: String,
    extractLength: Int
): String? {
    if (searchText.isEmpty() || extractLength <= 0) {
        return null
    }
    val leftLen = leftAnchor.length
    val rightLen = rightAnchor.length
    if (leftLen + extractLength + rightLen != searchText.length) {
        return null
    }

    // Candidate starts where leftAnchor starts in incoming message; without a left anchor,
    // work back from each rightAnchor occurrence so the value can sit anywhere in the body.
    val starts = mutableListOf<Int>()
    when {
        leftAnchor.isNotEmpty() -> body.occurrencesOf(leftAnchor).forEach { p -> starts.add(p) }
        rightAnchor.isNotEmpty() -> body.occurrencesOf(rightAnchor)
            .map { p -> p - extractLength }
            .filter { start -> start >= 0 }
            .forEach { start -> starts.add(start) }
        else -> starts.add(0)
    }

    for (segmentStart in starts) {
        val segmentEnd = segmentStart + searchText.length
        if (segmentEnd > body.length) continue
        val segment = body.substring(segmentStart, segmentEnd)
        if (leftAnchor.isNotEmpty() && !segment.startsWith(leftAnchor, ignoreCase = true)) continue
        if (rightAnchor.isNotEmpty()) {
            val expectedRightAt = leftLen + extractLength
            val rightOk = segment.regionMatches(
                expectedRightAt,
                rightAnchor,
                0,
                rightAnchor.length,
                ignoreCase = true
            )
            if (!rightOk) continue
        }
        val extractStart = leftLen
        val extractEnd = extractStart + extractLength
        return segment.substring(extractStart, extractEnd)
    }
    return null
}

/**
 * Finds a value of any length between [leftAnchor] and [rightAnchor]. Anchors are matched
 * case-insensitively with flexible whitespace, so "123 TL" and "123TL" both match " TL".
 */
internal fun extractByAnchors(
    body: String,
    leftAnchor: String,
    rightAnchor: String,
    mode: Int
): String? {
    val left = leftAnchor.trim()
    val right = rightAnchor.trim()
    val value = when (mode) {
        // Lookbehind keeps the match from starting in the middle of a longer number
        NotificationExtractionModes.NUMBER -> """(?<![\d.,])\d(?:[\d.,]*\d)?"""
        NotificationExtractionModes.WORD -> """[\p{L}\p{N}]+(?:[-_/][\p{L}\p{N}]+)*"""
        NotificationExtractionModes.UNTIL_RIGHT_ANCHOR -> {
            if (left.isEmpty() && right.isEmpty()) return null
            if (right.isEmpty()) """[^\n]+""" else """[^\n]+?"""
        }
        else -> return null
    }
    val pattern = buildString {
        append("(?iu)")
        if (left.isNotEmpty()) append(anchorPattern(left)).append("""\s*""")
        append("(").append(value).append(")")
        if (right.isNotEmpty()) append("""\s*""").append(anchorPattern(right))
    }
    return runCatching { Regex(pattern).find(body) }.getOrNull()
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun anchorPattern(anchor: String): String =
    anchor.split(Regex("""\s+""")).joinToString("""\s+""") { part -> Regex.escape(part) }

private fun String.occurrencesOf(needle: String): List<Int> {
    val positions = mutableListOf<Int>()
    var from = 0
    while (from < length) {
        val p = indexOf(needle, from, ignoreCase = true)
        if (p < 0) break
        positions.add(p)
        from = p + 1
    }
    return positions
}
