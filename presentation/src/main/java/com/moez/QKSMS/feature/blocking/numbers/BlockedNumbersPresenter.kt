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
package dev.octoshrimpy.quik.feature.blocking.numbers

import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.interactor.MarkBlocked
import dev.octoshrimpy.quik.interactor.MarkUnblocked
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.repository.BlockingRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.util.Preferences
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.realm.Realm
import org.json.JSONArray
import javax.inject.Inject

class BlockedNumbersPresenter @Inject constructor(
    private val blockingRepo: BlockingRepository,
    private val conversationRepo: ConversationRepository,
    private val markUnblocked: MarkUnblocked,
    private val markBlocked: MarkBlocked,
    private val prefs: Preferences
) : QkPresenter<BlockedNumbersView, BlockedNumbersState>(
        BlockedNumbersState(numbers = blockingRepo.getBlockedNumbers())
) {

    override fun bindIntents(view: BlockedNumbersView) {
        super.bindIntents(view)

        view.unblockAddress()
            .observeOn(Schedulers.io())
            .doOnNext { id ->
                blockingRepo.getBlockedNumber(id)?.address
                    ?.let { address -> conversationRepo.getConversation(listOf(address)) }
                    ?.let { conversation -> markUnblocked.execute(listOf(conversation.id)) }
            }
            .doOnNext(blockingRepo::unblockNumber)
            .subscribeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe()

        view.addAddress()
            .autoDisposable(view.scope())
            .subscribe { view.showAddDialog() }

        view.saveAddress()
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe { address ->
                blockingRepo.blockNumber(address)
                applyBlockListToExistingConversations()
            }

        view.exportAddresses()
            .observeOn(Schedulers.io())
            .map {
                val blockedConversationAddresses = conversationRepo
                    .getBlockedConversations()
                    .flatMap { conversation -> conversation.recipients.map { recipient -> recipient.address } }

                val exportEntries = (blockingRepo.getBlockedAddresses() + blockedConversationAddresses)
                    .map { entry -> entry.trim() }
                    .filter { entry -> entry.isNotEmpty() }
                    .distinct()
                    .sorted()

                JSONArray(exportEntries).toString(2)
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .autoDisposable(view.scope())
            .subscribe(view::showExportDialog)

        view.importAddresses()
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .map { rawJson ->
                val importedAddresses = parseImport(rawJson)
                if (importedAddresses.isNotEmpty()) {
                    blockingRepo.replaceBlockedAddresses(importedAddresses)
                    applyBlockListToExistingConversations()
                }
                importedAddresses.size
            }
            .observeOn(AndroidSchedulers.mainThread())
            .autoDisposable(view.scope())
            .subscribe({ importedCount ->
                if (importedCount == 0) {
                    view.showImportError()
                } else {
                    view.showImportResult(importedCount)
                }
            }, {
                view.showImportError()
            })
    }

    private fun parseImport(rawJson: String): List<String> {
        val jsonArray = JSONArray(rawJson)
        return (0 until jsonArray.length())
            .mapNotNull { index -> jsonArray.optString(index).takeIf { item -> item.isNotBlank() } }
    }

    /**
     * After the block list changes, existing [Conversation] rows may still be [Conversation.blocked] = false
     * so the main inbox still shows them. Apply the same rules as incoming SMS: mark matching threads blocked.
     */
    private fun applyBlockListToExistingConversations() {
        Realm.getDefaultInstance().use { realm ->
            val threadIds = realm
                .where(Conversation::class.java)
                .equalTo("blocked", false)
                .findAll()
                .filter { conversation ->
                    conversation.recipients.any { recipient ->
                        blockingRepo.isBlocked(recipient.address)
                    }
                }
                .map { conversation -> conversation.id }
            if (threadIds.isNotEmpty()) {
                markBlocked.execute(
                    MarkBlocked.Params(
                        threadIds,
                        prefs.blockingManager.get(),
                        null
                    )
                )
            }
        }
    }

}
