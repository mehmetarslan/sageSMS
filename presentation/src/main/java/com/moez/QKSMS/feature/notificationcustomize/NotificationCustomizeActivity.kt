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
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.databinding.ActivityNotificationCustomizeBinding
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.NotificationExtractRule
import dev.octoshrimpy.quik.model.NotificationExtractionModes
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.repository.NotificationExtractRuleRepository
import io.reactivex.Completable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
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

    @Inject
    lateinit var conversationRepository: ConversationRepository

    private lateinit var binding: ActivityNotificationCustomizeBinding
    private var convThreadId: Long = 0
    private var sampleAddress: String = ""
    private var sampleBody: String = ""
    private var extractStartInSearch: Int = -1
    private var extractLength: Int = 0
    private var editRuleId: Long = -1L
    /** Bodies of the sender's latest incoming messages, newest first, used to test the rule live. */
    private var recentBodies: List<String> = emptyList()

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
        binding.extractionMode.setOnCheckedChangeListener { _, _ -> updatePreview() }
        binding.prefixText.doAfterTextChanged { updatePreview() }
        binding.searchText.doAfterTextChanged { updatePreview() }

        if (isEditMode) {
            val address = intent.getStringExtra(EXTRA_RULE_ADDRESS).orEmpty()
            val applyToAll = intent.getBooleanExtra(EXTRA_RULE_GLOBAL, false)
            val sample = intent.getStringExtra(EXTRA_RULE_SAMPLE_BODY).orEmpty()
            val search = intent.getStringExtra(EXTRA_RULE_SEARCH).orEmpty()
            val extract = intent.getStringExtra(EXTRA_RULE_EXTRACT).orEmpty()
            val prefix = intent.getStringExtra(EXTRA_RULE_PREFIX).orEmpty()
            val extractStart = intent.getIntExtra(EXTRA_RULE_EXTRACT_START, -1)
            val mode = intent.getIntExtra(EXTRA_RULE_MODE, NotificationExtractionModes.INDEXED_SUBSTRING)
            openEditorForExistingRule(address, sample, search, extract, extractStart, prefix, applyToAll)
            setMode(mode)
            restoreSelection(savedInstanceState)
            loadRecentBodies()
            updatePreview()
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
        restoreSelection(savedInstanceState)
        loadRecentBodies()
        updatePreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_EXTRACT_START, extractStartInSearch)
        outState.putInt(STATE_EXTRACT_LENGTH, extractLength)
    }

    /** EditTexts restore their own text after rotation, but the extract offsets live in fields. */
    private fun restoreSelection(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        extractStartInSearch = savedInstanceState.getInt(STATE_EXTRACT_START, -1)
        extractLength = savedInstanceState.getInt(STATE_EXTRACT_LENGTH, 0)
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
        setMode(NotificationExtractionModes.NUMBER)
        extractStartInSearch = -1
        extractLength = 0
    }

    private fun openEditorForExistingRule(
        address: String,
        sample: String,
        search: String,
        extract: String,
        extractStart: Int,
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
            // Prefer the stored position: indexOf picks the wrong spot when the value also appears earlier
            val start = extractStart
                .takeIf { it >= 0 && search.regionMatches(it, extract, 0, extract.length) }
                ?: search.indexOf(extract)
            if (start >= 0) {
                extractStartInSearch = start
                extractLength = extract.length
            }
        }
    }

    /** Either a rule built from the current inputs, or the string resource explaining what is missing. */
    private sealed class Draft {
        class Ready(val rule: NotificationExtractRule) : Draft()
        class Invalid(@StringRes val error: Int) : Draft()
    }

    private fun buildDraft(): Draft {
        val rawSearch = binding.searchText.text?.toString().orEmpty()
        val search = rawSearch.trim()
        // extractStartInSearch was measured on the untrimmed field, so shift it past the trimmed leading whitespace
        val extractStart = extractStartInSearch - (rawSearch.length - rawSearch.trimStart().length)
        val display = binding.displayText.text?.toString().orEmpty()
        if (search.isEmpty() || display.isEmpty() || extractStartInSearch < 0 || extractLength <= 0) {
            return Draft.Invalid(R.string.notification_customize_error_empty)
        }
        if (extractStart < 0 || extractStart + extractLength > search.length) {
            return Draft.Invalid(R.string.notification_customize_error_extract_inside_search)
        }
        if (editRuleId < 0L && !sampleBody.contains(search)) {
            return Draft.Invalid(R.string.notification_customize_error_search_not_in_sample)
        }
        if (search.substring(extractStart, extractStart + extractLength) != display) {
            return Draft.Invalid(R.string.notification_customize_error_extract_inside_search)
        }
        return Draft.Ready(
            NotificationExtractRule(
                id = editRuleId,
                address = sampleAddress,
                applyToAllSenders = binding.applyToAllSenders.isChecked,
                sampleBody = sampleBody,
                searchText = search,
                leftAnchor = search.substring(0, extractStart),
                rightAnchor = search.substring(extractStart + extractLength),
                extractionMode = selectedMode(),
                extractLength = extractLength,
                notificationPrefix = binding.prefixText.text?.toString()?.trim().orEmpty()
            )
        )
    }

    private fun selectedMode(): Int = when (binding.extractionMode.checkedRadioButtonId) {
        R.id.modeNumber -> NotificationExtractionModes.NUMBER
        R.id.modeWord -> NotificationExtractionModes.WORD
        R.id.modeUntilRight -> NotificationExtractionModes.UNTIL_RIGHT_ANCHOR
        else -> NotificationExtractionModes.INDEXED_SUBSTRING
    }

    private fun setMode(mode: Int) {
        binding.extractionMode.check(
            when (mode) {
                NotificationExtractionModes.NUMBER -> R.id.modeNumber
                NotificationExtractionModes.WORD -> R.id.modeWord
                NotificationExtractionModes.UNTIL_RIGHT_ANCHOR -> R.id.modeUntilRight
                else -> R.id.modeFixedLength
            }
        )
    }

    private fun loadRecentBodies() {
        val threadId = convThreadId.takeIf { it != 0L }
            ?: sampleAddress.takeIf { it.isNotBlank() && it != GLOBAL_RULE_ADDRESS }
                ?.let { address -> conversationRepository.getConversation(listOf(address))?.id }
            ?: return
        recentBodies = messageRepository.getMessagesSync(threadId)
            .filter { message -> !message.isMe() && message.hasNonWhitespaceText() }
            .takeLast(RECENT_MESSAGE_COUNT)
            .reversed()
            .map { message -> message.getSummary() }
    }

    private fun updatePreview() {
        val rule = (buildDraft() as? Draft.Ready)?.rule
        binding.previewLine.text = when {
            rule == null -> getString(R.string.notification_customize_preview_incomplete)
            else -> ruleRepository.applyRule(rule, sampleBody)
                ?: getString(R.string.notification_customize_preview_no_match)
        }

        binding.recentMatchesLabel.isVisible = recentBodies.isNotEmpty()
        binding.recentMatches.isVisible = recentBodies.isNotEmpty()
        if (rule == null || recentBodies.isEmpty()) {
            binding.recentMatches.text = ""
            return
        }
        val results = recentBodies.map { body -> body to ruleRepository.applyRule(rule, body) }
        val lines = results.map { (body, line) ->
            if (line != null) "✓ $line"
            else "✗ ${body.replace('\n', ' ').take(RECENT_SNIPPET_LENGTH)}…"
        }
        val summary = getString(
            R.string.notification_customize_recent_summary,
            results.count { (_, line) -> line != null },
            results.size
        )
        binding.recentMatches.text = (listOf(summary) + lines).joinToString("\n")
    }

    private fun onSave() {
        val rule = when (val draft = buildDraft()) {
            is Draft.Invalid -> {
                Toast.makeText(this, draft.error, Toast.LENGTH_LONG).show()
                return
            }
            is Draft.Ready -> draft.rule
        }
        binding.saveButton.isEnabled = false
        // Realm writes are not allowed on the UI thread; close the editor once the rule is committed
        Completable
            .fromAction {
                if (editRuleId >= 0L) {
                    ruleRepository.updateRule(
                        id = editRuleId,
                        address = rule.address,
                        applyToAllSenders = rule.applyToAllSenders,
                        sampleBody = rule.sampleBody,
                        searchText = rule.searchText,
                        leftAnchor = rule.leftAnchor,
                        rightAnchor = rule.rightAnchor,
                        extractionMode = rule.extractionMode,
                        extractLength = rule.extractLength,
                        notificationPrefix = rule.notificationPrefix
                    )
                } else {
                    ruleRepository.saveRule(
                        address = rule.address,
                        applyToAllSenders = rule.applyToAllSenders,
                        sampleBody = rule.sampleBody,
                        searchText = rule.searchText,
                        leftAnchor = rule.leftAnchor,
                        rightAnchor = rule.rightAnchor,
                        extractionMode = rule.extractionMode,
                        extractLength = rule.extractLength,
                        notificationPrefix = rule.notificationPrefix
                    )
                }
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .autoDisposable(scope())
            .subscribe({
                Toast.makeText(this, R.string.notification_customize_saved, Toast.LENGTH_SHORT).show()
                finish()
            }, { e ->
                Timber.e(e, "Saving notification rule failed")
                binding.saveButton.isEnabled = true
                Toast.makeText(this, R.string.notification_customize_save_failed, Toast.LENGTH_LONG).show()
            })
    }

    private fun copySelectionFromSample() {
        // Trim so stray spaces picked up by the selection handles don't become part of the anchors
        val selected = getSelectedText(binding.sampleBody)?.trim()
        if (selected.isNullOrBlank()) {
            Toast.makeText(this, R.string.notification_customize_error_select_text, Toast.LENGTH_SHORT).show()
            return
        }
        binding.searchText.setText(selected)
        binding.displayText.setText("")
        extractStartInSearch = -1
        extractLength = 0
        updatePreview()
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
        setMode(NotificationExtractionModes.suggestFor(selected))
        updatePreview()
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
        const val EXTRA_RULE_EXTRACT_START = "ruleExtractStart"
        const val EXTRA_RULE_MODE = "ruleMode"
        private const val GLOBAL_RULE_ADDRESS = "*"
        private const val RECENT_MESSAGE_COUNT = 10
        private const val RECENT_SNIPPET_LENGTH = 40
        private const val STATE_EXTRACT_START = "extractStart"
        private const val STATE_EXTRACT_LENGTH = "extractLength"
    }
}
