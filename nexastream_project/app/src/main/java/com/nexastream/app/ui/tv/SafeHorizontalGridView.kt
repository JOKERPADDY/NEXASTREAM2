package com.nexastream.app.ui.tv

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.leanback.widget.HorizontalGridView

class SafeHorizontalGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : HorizontalGridView(context, attrs, defStyle) {

    override fun onTouchEvent(e: MotionEvent): Boolean {
        return try {
            val handled = super.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP) {
                performClick()
            }
            handled
        } catch (_: IllegalStateException) {
            false
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.dispatchTouchEvent(ev)
        } catch (_: IllegalStateException) {
            false
        }
    }
}
