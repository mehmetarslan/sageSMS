/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.extensions.anyOf
import dev.octoshrimpy.quik.model.BlockedNumber
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import io.realm.Realm
import io.realm.RealmResults
import java.util.regex.Pattern
import javax.inject.Inject

class BlockingRepositoryImpl @Inject constructor(
    private val phoneNumberUtils: PhoneNumberUtils
) : BlockingRepository {

    override fun blockNumber(vararg addresses: String) {
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            val blockedNumbers = realm.where(BlockedNumber::class.java).findAll()
            val newAddresses = addresses.filter { address ->
                blockedNumbers.none { number -> phoneNumberUtils.compare(number.address, address) }
            }

            val maxId = realm.where(BlockedNumber::class.java)
                    .max("id")?.toLong() ?: -1

            realm.executeTransaction {
                realm.insert(newAddresses.mapIndexed { index, address ->
                    BlockedNumber(maxId + 1 + index, address)
                })
            }
        }
    }

    override fun getBlockedNumbers(): RealmResults<BlockedNumber> {
        return Realm.getDefaultInstance()
                .where(BlockedNumber::class.java)
                .findAllAsync()
    }

    override fun getBlockedNumber(id: Long): BlockedNumber? {
        return Realm.getDefaultInstance()
                .where(BlockedNumber::class.java)
                .equalTo("id", id)
                .findFirst()
    }

    override fun isBlocked(address: String): Boolean {
        return Realm.getDefaultInstance().use { realm ->
            realm.where(BlockedNumber::class.java)
                    .findAll()
                    .any { number -> doesPatternMatch(number.address, address) }
        }
    }

    override fun getBlockedAddresses(): List<String> {
        return Realm.getDefaultInstance().use { realm ->
            realm.where(BlockedNumber::class.java)
                .findAll()
                .map { blockedNumber -> blockedNumber.address }
        }
    }

    override fun replaceBlockedAddresses(addresses: List<String>) {
        Realm.getDefaultInstance().use { realm ->
            val normalized = addresses
                .map { address -> address.trim() }
                .filter { address -> address.isNotEmpty() }
                .distinct()

            realm.executeTransaction {
                realm.where(BlockedNumber::class.java).findAll().deleteAllFromRealm()
                realm.insert(normalized.mapIndexed { index, value -> BlockedNumber(index.toLong(), value) })
            }
        }
    }

    override fun unblockNumber(id: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                realm.where(BlockedNumber::class.java)
                        .equalTo("id", id)
                        .findAll()
                        .deleteAllFromRealm()
            }
        }
    }

    override fun unblockNumbers(vararg addresses: String) {
        Realm.getDefaultInstance().use { realm ->
            val ids = realm.where(BlockedNumber::class.java)
                    .findAll()
                    .filter { number ->
                        addresses.any { address -> phoneNumberUtils.compare(number.address, address) }
                    }
                    .map { number -> number.id }
                    .toLongArray()

            realm.executeTransaction {
                realm.where(BlockedNumber::class.java)
                        .anyOf("id", ids)
                        .findAll()
                        .deleteAllFromRealm()
            }
        }
    }

    private fun doesPatternMatch(blockedValue: String, incomingAddress: String): Boolean {
        val normalizedBlockedValue = blockedValue.trim()
        val normalizedIncoming = incomingAddress.trim()
        if (normalizedBlockedValue.isEmpty() || normalizedIncoming.isEmpty()) return false

        return when {
            normalizedBlockedValue.startsWith("regex:", ignoreCase = true) -> {
                val regexValue = normalizedBlockedValue.substringAfter(':').trim()
                if (regexValue.isEmpty()) false
                else runCatching { Regex(regexValue).matches(normalizedIncoming) }.getOrDefault(false)
            }
            normalizedBlockedValue.contains('*') -> {
                val wildcardRegex = buildString {
                    append("^")
                    normalizedBlockedValue.forEach { char ->
                        if (char == '*') append(".*") else append(Pattern.quote(char.toString()))
                    }
                    append("$")
                }
                runCatching { Regex(wildcardRegex).matches(normalizedIncoming) }.getOrDefault(false)
            }
            else -> phoneNumberUtils.compare(normalizedBlockedValue, normalizedIncoming)
        }
    }

}
