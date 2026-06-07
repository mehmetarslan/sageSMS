package dev.octoshrimpy.quik.feature.notificationcustomize

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.databinding.ActivityNotificationRulesBinding
import dev.octoshrimpy.quik.model.NotificationExtractionModes
import dev.octoshrimpy.quik.model.NotificationExtractRule
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.NotificationExtractRuleRepository
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

class NotificationRulesActivity : QkThemedActivity() {

    @Inject lateinit var navigator: Navigator
    @Inject lateinit var conversationRepository: ConversationRepository
    @Inject lateinit var rulesRepository: NotificationExtractRuleRepository

    private lateinit var binding: ActivityNotificationRulesBinding
    private var conversationThreadId: Long = 0
    private lateinit var adapter: RulesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showBackButton(true)
        title = getString(R.string.notification_rules_title)

        conversationThreadId = intent.getLongExtra(EXTRA_THREAD_ID, 0L)
        adapter = RulesAdapter(
            onEdit = { rule -> editRule(rule) },
            onDelete = { rule -> showRuleActions(rule) }
        )
        binding.rulesList.layoutManager = LinearLayoutManager(this)
        binding.rulesList.adapter = adapter
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.add -> {
                    showAddRuleActions()
                    true
                }
                R.id.exportRules -> {
                    exportRules()
                    true
                }
                R.id.importRules -> {
                    showImportDialog()
                    true
                }
                else -> false
            }
        }
        binding.toolbar.inflateMenu(R.menu.notification_rules)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val rules = if (conversationThreadId == 0L) {
            rulesRepository.getRules()
        } else {
            val addresses = conversationRepository.getConversation(conversationThreadId)
                ?.recipients
                ?.map { it.address }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            rulesRepository.getRules(addresses)
        }
        val addressLabels = buildAddressLabels()
        adapter.submit(buildRows(rules, addressLabels))
    }

    private fun deleteRule(id: Long) {
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_rules_delete_title)
            .setMessage(R.string.notification_rules_delete_body)
            .setPositiveButton(R.string.button_delete) { _, _ ->
                rulesRepository.deleteRule(id)
                refresh()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun showRuleActions(rule: NotificationExtractRule) {
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_rules_edit_title)
            .setItems(
                arrayOf(
                    getString(R.string.button_edit),
                    getString(R.string.button_delete)
                )
            ) { _, which ->
                when (which) {
                    0 -> editRule(rule)
                    1 -> deleteRule(rule.id)
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun editRule(rule: NotificationExtractRule) {
        startActivity(
            Intent(this, NotificationCustomizeActivity::class.java)
                .putExtra(NotificationCustomizeActivity.EXTRA_RULE_ID, rule.id)
                .putExtra(NotificationCustomizeActivity.EXTRA_RULE_ADDRESS, rule.address)
                .putExtra(NotificationCustomizeActivity.EXTRA_RULE_GLOBAL, rule.applyToAllSenders)
                .putExtra(NotificationCustomizeActivity.EXTRA_RULE_SAMPLE_BODY, rule.sampleBody)
                .putExtra(NotificationCustomizeActivity.EXTRA_RULE_SEARCH, rule.searchText)
                .putExtra(NotificationCustomizeActivity.EXTRA_RULE_EXTRACT, extractPreview(rule))
                .putExtra(NotificationCustomizeActivity.EXTRA_RULE_PREFIX, rule.notificationPrefix)
        )
    }

    private fun showAddRuleActions() {
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_rules_add_title)
            .setItems(
                arrayOf(
                    getString(R.string.notification_rules_add_pick_conversation),
                    getString(R.string.notification_rules_add_global)
                )
            ) { _, which ->
                when (which) {
                    0 -> openConversationPicker()
                    1 -> showGlobalCreateDialog()
                }
            }
            .show()
    }

    private fun openConversationPicker() {
        val conversations = conversationRepository.getConversationsSnapshot(unreadAtTop = false)
            .filter { it.recipients.isNotEmpty() }
        if (conversations.isEmpty()) return
        val labels = conversations.map { c ->
            c.getTitle().takeIf { it.isNotBlank() } ?: c.recipients.joinToString(", ") { recipient -> recipient.address }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_rules_add_pick_conversation)
            .setItems(labels) { _, which ->
                val picked = conversations[which]
                navigator.showConversation(picked.id)
                finish()
            }
            .setMessage(R.string.notification_rules_add_hint)
            .show()
    }

    private fun showGlobalCreateDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val searchInput = EditText(this).apply { hint = getString(R.string.notification_customize_search_label) }
        val extractInput = EditText(this).apply { hint = getString(R.string.notification_customize_display_label) }
        val prefixInput = EditText(this).apply { hint = getString(R.string.notification_customize_prefix_label) }
        root.addView(searchInput)
        root.addView(extractInput)
        root.addView(prefixInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_rules_new_global_title)
            .setView(root)
            .setPositiveButton(R.string.button_save) { _, _ ->
                saveManualRule(
                    id = -1L,
                    address = GLOBAL_RULE_ADDRESS,
                    search = searchInput.text?.toString()?.trim().orEmpty(),
                    extract = extractInput.text?.toString().orEmpty(),
                    prefix = prefixInput.text?.toString()?.trim().orEmpty(),
                    applyToAllSenders = true,
                    sampleBody = searchInput.text?.toString()?.trim().orEmpty()
                )
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun saveManualRule(
        id: Long,
        address: String,
        search: String,
        extract: String,
        prefix: String,
        applyToAllSenders: Boolean = false,
        sampleBody: String = search
    ) {
        if (search.isEmpty() || extract.isEmpty() || extract.length > search.length) return
        val start = search.indexOf(extract)
        if (start < 0) return
        val left = search.substring(0, start)
        val right = search.substring(start + extract.length)
        if (id > 0) {
            rulesRepository.updateRule(
                id = id,
                address = address,
                applyToAllSenders = applyToAllSenders,
                sampleBody = sampleBody,
                searchText = search,
                leftAnchor = left,
                rightAnchor = right,
                extractionMode = NotificationExtractionModes.INDEXED_SUBSTRING,
                extractLength = extract.length,
                notificationPrefix = prefix
            )
        } else {
            rulesRepository.saveRule(
                address = address,
                applyToAllSenders = applyToAllSenders,
                sampleBody = sampleBody,
                searchText = search,
                leftAnchor = left,
                rightAnchor = right,
                extractionMode = NotificationExtractionModes.INDEXED_SUBSTRING,
                extractLength = extract.length,
                notificationPrefix = prefix
            )
        }
        refresh()
    }

    private fun exportRules() {
        val rows = rulesRepository.getRules()
        val array = JSONArray()
        rows.forEach { rule ->
            array.put(
                JSONObject().apply {
                    put("address", rule.address)
                    put("searchText", rule.searchText)
                    put("applyToAllSenders", rule.applyToAllSenders)
                    put("sampleBody", rule.sampleBody)
                    put("leftAnchor", rule.leftAnchor)
                    put("rightAnchor", rule.rightAnchor)
                    put("extractLength", rule.extractLength)
                    put("notificationPrefix", rule.notificationPrefix)
                }
            )
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("notification_rules", array.toString()))
        Toast.makeText(this, R.string.notification_rules_export_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.notification_rules_import_hint)
            minLines = 6
            setText("")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_rules_import_title)
            .setView(input)
            .setPositiveButton(R.string.blocked_numbers_import_action) { _, _ ->
                importRules(input.text?.toString().orEmpty())
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun importRules(json: String) {
        try {
            val array = JSONArray(json)
            var imported = 0
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val address = obj.optString("address", "").trim()
                val searchText = obj.optString("searchText", "").trim()
                val applyToAllSenders = obj.optBoolean("applyToAllSenders", false)
                val sampleBody = obj.optString("sampleBody", searchText)
                val leftAnchor = obj.optString("leftAnchor", "")
                val rightAnchor = obj.optString("rightAnchor", "")
                val extractLength = obj.optInt("extractLength", 0)
                val notificationPrefix = obj.optString("notificationPrefix", "").trim()
                if (address.isEmpty() || searchText.isEmpty() || extractLength <= 0) continue
                rulesRepository.saveRule(
                    address = address,
                    applyToAllSenders = applyToAllSenders,
                    sampleBody = sampleBody,
                    searchText = searchText,
                    leftAnchor = leftAnchor,
                    rightAnchor = rightAnchor,
                    extractionMode = NotificationExtractionModes.INDEXED_SUBSTRING,
                    extractLength = extractLength,
                    notificationPrefix = notificationPrefix
                )
                imported++
            }
            refresh()
            Toast.makeText(this, getString(R.string.notification_rules_import_success, imported), Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.notification_rules_import_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun extractPreview(rule: NotificationExtractRule): String {
        val leftLen = rule.leftAnchor.length
        val rightLen = rule.rightAnchor.length
        val total = leftLen + rule.extractLength + rightLen
        if (total != rule.searchText.length || rule.extractLength <= 0) {
            return ""
        }
        return rule.searchText.substring(leftLen, leftLen + rule.extractLength)
    }

    private fun buildAddressLabels(): Map<String, String> {
        val labels = mutableMapOf<String, String>()
        conversationRepository.getConversationsSnapshot(unreadAtTop = false)
            .flatMap { it.recipients }
            .forEach { recipient ->
                val address = recipient.address.trim()
                if (address.isBlank() || labels.containsKey(address)) return@forEach
                val name = recipient.contact?.name?.trim().orEmpty()
                labels[address] = if (name.isNotBlank()) "$name ($address)" else address
            }
        return labels
    }

    private fun buildRows(rules: List<NotificationExtractRule>, addressLabels: Map<String, String>): List<Row> {
        val global = rules.filter { it.applyToAllSenders || it.address == GLOBAL_RULE_ADDRESS }.sortedBy { it.id }
        val senderGroups = rules
            .filter { !it.applyToAllSenders && it.address != GLOBAL_RULE_ADDRESS }
            .groupBy { it.address }
            .toSortedMap()

        val rows = mutableListOf<Row>()
        rows += Row.Section(getString(R.string.notification_rules_section_all))
        if (global.isEmpty()) {
            rows += Row.Empty(getString(R.string.notification_rules_empty_section))
        } else {
            rows += global.map { Row.RuleItem(it, getString(R.string.notification_rules_section_all)) }
        }
        senderGroups.forEach { (sender, senderRules) ->
            val label = addressLabels[sender] ?: sender
            rows += Row.Section(getString(R.string.notification_rules_section_sender, label))
            rows += senderRules.sortedBy { it.id }.map { Row.RuleItem(it, label) }
        }
        return rows
    }

    private sealed class Row {
        data class Section(val title: String) : Row()
        data class RuleItem(val rule: NotificationExtractRule, val addressLabel: String) : Row()
        data class Empty(val title: String) : Row()
    }

    private class RulesAdapter(
        private val onEdit: (NotificationExtractRule) -> Unit,
        private val onDelete: (NotificationExtractRule) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<Row> = emptyList()

        fun submit(values: List<Row>) {
            items = values
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is Row.Section -> 0
            is Row.RuleItem -> 1
            is Row.Empty -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                0 -> {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.notification_rule_section_row, parent, false)
                    SectionViewHolder(view)
                }
                2 -> {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.notification_rule_section_row, parent, false)
                    EmptyViewHolder(view)
                }
                else -> {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.notification_rule_row, parent, false)
                    RuleViewHolder(view)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is Row.Section -> (holder as SectionViewHolder).bind(row)
                is Row.Empty -> (holder as EmptyViewHolder).bind(row)
                is Row.RuleItem -> {
                    val rule = row.rule
                    (holder as RuleViewHolder).bind(rule, row.addressLabel)
                    holder.itemView.setOnClickListener { onEdit(rule) }
                    holder.itemView.setOnLongClickListener {
                        onDelete(rule)
                        true
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class SectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleView: TextView = view.findViewById(R.id.title)
        fun bind(section: Row.Section) {
            titleView.text = section.title
            titleView.alpha = 1f
        }
    }

    private class EmptyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleView: TextView = view.findViewById(R.id.title)
        fun bind(empty: Row.Empty) {
            titleView.text = empty.title
            titleView.alpha = 0.7f
        }
    }

    private class RuleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val addressView: TextView = view.findViewById(R.id.address)
        private val searchView: TextView = view.findViewById(R.id.search)
        private val extractView: TextView = view.findViewById(R.id.extract)

        fun bind(rule: NotificationExtractRule, addressLabel: String) {
            addressView.text = addressLabel
            searchView.text = rule.searchText
            extractView.text = if (rule.notificationPrefix.isBlank()) {
                "len=${rule.extractLength}"
            } else {
                "${rule.notificationPrefix} • len=${rule.extractLength}"
            }
        }
    }

    companion object {
        const val EXTRA_THREAD_ID = "threadId"
        private const val GLOBAL_RULE_ADDRESS = "*"
    }
}
