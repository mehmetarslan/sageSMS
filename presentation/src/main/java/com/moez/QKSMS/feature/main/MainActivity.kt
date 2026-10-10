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
package dev.octoshrimpy.quik.feature.main

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewStub
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.model.ConversationCategory
import dev.octoshrimpy.quik.common.androidxcompat.drawerOpen
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.common.util.extensions.autoScrollToStart
import dev.octoshrimpy.quik.common.util.extensions.dismissKeyboard
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.common.util.extensions.scrapViews
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.common.util.extensions.setVisible
import dev.octoshrimpy.quik.common.widget.TextInputDialog
import dev.octoshrimpy.quik.feature.blocking.BlockingDialog
import dev.octoshrimpy.quik.databinding.MainActivityBinding
import dev.octoshrimpy.quik.databinding.MainPermissionHintBinding
import dev.octoshrimpy.quik.databinding.MainSyncingBinding
import dev.octoshrimpy.quik.feature.changelog.ChangelogDialog
import dev.octoshrimpy.quik.feature.conversations.ConversationItemTouchCallback
import dev.octoshrimpy.quik.feature.conversations.ConversationsAdapter
import dev.octoshrimpy.quik.manager.ChangelogManager
import dev.octoshrimpy.quik.repository.SyncRepository
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class MainActivity : QkThemedActivity(), MainView {

    @Inject lateinit var blockingDialog: BlockingDialog
    @Inject lateinit var disposables: CompositeDisposable
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var conversationsAdapter: ConversationsAdapter
    @Inject lateinit var drawerBadgesExperiment: DrawerBadgesExperiment
    @Inject lateinit var searchAdapter: SearchAdapter
    @Inject lateinit var itemTouchCallback: ConversationItemTouchCallback
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var binding: MainActivityBinding
    private lateinit var snackbarBinding: MainPermissionHintBinding
    private lateinit var syncingBinding: MainSyncingBinding

    override val onNewIntentIntent: Subject<Intent> = PublishSubject.create()
    override val activityResumedIntent: Subject<Boolean> = PublishSubject.create()
    override val queryChangedIntent by lazy { binding.toolbarSearch.textChanges() }
    override val composeIntent by lazy { binding.compose.clicks() }
    override val drawerToggledIntent: Observable<Boolean> by lazy {
        binding.drawerLayout.drawerOpen(Gravity.START)
    }
    override val homeIntent: Subject<Unit> = PublishSubject.create()
    override val navigationIntent: Observable<NavItem> by lazy {
        Observable.merge(listOf(
                backPressedSubject,
                binding.drawer.inbox.clicks().map { NavItem.INBOX },
                binding.drawer.archived.clicks().map { NavItem.ARCHIVED },
                binding.drawer.backup.clicks().map { NavItem.BACKUP },
                binding.drawer.scheduled.clicks().map { NavItem.SCHEDULED },
                binding.drawer.blocking.clicks().map { NavItem.BLOCKING },
                binding.drawer.messageUtils.clicks().map { NavItem.MESSAGE_UTILS },
                binding.drawer.notificationRules.clicks().map { NavItem.NOTIFICATION_RULES },
                binding.drawer.settings.clicks().map { NavItem.SETTINGS },
                binding.drawer.about.clicks().map { NavItem.ABOUT },
//                plus.clicks().map { NavItem.PLUS },
//                help.clicks().map { NavItem.HELP },
                binding.drawer.invite.clicks().map { NavItem.INVITE }))
    }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
//    override val plusBannerIntent by lazy { plusBanner.clicks() }
    override val dismissRatingIntent by lazy { binding.drawer.rateDismiss.clicks() }
    override val rateIntent by lazy { binding.drawer.rateOkay.clicks() }
    override val conversationsSelectedIntent by lazy { conversationsAdapter.selectionChanges }
    override val confirmDeleteIntent: Subject<List<Long>> = PublishSubject.create()
    override val renameConversationIntent: Subject<String> = PublishSubject.create()
    override val swipeConversationIntent by lazy { itemTouchCallback.swipes }
    override val changelogMoreIntent by lazy { changelogDialog.moreClicks }
    override val undoArchiveIntent: Subject<Unit> = PublishSubject.create()
    override val snackbarButtonIntent: Subject<Unit> = PublishSubject.create()
    override val categorySelectedIntent: Subject<Long> = PublishSubject.create()
    override val categoryAddIntent: Subject<String> = PublishSubject.create()
    override val categoryRenameIntent: Subject<Pair<Long, String>> = PublishSubject.create()
    override val categoryDeleteIntent: Subject<Long> = PublishSubject.create()
    override val moveToCategoryIntent: Subject<Long> = PublishSubject.create()
    override val moveToNewCategoryIntent: Subject<String> = PublishSubject.create()

    /** What the category bar currently shows, so it is only rebuilt when that changes. */
    private var renderedCategories: Pair<List<Pair<Long, String>>, Long>? = null

    private val viewModel by lazy {
        ViewModelProviders.of(this, viewModelFactory)[MainViewModel::class.java]
    }
    private val toggle by lazy {
        ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.main_drawer_open_cd,
            0
        )
    }
    private val itemTouchHelper by lazy { ItemTouchHelper(itemTouchCallback) }
    private val progressAnimator by lazy {
        ObjectAnimator.ofInt(syncingBinding.syncingProgress, "progress", 0, 0)
    }
    private val changelogDialog by lazy { ChangelogDialog(this) }
    private val backPressedSubject: Subject<NavItem> = PublishSubject.create()

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel.bindView(this)
        onNewIntentIntent.onNext(intent)

        snackbarBinding = MainPermissionHintBinding.bind(binding.snackbar.inflate()).also {
            it.snackbarButton.clicks()
                .autoDisposable(scope(Lifecycle.Event.ON_DESTROY))
                .subscribe(snackbarButtonIntent)
        }

        syncingBinding = MainSyncingBinding.bind(binding.syncing.inflate()).also {
            it.syncingProgress.progressTintList = ColorStateList.valueOf(theme.blockingFirst().theme)
            it.syncingProgress.indeterminateTintList = ColorStateList.valueOf(theme.blockingFirst().theme)
        }

        toggle.syncState()
        binding.toolbar.setNavigationOnClickListener {
            dismissKeyboard()
            homeIntent.onNext(Unit)
        }

        itemTouchCallback.adapter = conversationsAdapter
        conversationsAdapter.autoScrollToStart(binding.recyclerView)

        // Don't allow clicks to pass through the drawer layout
        binding.drawer.root.clicks().autoDisposable(scope()).subscribe()

        // Set the theme color tint to the recyclerView, progressbar, and FAB
        theme
                .autoDisposable(scope())
                .subscribe { theme ->
                    // Set the color for the drawer icons
                    val states = arrayOf(
                            intArrayOf(android.R.attr.state_activated),
                            intArrayOf(-android.R.attr.state_activated))

                    ColorStateList(states, intArrayOf(theme.theme,
                        resolveThemeColor(android.R.attr.textColorSecondary)
                    ))
                        .let { tintList ->
                            binding.drawer.inboxIcon.imageTintList = tintList
                            binding.drawer.archivedIcon.imageTintList = tintList
                        }

                    // Miscellaneous views
                    listOf(binding.drawer.plusBadge1, binding.drawer.plusBadge2).forEach { badge ->
                        badge.setBackgroundTint(theme.theme)
                        badge.setTextColor(theme.textPrimary)
                    }
                    syncingBinding.syncingProgress.progressTintList = ColorStateList.valueOf(theme.theme)
                    syncingBinding.syncingProgress.indeterminateTintList = ColorStateList.valueOf(theme.theme)
                    binding.drawer.plusIcon.setTint(theme.theme)
                    binding.drawer.rateIcon.setTint(theme.theme)
                    binding.compose.setBackgroundTint(theme.theme)

                    // Set the FAB compose icon color
                    binding.compose.setTint(theme.textPrimary)
                }
    }

    override fun onNewIntent(intent: Intent?) =
        intent?.let {
            super.onNewIntent(intent)
            it.run(onNewIntentIntent::onNext)
        } ?: Unit

    override fun render(state: MainState) {
        if (state.hasError) {
            finish()
            return
        }

        val addContact = when (state.page) {
            is Inbox -> state.page.addContact
            is Archived -> state.page.addContact
            else -> false
        }

        val markPinned = when (state.page) {
            is Inbox -> state.page.markPinned
            is Archived -> state.page.markPinned
            else -> true
        }

        val markRead = when (state.page) {
            is Inbox -> state.page.markRead
            is Archived -> state.page.markRead
            else -> true
        }

        val selectedConversations = when (state.page) {
            is Inbox -> state.page.selected
            is Archived -> state.page.selected
            else -> 0
        }

        binding.toolbarSearch.setVisible(state.page is Inbox &&
                state.page.selected == 0 ||
                state.page is Searching
        )
        binding.toolbarTitle.setVisible(binding.toolbarSearch.visibility != View.VISIBLE)

        binding.toolbar.menu.apply {
            findItem(R.id.select_all)?.isVisible =
                (conversationsAdapter.itemCount > 1) && selectedConversations != 0
            findItem(R.id.archive)?.isVisible =
                state.page is Inbox && selectedConversations != 0
            findItem(R.id.unarchive)?.isVisible =
                state.page is Archived && selectedConversations != 0
            findItem(R.id.delete)?.isVisible = selectedConversations != 0
            findItem(R.id.add)?.isVisible = addContact && selectedConversations != 0
            findItem(R.id.pin)?.isVisible = markPinned && selectedConversations != 0
            findItem(R.id.unpin)?.isVisible = !markPinned && selectedConversations != 0
            findItem(R.id.read)?.isVisible = ( markRead && selectedConversations != 0 ) ||
                    selectedConversations > 1
            findItem(R.id.unread)?.isVisible = ( !markRead && selectedConversations != 0 ) ||
                    selectedConversations > 1
            findItem(R.id.block)?.isVisible = selectedConversations != 0
            findItem(R.id.rename)?.isVisible = selectedConversations == 1
            findItem(R.id.moveToCategory)?.isVisible =
                state.showCategories && state.page is Inbox && selectedConversations != 0
        }

        binding.categoriesBar.isVisible = state.showCategories && state.page is Inbox
        if (binding.categoriesBar.isVisible) renderCategories(state)

        listOf(binding.drawer.plusBadge1, binding.drawer.plusBadge2).forEach { badge ->
            badge.isVisible = drawerBadgesExperiment.variant && !state.upgraded
        }
//        plus.isVisible = state.upgraded
        binding.drawer.plusBanner.isVisible = !state.upgraded
        binding.drawer.rateLayout.setVisible(state.showRating)

        binding.compose.setVisible(state.page is Inbox || state.page is Archived)
        conversationsAdapter.emptyView = binding.empty.takeIf {
            state.page is Inbox || state.page is Archived
        }
        searchAdapter.emptyView = binding.empty.takeIf { state.page is Searching }

        when (state.page) {
            is Inbox -> {
                showBackButton(state.page.selected > 0)
                title = getString(R.string.main_title_selected, state.page.selected)
                if (binding.recyclerView.adapter !== conversationsAdapter)
                    binding.recyclerView.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(binding.recyclerView)
                binding.empty.setText(when {
                    state.showCategories && state.selectedCategoryId != ALL_CATEGORIES -> R.string.category_empty
                    else -> R.string.inbox_empty_text
                })
            }

            is Searching -> {
                showBackButton(true)
                if (binding.recyclerView.adapter !== searchAdapter) binding.recyclerView.adapter = searchAdapter
                searchAdapter.data = state.page.data ?: listOf()
                itemTouchHelper.attachToRecyclerView(null)
                binding.empty.setText(R.string.inbox_search_empty_text)
            }

            is Archived -> {
                showBackButton(state.page.selected > 0)
                title = when (state.page.selected != 0) {
                    true -> getString(R.string.main_title_selected, state.page.selected)
                    false -> getString(R.string.title_archived)
                }
                if (binding.recyclerView.adapter !== conversationsAdapter)
                    binding.recyclerView.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(null)
                binding.empty.setText(R.string.archived_empty_text)
            }

            else -> {}
        }

        binding.drawer.inbox.isActivated = state.page is Inbox
        binding.drawer.archived.isActivated = state.page is Archived

        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START) && !state.drawerOpen)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        else if (!binding.drawerLayout.isDrawerVisible(GravityCompat.START) && state.drawerOpen)
            binding.drawerLayout.openDrawer(GravityCompat.START)

        when (state.syncing) {
            is SyncRepository.SyncProgress.Idle -> {
                syncingBinding.root.isVisible = false
                snackbarBinding.root.isVisible = (!state.defaultSms ||
                        !state.smsPermission ||
                        !state.contactPermission ||
                        !state.notificationPermission)
            }

            is SyncRepository.SyncProgress.Running -> {
                syncingBinding.root.isVisible = true
                syncingBinding.syncingProgress.max = state.syncing.max
                progressAnimator.apply {
                    setIntValues(syncingBinding.syncingProgress.progress, state.syncing.progress)
                }.start()
                syncingBinding.syncingProgress.isIndeterminate = state.syncing.indeterminate
                snackbarBinding.root.isVisible = false
            }

            is SyncRepository.SyncProgress.ParsingEmojis -> {
                syncingBinding.root.isVisible = true
                syncingBinding.syncingLabel.setText(getString(R.string.main_sync_emojis))
                syncingBinding.syncingProgress.max = state.syncing.max
                progressAnimator.apply {
                    setIntValues(syncingBinding.syncingProgress.progress, state.syncing.progress)
                }.start()
                syncingBinding.syncingProgress.isIndeterminate = state.syncing.indeterminate
                snackbarBinding.root.isVisible = false
            }
        }

        when {
            !state.defaultSms -> {
                snackbarBinding.snackbarTitle.setText(R.string.main_default_sms_title)
                snackbarBinding.snackbarMessage.setText(R.string.main_default_sms_message)
                snackbarBinding.snackbarButton.setText(R.string.main_default_sms_change)
            }

            !state.smsPermission -> {
                snackbarBinding.snackbarTitle.setText(R.string.main_permission_required)
                snackbarBinding.snackbarMessage.setText(R.string.main_permission_sms)
                snackbarBinding.snackbarButton.setText(R.string.main_permission_allow)
            }

            !state.contactPermission -> {
                snackbarBinding.snackbarTitle.setText(R.string.main_permission_required)
                snackbarBinding.snackbarMessage.setText(R.string.main_permission_contacts)
                snackbarBinding.snackbarButton.setText(R.string.main_permission_allow)
            }

            !state.notificationPermission -> {
                snackbarBinding.snackbarTitle.setText(R.string.main_permission_required)
                snackbarBinding.snackbarMessage.setText(R.string.main_permission_notifications)
                snackbarBinding.snackbarButton.setText(R.string.main_permission_allow)
            }
        }
    }

    private fun renderCategories(state: MainState) {
        val key = state.categories.map { it.id to it.name } to state.selectedCategoryId
        if (key == renderedCategories) return
        renderedCategories = key

        binding.categoriesContainer.removeAllViews()
        binding.categoriesContainer.addView(
            categoryChip(getString(R.string.category_all), state.selectedCategoryId == ALL_CATEGORIES) {
                categorySelectedIntent.onNext(ALL_CATEGORIES)
            }
        )
        state.categories.forEach { category ->
            binding.categoriesContainer.addView(
                categoryChip(
                    category.name,
                    state.selectedCategoryId == category.id,
                    onLongClick = { showCategoryActions(category) }
                ) { categorySelectedIntent.onNext(category.id) }
            )
        }
        binding.categoriesContainer.addView(
            categoryChip("+", selected = false) {
                showCategoryNameDialog(R.string.category_add, "") { name -> categoryAddIntent.onNext(name) }
            }.apply { contentDescription = getString(R.string.category_add) }
        )
    }

    /** A pill-shaped tab; the selected one is filled with the theme colour. */
    private fun categoryChip(
        label: String,
        selected: Boolean,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): TextView {
        val dp = resources.displayMetrics.density
        val theme = colors.theme()
        return TextView(this).apply {
            text = label
            textSize = 14f
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
            setTextColor(if (selected) theme.textPrimary else resolveThemeColor(android.R.attr.textColorPrimary))
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                if (selected) {
                    setColor(theme.theme)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(maxOf(1, dp.toInt()), resolveThemeColor(android.R.attr.textColorTertiary))
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * dp).toInt() }
            setOnClickListener { onClick() }
            onLongClick?.let { callback -> setOnLongClickListener { callback(); true } }
        }
    }

    private fun showCategoryActions(category: ConversationCategory) {
        AlertDialog.Builder(this)
            .setTitle(category.name)
            .setItems(arrayOf(getString(R.string.category_rename), getString(R.string.button_delete))) { _, which ->
                when (which) {
                    0 -> showCategoryNameDialog(R.string.category_rename, category.name) { name ->
                        categoryRenameIntent.onNext(category.id to name)
                    }
                    1 -> AlertDialog.Builder(this)
                        .setMessage(getString(R.string.category_delete_confirm, category.name))
                        .setPositiveButton(R.string.button_delete) { _, _ -> categoryDeleteIntent.onNext(category.id) }
                        .setNegativeButton(R.string.button_cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun showCategoryNameDialog(@StringRes title: Int, initial: String, onSave: (String) -> Unit) {
        val dp = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = getString(R.string.category_name_hint)
            setSingleLine()
            setText(initial)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.button_save) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onSave)
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showCategoryPicker(categories: List<ConversationCategory>) {
        val labels = categories.map { it.name } +
                getString(R.string.category_remove) +
                getString(R.string.category_new)
        AlertDialog.Builder(this)
            .setTitle(R.string.main_menu_category)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    in categories.indices -> moveToCategoryIntent.onNext(categories[which].id)
                    categories.size -> moveToCategoryIntent.onNext(UNCATEGORIZE)
                    else -> showCategoryNameDialog(R.string.category_new, "") { name ->
                        moveToNewCategoryIntent.onNext(name)
                    }
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showCategoryMoved(name: String?) {
        val message = name?.let { getString(R.string.category_moved, it) } ?: getString(R.string.category_removed)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() =
        super.onResume().also { activityResumedIntent.onNext(true) }

    override fun onPause() =
        super.onPause().also { activityResumedIntent.onNext(false) }

    override fun onDestroy() =
        super.onDestroy().also { disposables.dispose() }

    override fun showBackButton(show: Boolean) =
        toggle.let {
            it.onDrawerSlide(binding.drawer.root, if (show) 1f else 0f)
            it.drawerArrowDrawable.color = when (show) {
                true -> resolveThemeColor(android.R.attr.textColorSecondary)
                false -> resolveThemeColor(android.R.attr.textColorPrimary)
            }
        }

    override fun requestDefaultSms() =
        navigator.showDefaultSmsDialog(this)

    override fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            permissions += Manifest.permission.POST_NOTIFICATIONS

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 0)
    }

    override fun clearSearch() {
        dismissKeyboard()
        binding.toolbarSearch.text = null
    }

    override fun clearSelection() = conversationsAdapter.clearSelection()

    override fun toggleSelectAll() = conversationsAdapter.toggleSelectAll()

    override fun themeChanged() = binding.recyclerView.scrapViews()

    override fun showBlockingDialog(conversations: List<Long>, block: Boolean) {
        blockingDialog.show(this, conversations, block)
    }

    override fun showDeleteDialog(conversations: List<Long>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.dialog_delete_message,
                    conversations.size,
                    conversations.size
                )
            )
            .setPositiveButton(R.string.button_delete) { _, _ -> confirmDeleteIntent.onNext(conversations) }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showRenameDialog(conversationName: String) =
        TextInputDialog(
            this,
            getString(R.string.info_name),
            renameConversationIntent::onNext
        )
            .setText(conversationName)
            .show()

    override fun showChangelog(changelog: ChangelogManager.CumulativeChangelog) =
        changelogDialog.show(changelog)

    override fun showArchivedSnackbar(countConversationsArchived: Int, isArchiving: Boolean) =
        Snackbar.make(
            binding.drawerLayout,
            if (isArchiving) {
                resources.getQuantityString(R.plurals.toast_archived, countConversationsArchived, countConversationsArchived)
            } else {
                resources.getQuantityString(R.plurals.toast_unarchived, countConversationsArchived, countConversationsArchived)
            },
            if (countConversationsArchived < 10) Snackbar.LENGTH_LONG
            else Snackbar.LENGTH_INDEFINITE
        ).let {
            it.setAction(R.string.button_undo) { undoArchiveIntent.onNext(Unit) }
            it.setActionTextColor(colors.theme().theme)
            it.show()
        }

    override fun onCreateOptionsMenu(menu: Menu?) =
        menu?.let {
            menuInflater.inflate(R.menu.main, it)
            super.onCreateOptionsMenu(it)
        } ?: false

    override fun onOptionsItemSelected(item: MenuItem) =
        optionsItemIntent.onNext(item.itemId).let { true }

    override fun onBackPressed() = backPressedSubject.onNext(NavItem.BACK)

    override fun drawerToggled(opened: Boolean) {
        if (opened) {
            dismissKeyboard()
            if (!binding.drawer.inbox.isInTouchMode)
                binding.drawer.inbox.requestFocus()
        } else
            binding.toolbarSearch.requestFocus()
    }
}
