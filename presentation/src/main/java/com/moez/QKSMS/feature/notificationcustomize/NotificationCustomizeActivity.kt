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
package dev.octoshrimpy.quik.feature.notificationcustomize

import android.os.Bundle
import android.text.Selection
import android.text.Spannable
import android.widget.TextView
import android.widget.Toast
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.databinding.ActivityNotificationCustomizeBinding
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.NotificationExtractionModes
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.repository.NotificationExtractRuleRepository
import javax.inject.Inject

/**
 * Direct rule editor opened from selected message in conversation.
 * Flow:
 * 1) Select text from sample SMS -> "Use selection as search".
 * 2) Select text inside search field -> "Use selection as extract".
 * 3) Optional notification prefix + save.
 */
class NotificationCustomizeActivity : QkThemedActivity() {

    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var ruleRepository: NotificationExtractRuleRepository

    private lateinit var binding: ActivityNotificationCustomizeBinding
    private var convThreadId: Long = 0
    private var sampleAddress: String = ""
    private var sampleBody: String = ""
    private var extractStartInSearch: Int = -1
    private var extractLength: Int = 0
    private var editRuleId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        editRuleId = intent.getLongExtra(EXTRA_RULE_ID, -1L)
        val isEditMode = editRuleId >= 0L
        convThreadId = intent.getLongExtra(EXTRA_THREAD_ID, 0L)
        if (!isEditMode && convThreadId == 0L) {
            finish()
            return
        }
        if (convThreadId != 0L) {
            this.threadId.onNext(convThreadId)
        }
        binding = ActivityNotificationCustomizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showBackButton(true)
        title = getString(R.string.notification_customize_edit_title)

        binding.selectSearchButton.setOnClickListener { copySelectionFromSample() }
        binding.selectExtractButton.setOnClickListener { copySelectionFromSearch() }
        binding.saveButton.setOnClickListener { onSave() }

        if (isEditMode) {
            val address = intent.getStringExtra(EXTRA_RULE_ADDRESS).orEmpty()
            val applyToAll = intent.getBooleanExtra(EXTRA_RULE_GLOBAL, false)
            val sample = intent.getStringExtra(EXTRA_RULE_SAMPLE_BODY).orEmpty()
            val search = intent.getStringExtra(EXTRA_RULE_SEARCH).orEmpty()
            val extract = intent.getStringExtra(EXTRA_RULE_EXTRACT).orEmpty()
            val prefix = intent.getStringExtra(EXTRA_RULE_PREFIX).orEmpty()
            openEditorForExistingRule(address, sample, search, extract, prefix, applyToAll)
            return
        }

        val pre = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        val message = if (pre > 0L) {
            messageRepository.getMessage(pre)
        } else {
            messageRepository.getMessagesSync(convThreadId).lastOrNull { it.hasNonWhitespaceText() }
        }
        if (message == null || message.threadId != convThreadId || !message.hasNonWhitespaceText()) {
            Toast.makeText(this, R.string.notification_customize_error_pick_message, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        openEditorFor(message)
    }

    private fun openEditorFor(message: Message) {
        sampleAddress = message.address
        sampleBody = message.getSummary()
        binding.sampleBody.text = sampleBody
        binding.searchText.setText("")
        binding.displayText.setText("")
        binding.prefixText.setText("")
        binding.applyToAllSenders.isChecked = false
        binding.selectSearchButton.isEnabled = true
        extractStartInSearch = -1
        extractLength = 0
    }

    private fun openEditorForExistingRule(
        address: String,
        sample: String,
        search: String,
        extract: String,
        prefix: String,
        applyToAll: Boolean
    ) {
        sampleAddress = address
        sampleBody = sample.ifBlank { search }
        binding.sampleBody.text = sampleBody
        binding.searchText.setText(search)
        binding.displayText.setText(extract)
        binding.prefixText.setText(prefix)
        binding.applyToAllSenders.isChecked = applyToAll
        binding.selectSearchButton.isEnabled = true
        if (search.isNotEmpty() && extract.isNotEmpty()) {
            val start = search.indexOf(extract)
            if (start >= 0) {
                extractStartInSearch = start
                extractLength = extract.length
            }
        }
    }

    private fun onSave() {
        val search = binding.searchText.text?.toString()?.trim().orEmpty()
        val display = binding.displayText.text?.toString().orEmpty()
        val prefix = binding.prefixText.text?.toString()?.trim().orEmpty()
        val applyToAll = binding.applyToAllSenders.isChecked
        if (search.isEmpty() || display.isEmpty() || extractStartInSearch < 0 || extractLength <= 0) {
            Toast.makeText(this, R.string.notification_customize_error_empty, Toast.LENGTH_LONG).show()
            return
        }
        if (extractStartInSearch + extractLength > search.length) {
            Toast.makeText(this, R.string.notification_customize_error_extract_inside_search, Toast.LENGTH_LONG).show()
            return
        }
        if (editRuleId < 0L && !sampleBody.contains(search)) {
            Toast.makeText(this, R.string.notification_customize_error_search_not_in_sample, Toast.LENGTH_LONG).show()
            return
        }
        val selectedFromSearch = search.substring(extractStartInSearch, extractStartInSearch + extractLength)
        if (selectedFromSearch != display) {
            Toast.makeText(this, R.string.notification_customize_error_extract_inside_search, Toast.LENGTH_LONG).show()
            return
        }
        val left = search.substring(0, extractStartInSearch)
        val right = search.substring(extractStartInSearch + extractLength)
        if (editRuleId >= 0L) {
            ruleRepository.updateRule(
                id = editRuleId,
                address = sampleAddress,
                applyToAllSenders = applyToAll,
                sampleBody = sampleBody,
                searchText = search,
                leftAnchor = left,
                rightAnchor = right,
                extractionMode = NotificationExtractionModes.INDEXED_SUBSTRING,
                extractLength = extractLength,
                notificationPrefix = prefix
            )
        } else {
            ruleRepository.saveRule(
                address = sampleAddress,
                applyToAllSenders = applyToAll,
                sampleBody = sampleBody,
                searchText = search,
                leftAnchor = left,
                rightAnchor = right,
                extractionMode = NotificationExtractionModes.INDEXED_SUBSTRING,
                extractLength = extractLength,
                notificationPrefix = prefix
            )
        }
        Toast.makeText(this, R.string.notification_customize_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun copySelectionFromSample() {
        val selected = getSelectedText(binding.sampleBody)
        if (selected.isNullOrBlank()) {
            Toast.makeText(this, R.string.notification_customize_error_select_text, Toast.LENGTH_SHORT).show()
            return
        }
        binding.searchText.setText(selected)
        binding.displayText.setText("")
        extractStartInSearch = -1
        extractLength = 0
    }

    private fun copySelectionFromSearch() {
        val text = binding.searchText.text ?: run {
            Toast.makeText(this, R.string.notification_customize_error_select_text, Toast.LENGTH_SHORT).show()
            return
        }
        val start = binding.searchText.selectionStart
        val end = binding.searchText.selectionEnd
        if (start < 0 || end < 0 || start == end) {
            Toast.makeText(this, R.string.notification_customize_error_select_text, Toast.LENGTH_SHORT).show()
            return
        }
        val s0 = minOf(start, end)
        val e0 = maxOf(start, end)
        val selected = text.substring(s0, e0)
        binding.displayText.setText(selected)
        extractStartInSearch = s0
        extractLength = e0 - s0
        if (text is Spannable) {
            Selection.setSelection(text, s0, e0)
        }
    }

    private fun getSelectedText(textView: TextView): String? {
        val text = textView.text ?: return null
        val start = textView.selectionStart
        val end = textView.selectionEnd
        if (start < 0 || end < 0 || start == end) {
            return null
        }
        val s0 = minOf(start, end)
        val e0 = maxOf(start, end)
        return text.substring(s0, e0)
    }

    companion object {
        const val EXTRA_THREAD_ID = "threadId"
        const val EXTRA_MESSAGE_ID = "messageId"
        const val EXTRA_RULE_ID = "ruleId"
        const val EXTRA_RULE_ADDRESS = "ruleAddress"
        const val EXTRA_RULE_SEARCH = "ruleSearch"
        const val EXTRA_RULE_EXTRACT = "ruleExtract"
        const val EXTRA_RULE_PREFIX = "rulePrefix"
        const val EXTRA_RULE_GLOBAL = "ruleGlobal"
        const val EXTRA_RULE_SAMPLE_BODY = "ruleSampleBody"
    }
}
