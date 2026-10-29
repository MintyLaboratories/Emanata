package com.infinity.comicreader.ui

import com.google.android.material.R as M
import com.infinity.comicreader.R

/**
 * The floating button's icon, name and colors (theme attributes) for each thing it does:
 * every action its own color from the theme's roles, so a theme restyles them all.
 */
internal data class FabLook(val icon: Int, val label: Int, val background: Int, val foreground: Int) {
    companion object {
        /** [fab] as it shows; the list/grid toggle names the view it switches to ([grid] now), Favorite what it does. */
        fun of(fab: SelectionActions.Fab, grid: Boolean, unfavorite: Boolean): FabLook = when (fab) {
            SelectionActions.Fab.GRID_LIST -> FabLook(
                if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view,
                if (grid) R.string.menu_list_view else R.string.menu_grid,
                M.attr.colorSecondaryContainer, M.attr.colorOnSecondaryContainer
            )
            SelectionActions.Fab.OPEN -> FabLook(R.drawable.ic_auto_stories, R.string.fab_open, M.attr.colorPrimaryContainer, M.attr.colorOnPrimaryContainer)
            SelectionActions.Fab.MOVE -> MOVE
            SelectionActions.Fab.COPY -> COPY
            SelectionActions.Fab.DELETE -> FabLook(R.drawable.ic_delete, R.string.delete, M.attr.colorErrorContainer, M.attr.colorOnErrorContainer)
            SelectionActions.Fab.SHARE -> FabLook(R.drawable.ic_share, R.string.file_op_share, M.attr.colorSurfaceContainerHighest, androidx.appcompat.R.attr.colorPrimary)
            SelectionActions.Fab.FAVORITE -> FabLook(
                if (unfavorite) R.drawable.ic_star else R.drawable.ic_star_border,
                if (unfavorite) R.string.menu_remove_favorite else R.string.menu_add_favorite,
                M.attr.colorSurfaceContainerHighest, androidx.appcompat.R.attr.colorPrimary
            )
        }

        /** The Paste button of a move, or of a copy: the colors of Move and Copy. */
        fun paste(move: Boolean): FabLook = (if (move) MOVE else COPY).copy(icon = R.drawable.ic_content_paste, label = R.string.paste)

        private val MOVE = FabLook(R.drawable.ic_drive_file_move, R.string.file_op_move, M.attr.colorTertiaryContainer, M.attr.colorOnTertiaryContainer)
        private val COPY = FabLook(R.drawable.ic_content_copy, R.string.file_op_copy, M.attr.colorSecondary, M.attr.colorOnSecondary)
    }
}
