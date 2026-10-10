package dev.octoshrimpy.quik.common.util.extensions

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.annotation.StringRes
import dev.octoshrimpy.quik.R

/**
 * Adds an item to the floating Copy / Share menu that appears while text is selected in this view.
 * [onSelected] receives the selection bounds in the view's text; the menu closes afterwards.
 */
fun TextView.addSelectionAction(@StringRes title: Int, onSelected: (start: Int, end: Int) -> Unit) {
    customSelectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, R.id.selection_action_custom, 0, title)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != R.id.selection_action_custom) return false
            onSelected(selectionStart, selectionEnd)
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }
}
