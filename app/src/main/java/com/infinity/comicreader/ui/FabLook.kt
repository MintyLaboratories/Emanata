package com.infinity.comicreader.ui

import com.google.android.material.R as M
import com.infinity.comicreader.R
import com.infinity.comicreader.files.FileWork

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
                M.attr.colorPrimaryContainer, M.attr.colorOnPrimaryContainer
            )
            SelectionActions.Fab.OPEN -> FabLook(R.drawable.ic_auto_stories, R.string.fab_open, M.attr.colorSecondaryContainer, M.attr.colorOnSecondaryContainer)
            SelectionActions.Fab.MOVE -> MOVE
            SelectionActions.Fab.COPY -> COPY
            SelectionActions.Fab.DELETE -> DELETE
            SelectionActions.Fab.SHARE -> FabLook(R.drawable.ic_share, R.string.file_op_share, M.attr.colorSurfaceContainerHighest, androidx.appcompat.R.attr.colorPrimary)
            SelectionActions.Fab.FAVORITE -> FabLook(
                if (unfavorite) R.drawable.ic_star else R.drawable.ic_star_border,
                if (unfavorite) R.string.menu_remove_favorite else R.string.menu_add_favorite,
                M.attr.colorSurfaceContainerHighest, androidx.appcompat.R.attr.colorPrimary
            )
        }

        /** The Paste button, never mistaken for an action's: the clipboard icon on the inverse surface. */
        val PASTE = FabLook(R.drawable.ic_content_paste, R.string.paste, M.attr.colorSurfaceInverse, M.attr.colorOnSurfaceInverse)

        /** The badge on Paste telling a move from a copy: that action's icon and colors. */
        fun pasteBadge(move: Boolean): FabLook = if (move) MOVE else COPY

        /** The bubble of an operation of [kind]: Move and Copy as their buttons, Delete on the error container, Compact, Make compatible and Pack each on its own. */
        fun of(kind: FileWork.Kind): FabLook = when (kind) {
            FileWork.Kind.COPY -> COPY
            FileWork.Kind.MOVE -> MOVE
            FileWork.Kind.DELETE -> DELETE
            FileWork.Kind.RENAME -> RENAME
            FileWork.Kind.COMPACT -> COMPACT
            FileWork.Kind.COMPATIBLE -> COMPATIBLE
            FileWork.Kind.PACK -> PACK
        }

        /** The color of the progress ring around a running bubble of [kind]: the action's own, strong over the list. */
        fun ringOf(kind: FileWork.Kind): Int = when (kind) {
            FileWork.Kind.COPY -> M.attr.colorSecondary
            FileWork.Kind.MOVE -> M.attr.colorTertiary
            FileWork.Kind.DELETE -> androidx.appcompat.R.attr.colorError
            FileWork.Kind.RENAME -> androidx.appcompat.R.attr.colorPrimary
            FileWork.Kind.COMPACT -> R.attr.colorOpCompactRing
            FileWork.Kind.COMPATIBLE -> R.attr.colorOpCompatibleRing
            FileWork.Kind.PACK -> R.attr.colorOpPackRing
        }

        /** A bubble waiting its turn: gray, whatever it will do. */
        val WAITING_BACKGROUND = M.attr.colorSurfaceContainerHighest
        val WAITING_FOREGROUND = M.attr.colorOutline

        private val MOVE = FabLook(R.drawable.ic_drive_file_move, R.string.file_op_move, M.attr.colorTertiaryContainer, M.attr.colorOnTertiaryContainer)
        private val COPY = FabLook(R.drawable.ic_content_copy, R.string.file_op_copy, M.attr.colorSecondary, M.attr.colorOnSecondary)
        private val DELETE = FabLook(R.drawable.ic_delete, R.string.delete, M.attr.colorErrorContainer, M.attr.colorOnErrorContainer)
        private val RENAME = FabLook(R.drawable.ic_edit, R.string.menu_rename, androidx.appcompat.R.attr.colorPrimary, M.attr.colorOnPrimary)
        // Roles of their own: Material's are taken by the floating button's actions.
        private val COMPACT = FabLook(R.drawable.ic_compress, R.string.more_compact, R.attr.colorOpCompact, R.attr.colorOnOpCompact)
        private val COMPATIBLE = FabLook(R.drawable.ic_unfold_more, R.string.more_make_compatible, R.attr.colorOpCompatible, R.attr.colorOnOpCompatible)
        private val PACK = FabLook(R.drawable.ic_archive, R.string.more_pack, R.attr.colorOpPack, R.attr.colorOnOpPack)
    }
}
