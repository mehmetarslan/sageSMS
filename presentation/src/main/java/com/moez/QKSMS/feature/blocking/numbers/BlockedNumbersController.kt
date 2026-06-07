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

import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkController
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import dev.octoshrimpy.quik.databinding.BlockedNumbersControllerBinding
import dev.octoshrimpy.quik.databinding.BlockedNumbersAddDialogBinding
import javax.inject.Inject

class BlockedNumbersController : QkController<BlockedNumbersControllerBinding, BlockedNumbersView, BlockedNumbersState, BlockedNumbersPresenter>(),
    BlockedNumbersView {

    @Inject override lateinit var presenter: BlockedNumbersPresenter
    @Inject lateinit var colors: Colors
    @Inject lateinit var phoneNumberUtils: PhoneNumberUtils

    private val adapter = BlockedNumbersAdapter()
    private val saveAddressSubject: Subject<String> = PublishSubject.create()
    private val importAddressesSubject: Subject<String> = PublishSubject.create()
    private val exportAddressesSubject: Subject<Unit> = PublishSubject.create()

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): BlockedNumbersControllerBinding =
        BlockedNumbersControllerBinding.inflate(inflater, container, false)

    override fun onAttach(view: View) {
        super.onAttach(view)
        setHasOptionsMenu(true)
        presenter.bindIntents(this)
        setTitle(R.string.blocked_numbers_title)
        showBackButton(true)
    }

    override fun onViewCreated() {
        super.onViewCreated()
        binding.add.setBackgroundTint(colors.theme().theme)
        binding.add.setTint(colors.theme().textPrimary)
        adapter.emptyView = binding.empty
        binding.numbers.adapter = adapter
    }

    override fun render(state: BlockedNumbersState) {
        adapter.updateData(state.numbers)
    }

    override fun unblockAddress(): Observable<Long> = adapter.unblockAddress
    override fun addAddress(): Observable<*> = binding.add.clicks()
    override fun saveAddress(): Observable<String> = saveAddressSubject
    override fun importAddresses(): Observable<String> = importAddressesSubject
    override fun exportAddresses(): Observable<*> = exportAddressesSubject

    override fun showAddDialog() {
        val layout = BlockedNumbersAddDialogBinding.inflate(LayoutInflater.from(activity))
        val textWatcher = BlockedNumberTextWatcher(layout.input, phoneNumberUtils)
        val dialog = AlertDialog.Builder(activity!!)
                .setView(layout.root)
                .setPositiveButton(R.string.blocked_numbers_dialog_block) { _, _ ->
                    saveAddressSubject.onNext(layout.input.text.toString())
                }
                .setNegativeButton(R.string.button_cancel) { _, _ -> }
                .setOnDismissListener { textWatcher.dispose() }
        dialog.show()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.blocked_numbers, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.importBlockedNumbers -> {
                showImportDialog()
                true
            }
            R.id.exportBlockedNumbers -> {
                exportAddressesSubject.onNext(Unit)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun showImportDialog() {
        val layout = BlockedNumbersAddDialogBinding.inflate(LayoutInflater.from(activity))
        layout.input.hint = activity?.getString(R.string.blocked_numbers_import_hint)

        AlertDialog.Builder(activity!!)
            .setTitle(R.string.blocked_numbers_import_title)
            .setView(layout.root)
            .setPositiveButton(R.string.blocked_numbers_import_action) { _, _ ->
                importAddressesSubject.onNext(layout.input.text.toString())
            }
            .setNegativeButton(R.string.button_cancel) { _, _ -> }
            .show()
    }

    override fun showExportDialog(exportedJson: String) {
        AlertDialog.Builder(activity!!)
            .setTitle(R.string.blocked_numbers_export_title)
            .setMessage(exportedJson)
            .setPositiveButton(R.string.blocked_numbers_export_copy) { _, _ ->
                val clipboard = activity?.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("blocked_numbers", exportedJson))
                Snackbar.make(binding.root, R.string.blocked_numbers_export_copied, Snackbar.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.button_cancel) { _, _ -> }
            .show()
    }

    override fun showImportResult(importedCount: Int) {
        Snackbar.make(
            binding.root,
            activity?.getString(R.string.blocked_numbers_import_success, importedCount).orEmpty(),
            Snackbar.LENGTH_LONG
        ).show()
    }

    override fun showImportError() {
        Snackbar.make(binding.root, R.string.blocked_numbers_import_error, Snackbar.LENGTH_LONG).show()
    }

}
