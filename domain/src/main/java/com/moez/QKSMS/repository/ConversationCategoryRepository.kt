package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.ConversationCategory

/** Inbox categories and which senders belong to them. Writes must run off the UI thread. */
interface ConversationCategoryRepository {

    /** Unmanaged copies in display order. */
    fun getCategories(): List<ConversationCategory>

    /** @return the new category's id */
    fun addCategory(name: String): Long

    fun renameCategory(id: Long, name: String)

    /** Removes the category; its senders go back to being uncategorised. */
    fun deleteCategory(id: Long)

    /** Creates [names] as categories, but only if there are none yet. */
    fun addDefaultCategories(names: List<String>)

    /** Sender addresses in the category, exactly as stored on the conversation recipients. */
    fun getAddresses(categoryId: Long): List<String>

    /** Every sender address that is in some category. */
    fun getCategorizedAddresses(): List<String>

    /** Moves the senders to [categoryId], or out of any category when it is null. */
    fun setCategory(addresses: Collection<String>, categoryId: Long?)
}
