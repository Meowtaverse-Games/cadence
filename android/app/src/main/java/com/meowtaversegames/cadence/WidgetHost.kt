package com.meowtaversegames.cadence

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import android.widget.RemoteViews
import kotlin.math.abs

const val CADENCE_WIDGET_HOST_ID = 1

class CadenceWidgetHost(context: Context, private val changed: () -> Unit, private val log: () -> Unit) :
    AppWidgetHost(context, CADENCE_WIDGET_HOST_ID) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo): AppWidgetHostView =
        CadenceWidgetView(context, log)
    override fun onProvidersChanged() { changed() }
}

/** Child clicks work normally. Vertical drags always remain available to the home LazyColumn. */
class CadenceWidgetView(context: Context, private val log: () -> Unit) : AppWidgetHostView(context) {
    var onHold: () -> Unit = {}
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var held = false
    private var tracking = false
    private val hold = Runnable {
        if (tracking && isAttachedToWindow) {
            held = true
            tracking = false
            val cancel = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL, downX, downY, 0)
            // Dispatch through ViewGroup to cancel whichever RemoteViews child owns the gesture.
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onHold()
        }
    }
    private fun trackTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = false; tracking = true; downX = event.x; downY = event.y
                removeCallbacks(hold)
                postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                tracking = false; removeCallbacks(hold)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tracking = false; removeCallbacks(hold) }
        }
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        trackTouch(event)
        return held
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Once an empty area owns the stream, ViewGroup may skip onInterceptTouchEvent.
        // Its UP/MOVE must still cancel the timer, just like events aimed at a child.
        if (event.actionMasked != MotionEvent.ACTION_DOWN) trackTouch(event)
        return true
    }
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // RemoteViews ListViews may request this on DOWN, before slop is crossed.
        super.requestDisallowInterceptTouchEvent(false)
    }
    override fun onDetachedFromWindow() {
        tracking = false; removeCallbacks(hold)
        super.onDetachedFromWindow()
    }
    override fun getErrorView(): View {
        log()
        return TextView(context).apply { text = "読み込めません"; setTextColor(android.graphics.Color.GRAY) }
    }
    override fun updateAppWidget(remoteViews: RemoteViews?) {
        try { super.updateAppWidget(remoteViews) }
        catch (_: RuntimeException) { showFailure() }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        try { super.onLayout(changed, left, top, right, bottom) }
        catch (_: RuntimeException) { post { showFailure() } }
    }
    private fun showFailure() {
        removeAllViews()
        addView(errorView)
    }
    private var reportedSize: Pair<Float, Float>? = null
    fun reportSize(width: Float, height: Float) {
        if (width <= 1f || reportedSize == (width to height)) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) updateAppWidgetSize(Bundle(), listOf(android.util.SizeF(width, height)))
            else {
                @Suppress("DEPRECATION")
                updateAppWidgetSize(Bundle(), width.toInt(), height.toInt(), width.toInt(), height.toInt())
            }
            reportedSize = width to height
        } catch (_: RuntimeException) { showFailure() }
    }
}
