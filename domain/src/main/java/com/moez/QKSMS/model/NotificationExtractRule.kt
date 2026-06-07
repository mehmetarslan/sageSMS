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
    var notificationPrefix: String = ""
) : RealmObject()

object NotificationExtractionModes {
    const val INDEXED_SUBSTRING = 2
}
