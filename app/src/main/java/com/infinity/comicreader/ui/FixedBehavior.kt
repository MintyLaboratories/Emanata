package com.infinity.comicreader.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout

/**
 * Keeps a view where its layout puts it: in place of a floating button's own behavior,
 * which moves it out of a snackbar's way.
 */
class FixedBehavior(context: Context, attrs: AttributeSet?) : CoordinatorLayout.Behavior<View>(context, attrs)
