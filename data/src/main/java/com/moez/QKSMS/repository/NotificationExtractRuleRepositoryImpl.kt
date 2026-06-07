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
            realm.executeTransactionAsync { bgRealm ->
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
                .sortedByDescending { it.applyToAllSenders }
                .asSequence()
                .mapNotNull { rule ->
                    if (rule.searchText.isEmpty()) {
                        return@mapNotNull null
                    }
                    when (rule.extractionMode) {
                        NotificationExtractionModes.INDEXED_SUBSTRING -> {
                            extractByIndexedPattern(
                                body = fullBody,
                                searchText = rule.searchText,
                                leftAnchor = rule.leftAnchor,
                                rightAnchor = rule.rightAnchor,
                                extractLength = rule.extractLength
                            )?.let { extracted ->
                                val prefix = rule.notificationPrefix.trim()
                                if (prefix.isBlank()) extracted else "$prefix $extracted"
                            }
                        }
                        else -> null
                    }
                }
                .firstOrNull { it.isNotBlank() }
        }
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
            realm.executeTransactionAsync { bgRealm ->
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
            realm.executeTransactionAsync { bgRealm ->
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

    // Candidate starts where leftAnchor starts in incoming message.
    val starts = mutableListOf<Int>()
    if (leftAnchor.isNotEmpty()) {
        var from = 0
        while (from < body.length) {
            val p = body.indexOf(leftAnchor, from, ignoreCase = true)
            if (p < 0) break
            starts.add(p)
            from = p + 1
        }
    } else {
        starts.add(0)
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
