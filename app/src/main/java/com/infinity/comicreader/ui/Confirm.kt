package com.infinity.comicreader.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R

/**
 * Asks before a destructive Clear / Reset: [title] names it, [message] says what goes
 * and what stays, and [onConfirm] runs only on the [action] button.
 */
fun confirm(
    context: Context,
    @StringRes title: Int,
    message: CharSequence,
    @StringRes action: Int,
    onConfirm: () -> Unit
): AlertDialog = AlertDialog.Builder(context)
    .setTitle(title)
    .setMessage(message)
    .setPositiveButton(action) { _, _ -> onConfirm() }
    .setNegativeButton(R.string.cancel, null)
    .showKeepingFullscreen()

/** [confirm] with a message from resources. */
fun confirm(
    context: Context,
    @StringRes title: Int,
    @StringRes message: Int,
    @StringRes action: Int,
    onConfirm: () -> Unit
): AlertDialog = confirm(context, title, context.getText(message), action, onConfirm)

/** [confirm] with a title made in code, as one naming the item it acts on. */
fun confirm(
    context: Context,
    title: CharSequence,
    message: CharSequence,
    @StringRes action: Int,
    onConfirm: () -> Unit
): AlertDialog = AlertDialog.Builder(context)
    .setTitle(title)
    .setMessage(message)
    .setPositiveButton(action) { _, _ -> onConfirm() }
    .setNegativeButton(R.string.cancel, null)
    .showKeepingFullscreen()
