package dev.octoshrimpy.quik.feature.notificationcustomize

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.common.util.extensions.addSelectionAction
import dev.octoshrimpy.quik.common.util.extensions.withAlpha
import dev.octoshrimpy.quik.databinding.ActivityNotificationPatternBinding
import dev.octoshrimpy.quik.model.NotificationExtractRule
import dev.octoshrimpy.quik.model.NotificationExtractionModes
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.repository.NotificationExtractRuleRepository
import dev.octoshrimpy.quik.rules.NotificationPatternGenerator
import io.reactivex.Completable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import javax.inject.Inject

/**
 * Rule screen opened from "Set as notification" on selected message text. The pattern is generated
 * from the selection, so the user only decides how the notification line looks and who it applies to.
 */
class NotificationPatternActivity : QkThemedActivity() {

    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var ruleRepository: NotificationExtractRuleRepository
    @Inject lateinit var conversationRepository: ConversationRepository

    private lateinit var binding: ActivityNotificationPatternBinding
    private var ruleId = -1L
    private var address = ""
    private var sample = ""
    /** The sender's latest incoming messages, newest first; used to learn the template and to test it. */
    private var recentBodies: List<String> = emptyList()
    private var settingPatternText = false

    private val pattern: String
        get() = binding.pattern.text?.toString()?.trim().orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationPatternBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showBackButton(true)
        title = getString(R.string.notification_pattern_title)

        ruleId = intent.getLongExtra(EXTRA_RULE_ID, -1L)
        val threadId = if (ruleId >= 0) loadRule() else loadMessage()
        if (threadId == null) {
            finish()
            return
        }
        this.threadId.onNext(threadId)
        loadRecentBodies(threadId)

        binding.sampleBody.addSelectionAction(R.string.selection_action_use_as_value) { start, end ->
            generate(start, end)
        }
        binding.template.doAfterTextChanged { updatePreview() }
        binding.pattern.doAfterTextChanged {
            if (!settingPatternText) {
                highlightValue()
                updatePreview()
            }
        }
        binding.insertValue.setOnClickListener { insertPlaceholder() }
        binding.advancedToggle.setOnClickListener { binding.pattern.isVisible = !binding.pattern.isVisible }
        binding.saveButton.setOnClickListener { save() }

        val start = intent.getIntExtra(EXTRA_SELECTION_START, -1)
        val end = intent.getIntExtra(EXTRA_SELECTION_END, -1)
        // EditTexts restore their own text after rotation, so only generate on first launch
        if (savedInstanceState == null && ruleId < 0 && start >= 0 && end > start) {
            generate(start, end)
        }
        highlightValue()
        updatePreview()
    }

    /** @return the thread to read recent messages from, or null if the rule is gone */
    private fun loadRule(): Long? {
        val rule = ruleRepository.getRules().firstOrNull { it.id == ruleId } ?: return null
        address = rule.address
        sample = rule.sampleBody
        binding.sampleBody.text = sample
        setPatternText(rule.pattern)
        binding.template.setText(rule.notificationPrefix)
        binding.scope.check(if (rule.applyToAllSenders) R.id.scopeAll else R.id.scopeSender)
        return conversationRepository.getConversation(listOf(address))?.id ?: 0L
    }

    private fun loadMessage(): Long? {
        val message = messageRepository.getMessage(intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)) ?: return null
        address = message.address
        sample = intent.getStringExtra(EXTRA_TEXT) ?: message.getSummary()
        binding.sampleBody.text = sample
        binding.template.setText(getString(R.string.notification_pattern_default_template))
        return message.threadId
    }

    private fun loadRecentBodies(threadId: Long) {
        if (threadId == 0L) return
        recentBodies = messageRepository.getMessagesSync(threadId)
            .filter { message -> !message.isMe() && message.hasNonWhitespaceText() }
            .takeLast(LEARNING_MESSAGE_COUNT)
            .reversed()
            .map { message -> message.getSummary() }
    }

    private fun generate(start: Int, end: Int) {
        val result = NotificationPatternGenerator.generate(sample, start, end, recentBodies)
        if (result == null) {
            Toast.makeText(this, R.string.notification_pattern_failed, Toast.LENGTH_LONG).show()
            return
        }
        setPatternText(result.pattern)
        highlightValue()
        updatePreview()
    }

    private fun setPatternText(value: String) {
        settingPatternText = true
        binding.pattern.setText(value)
        settingPatternText = false
    }

    /** Marks the part of the sample the current pattern captures. */
    private fun highlightValue() {
        val range = runCatching { Regex(pattern).find(sample)?.groups?.get(1)?.range }.getOrNull()
        if (pattern.isEmpty() || range == null) {
            binding.sampleBody.text = sample
            return
        }
        binding.sampleBody.text = SpannableString(sample).apply {
            val color = colors.theme().theme.withAlpha(0x5d)
            setSpan(BackgroundColorSpan(color), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun insertPlaceholder() {
        val placeholder = getString(R.string.notification_pattern_default_template)
        val editable = binding.template.text ?: return
        val cursor = binding.template.selectionStart.takeIf { it >= 0 } ?: editable.length
        editable.insert(cursor, placeholder)
    }

    private fun draftRule(): NotificationExtractRule = NotificationExtractRule(
        id = ruleId,
        address = address,
        applyToAllSenders = binding.scope.checkedRadioButtonId == R.id.scopeAll,
        sampleBody = sample,
        searchText = NotificationPatternGenerator.extract(pattern, sample).orEmpty(),
        extractionMode = NotificationExtractionModes.REGEX,
        notificationPrefix = binding.template.text?.toString().orEmpty(),
        pattern = pattern
    )

    private fun updatePreview() {
        val valid = pattern.isNotEmpty() && runCatching { Regex(pattern) }.isSuccess
        val rule = draftRule()
        binding.previewLine.text = when {
            pattern.isEmpty() -> getString(R.string.notification_pattern_no_selection)
            !valid -> getString(R.string.notification_pattern_invalid)
            else -> ruleRepository.applyRule(rule, sample) ?: getString(R.string.notification_pattern_no_match)
        }

        val others = recentBodies.filter { it != sample }.take(PREVIEW_MESSAGE_COUNT)
        binding.recentLabel.isVisible = valid && others.isNotEmpty()
        binding.recentMatches.isVisible = valid && others.isNotEmpty()
        if (!valid || others.isEmpty()) return

        val results = others.map { body -> body to ruleRepository.applyRule(rule, body) }
        binding.recentLabel.text = getString(
            R.string.notification_customize_recent_summary,
            results.count { (_, line) -> line != null },
            results.size
        )
        binding.recentMatches.text = results.joinToString("\n") { (body, line) ->
            if (line != null) "✓ $line" else "✗ ${body.replace('\n', ' ').take(SNIPPET_LENGTH)}…"
        }
    }

    private fun save() {
        val rule = draftRule()
        val error = when {
            pattern.isEmpty() -> R.string.notification_pattern_no_selection
            runCatching { Regex(pattern) }.isFailure -> R.string.notification_pattern_invalid
            rule.searchText.isEmpty() -> R.string.notification_pattern_no_match
            else -> null
        }
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }

        binding.saveButton.isEnabled = false
        // Realm writes are not allowed on the UI thread; close once the rule is committed
        Completable
            .fromAction {
                if (ruleId >= 0) {
                    ruleRepository.updateRule(
                        id = ruleId,
                        address = rule.address,
                        applyToAllSenders = rule.applyToAllSenders,
                        sampleBody = rule.sampleBody,
                        searchText = rule.searchText,
                        leftAnchor = "",
                        rightAnchor = "",
                        extractionMode = rule.extractionMode,
                        notificationPrefix = rule.notificationPrefix,
                        pattern = rule.pattern
                    )
                } else {
                    ruleRepository.saveRule(
                        address = rule.address,
                        applyToAllSenders = rule.applyToAllSenders,
                        sampleBody = rule.sampleBody,
                        searchText = rule.searchText,
                        leftAnchor = "",
                        rightAnchor = "",
                        extractionMode = rule.extractionMode,
                        notificationPrefix = rule.notificationPrefix,
                        pattern = rule.pattern
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

    companion object {
        const val EXTRA_MESSAGE_ID = "messageId"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SELECTION_START = "selectionStart"
        const val EXTRA_SELECTION_END = "selectionEnd"
        const val EXTRA_RULE_ID = "ruleId"
        private const val LEARNING_MESSAGE_COUNT = 30
        private const val PREVIEW_MESSAGE_COUNT = 10
        private const val SNIPPET_LENGTH = 40
    }
}
