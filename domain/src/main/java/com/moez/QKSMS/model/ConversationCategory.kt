package dev.octoshrimpy.quik.model

import io.realm.RealmObject
import io.realm.annotations.Index
import io.realm.annotations.PrimaryKey

/** A user-defined inbox tab such as "Bank" or "Verification". */
open class ConversationCategory(
    @PrimaryKey var id: Long = 0,
    var name: String = "",
    var position: Int = 0
) : RealmObject()

/**
 * Puts a sender in a category. Keyed by the recipient address rather than the conversation, so it
 * survives conversation re-syncs and a sender is in at most one category.
 */
open class CategoryMember(
    @PrimaryKey var address: String = "",
    @Index var categoryId: Long = 0
) : RealmObject()
