package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.CategoryMember
import dev.octoshrimpy.quik.model.ConversationCategory
import io.realm.Realm
import io.realm.Sort
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationCategoryRepositoryImpl @Inject constructor() : ConversationCategoryRepository {

    override fun getCategories(): List<ConversationCategory> =
        Realm.getDefaultInstance().use { realm ->
            realm.where(ConversationCategory::class.java)
                .sort("position", Sort.ASCENDING, "id", Sort.ASCENDING)
                .findAll()
                .let(realm::copyFromRealm)
        }

    override fun addCategory(name: String): Long =
        Realm.getDefaultInstance().use { realm ->
            var id = 0L
            realm.executeTransaction {
                id = (realm.where(ConversationCategory::class.java).max("id")?.toLong() ?: 0L) + 1
                val position = (realm.where(ConversationCategory::class.java).max("position")?.toInt() ?: -1) + 1
                realm.insert(ConversationCategory(id = id, name = name.trim(), position = position))
            }
            id
        }

    override fun renameCategory(id: Long, name: String) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                realm.where(ConversationCategory::class.java).equalTo("id", id).findFirst()?.name = name.trim()
            }
        }
    }

    override fun deleteCategory(id: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                realm.where(CategoryMember::class.java).equalTo("categoryId", id).findAll().deleteAllFromRealm()
                realm.where(ConversationCategory::class.java).equalTo("id", id).findAll().deleteAllFromRealm()
            }
        }
    }

    override fun addDefaultCategories(names: List<String>) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                if (realm.where(ConversationCategory::class.java).count() > 0) return@executeTransaction
                names.forEachIndexed { index, name ->
                    realm.insert(ConversationCategory(id = index + 1L, name = name, position = index))
                }
            }
        }
    }

    override fun getAddresses(categoryId: Long): List<String> =
        Realm.getDefaultInstance().use { realm ->
            realm.where(CategoryMember::class.java)
                .equalTo("categoryId", categoryId)
                .findAll()
                .map { member -> member.address }
        }

    override fun getCategorizedAddresses(): List<String> =
        Realm.getDefaultInstance().use { realm ->
            // Only members of categories that still exist count, in case a delete was interrupted
            val categoryIds = realm.where(ConversationCategory::class.java).findAll().map { it.id }.toSet()
            realm.where(CategoryMember::class.java)
                .findAll()
                .filter { member -> member.categoryId in categoryIds }
                .map { member -> member.address }
        }

    override fun setCategory(addresses: Collection<String>, categoryId: Long?) {
        val distinct = addresses.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                if (categoryId == null) {
                    realm.where(CategoryMember::class.java)
                        .`in`("address", distinct.toTypedArray())
                        .findAll()
                        .deleteAllFromRealm()
                } else {
                    realm.insertOrUpdate(distinct.map { address -> CategoryMember(address, categoryId) })
                }
            }
        }
    }
}
