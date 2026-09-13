package com.jambus.heji

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Keeps the file-library swipe rules independent from Android touch events so they can be
 * regression tested without an Activity or a device.
 */
object SwipeRowGesturePolicy {
  const val HORIZONTAL_AXIS_RATIO = 1.25f
  const val OPEN_THRESHOLD = 0.45f
  const val ANIMATION_DURATION_MS = 150L

  enum class Axis {
    UNDECIDED,
    HORIZONTAL,
    VERTICAL
  }

  enum class ReleaseAction {
    SETTLE,
    ACTIVATE_OR_CLOSE,
    IGNORE
  }

  fun axisAfterSlop(deltaX: Float, deltaY: Float, touchSlopPx: Float): Axis {
    val horizontal = abs(deltaX)
    val vertical = abs(deltaY)
    if (horizontal < touchSlopPx && vertical < touchSlopPx) return Axis.UNDECIDED
    return if (horizontal >= HORIZONTAL_AXIS_RATIO * vertical) Axis.HORIZONTAL else Axis.VERTICAL
  }

  fun clampTranslation(translationX: Float, actionsWidthPx: Int): Float =
    translationX.coerceIn(-actionsWidthPx.toFloat(), 0f)

  fun shouldOpen(
    translationX: Float,
    velocityX: Float,
    minimumFlingVelocityPxPerSecond: Float,
    actionsWidthPx: Int
  ): Boolean {
    if (abs(velocityX) >= minimumFlingVelocityPxPerSecond) return velocityX < 0f
    return abs(translationX) >= actionsWidthPx * OPEN_THRESHOLD
  }

  fun animationDuration(animationsEnabled: Boolean): Long =
    if (animationsEnabled) ANIMATION_DURATION_MS else 0L

  fun releaseAction(axis: Axis): ReleaseAction = when (axis) {
    Axis.HORIZONTAL -> ReleaseAction.SETTLE
    Axis.VERTICAL -> ReleaseAction.IGNORE
    Axis.UNDECIDED -> ReleaseAction.ACTIVATE_OR_CLOSE
  }

  data class VelocitySample(val x: Float, val y: Float)

  /** Velocity must follow the finger in screen coordinates, not a translated row's local space. */
  fun velocitySample(rawX: Float, rawY: Float): VelocitySample = VelocitySample(rawX, rawY)
}

/**
 * Coordinates the one short tap that closes an already open row. It never consumes the touch
 * stream, so a drag beginning with that touch remains available to the enclosing ScrollView.
 */
object SwipeDismissTouchPolicy {
  data class State(val downRawX: Float, val downRawY: Float, val movedPastSlop: Boolean = false)

  fun begin(rawX: Float, rawY: Float): State = State(rawX, rawY)

  fun onMove(state: State, rawX: Float, rawY: Float, touchSlopPx: Float): State {
    if (state.movedPastSlop) return state
    val moved = abs(rawX - state.downRawX) >= touchSlopPx ||
      abs(rawY - state.downRawY) >= touchSlopPx
    return state.copy(movedPastSlop = moved)
  }

  fun suppressActivation(state: State?): Boolean = state != null && !state.movedPastSlop

  fun shouldCancelTargetOnFinish(state: State?): Boolean = suppressActivation(state)
}

/**
 * A native, one-row action drawer. The owner is responsible for enforcing that only one row is
 * open; this class reports its requested open state before it starts its interruptible animation.
 */
class SwipeActionRow(
  context: Context,
  private val actionsWidthPx: Int,
  private val touchSlopPx: Int,
  private val animationsEnabled: () -> Boolean = {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
  }
) : FrameLayout(context) {
  private var foreground: View? = null
  private var actionStrip: View? = null
  private var animator: ValueAnimator? = null
  private var velocityTracker: VelocityTracker? = null
  private var downRawX = 0f
  private var downRawY = 0f
  private var dragStartTranslation = 0f
  private var axis = SwipeRowGesturePolicy.Axis.UNDECIDED
  private var longPressHandled = false
  private var open = false
  private var rowTitle = ""
  private var rowAccessibilityLabel = ""
  private var onActivate: (() -> Unit)? = null
  private var onLongPress: (() -> Unit)? = null
  private var onRename: (() -> Unit)? = null
  private var onMoveToTrash: (() -> Unit)? = null
  private var onOpenStateChanged: ((SwipeActionRow, Boolean) -> Unit)? = null
  private val renameActionId = View.generateViewId()
  private val trashActionId = View.generateViewId()
  private val longPressRunnable = Runnable {
    if (axis == SwipeRowGesturePolicy.Axis.UNDECIDED) {
      longPressHandled = true
      performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
      onLongPress?.invoke()
    }
  }

  fun bind(
    foreground: View,
    actionStrip: View,
    title: String,
    accessibilityLabel: String,
    onActivate: () -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
    onMoveToTrash: () -> Unit,
    onOpenStateChanged: (SwipeActionRow, Boolean) -> Unit
  ) {
    removeAllViews()
    this.foreground = foreground
    this.actionStrip = actionStrip
    this.rowTitle = title
    this.rowAccessibilityLabel = accessibilityLabel
    this.onActivate = onActivate
    this.onLongPress = onLongPress
    this.onRename = onRename
    this.onMoveToTrash = onMoveToTrash
    this.onOpenStateChanged = onOpenStateChanged
    actionStrip.visibility = View.INVISIBLE
    addView(actionStrip, LayoutParams(actionsWidthPx, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
    addView(foreground, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    foreground.setOnClickListener {
      if (open) close() else onActivate()
    }
    foreground.setOnTouchListener { _, event -> handleForegroundTouch(event) }
    foreground.setAccessibilityDelegate(accessibilityDelegate())
    updateAccessibilityState(announce = false)
  }

  fun isOpen(): Boolean = open

  /** Returns true only when the row changed from expanded to closed. */
  fun close(animated: Boolean = true): Boolean {
    if (!open && foreground?.translationX == 0f) return false
    settle(openTarget = false, animated = animated)
    return true
  }

  fun containsRawPoint(rawX: Float, rawY: Float): Boolean {
    val location = IntArray(2)
    getLocationOnScreen(location)
    return rawX >= location[0] && rawX <= location[0] + width &&
      rawY >= location[1] && rawY <= location[1] + height
  }

  private fun handleForegroundTouch(event: MotionEvent): Boolean {
    val content = foreground ?: return false
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        animator?.cancel()
        downRawX = event.rawX
        downRawY = event.rawY
        dragStartTranslation = content.translationX
        axis = SwipeRowGesturePolicy.Axis.UNDECIDED
        longPressHandled = false
        velocityTracker = VelocityTracker.obtain().also { addVelocityMovement(it, event) }
        content.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        velocityTracker?.let { addVelocityMovement(it, event) }
        val deltaX = event.rawX - downRawX
        val deltaY = event.rawY - downRawY
        if (axis == SwipeRowGesturePolicy.Axis.UNDECIDED) {
          axis = SwipeRowGesturePolicy.axisAfterSlop(deltaX, deltaY, touchSlopPx.toFloat())
          if (axis != SwipeRowGesturePolicy.Axis.UNDECIDED) content.removeCallbacks(longPressRunnable)
          if (axis == SwipeRowGesturePolicy.Axis.HORIZONTAL) {
            parent?.requestDisallowInterceptTouchEvent(true)
          }
        }
        if (axis == SwipeRowGesturePolicy.Axis.HORIZONTAL) {
          showActionStrip()
          content.translationX = SwipeRowGesturePolicy.clampTranslation(
            dragStartTranslation + deltaX,
            actionsWidthPx
          )
          return true
        }
        if (axis == SwipeRowGesturePolicy.Axis.VERTICAL) {
          parent?.requestDisallowInterceptTouchEvent(false)
          recycleVelocityTracker()
          return false
        }
        return true
      }
      MotionEvent.ACTION_UP -> {
        content.removeCallbacks(longPressRunnable)
        velocityTracker?.let { addVelocityMovement(it, event) }
        when (SwipeRowGesturePolicy.releaseAction(axis)) {
          SwipeRowGesturePolicy.ReleaseAction.SETTLE -> {
            velocityTracker?.computeCurrentVelocity(1000)
            val velocityX = velocityTracker?.xVelocity ?: 0f
            val minimumVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
            val shouldOpen = SwipeRowGesturePolicy.shouldOpen(
              content.translationX,
              velocityX,
              minimumVelocity,
              actionsWidthPx
            )
            recycleVelocityTracker()
            settle(shouldOpen, animated = true)
            return true
          }
          SwipeRowGesturePolicy.ReleaseAction.IGNORE -> {
            recycleVelocityTracker()
            return false
          }
          SwipeRowGesturePolicy.ReleaseAction.ACTIVATE_OR_CLOSE -> Unit
        }
        recycleVelocityTracker()
        if (longPressHandled) return true
        if (open) {
          close()
        } else {
          content.performClick()
        }
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        content.removeCallbacks(longPressRunnable)
        recycleVelocityTracker()
        if (SwipeRowGesturePolicy.releaseAction(axis) == SwipeRowGesturePolicy.ReleaseAction.IGNORE) {
          return false
        }
        settle(openTarget = false, animated = false)
        return true
      }
    }
    return true
  }

  private fun settle(openTarget: Boolean, animated: Boolean) {
    val content = foreground ?: return
    if (openTarget) showActionStrip()
    val wasOpen = open
    open = openTarget
    if (wasOpen != openTarget) {
      onOpenStateChanged?.invoke(this, openTarget)
      updateAccessibilityState(announce = openTarget)
    }
    val target = if (openTarget) -actionsWidthPx.toFloat() else 0f
    animator?.cancel()
    val duration = if (animated) SwipeRowGesturePolicy.animationDuration(animationsEnabled()) else 0L
    if (duration == 0L || content.translationX == target) {
      content.translationX = target
      if (!openTarget) hideActionStrip()
      return
    }
    animator = ValueAnimator.ofFloat(content.translationX, target).apply {
      this.duration = duration
      addUpdateListener { content.translationX = it.animatedValue as Float }
      addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
          if (!open) hideActionStrip()
        }
      })
      start()
    }
  }

  private fun showActionStrip() {
    actionStrip?.visibility = View.VISIBLE
  }

  private fun hideActionStrip() {
    actionStrip?.visibility = View.INVISIBLE
  }

  private fun updateAccessibilityState(announce: Boolean) {
    val content = foreground ?: return
    actionStrip?.importantForAccessibility = if (open) {
      View.IMPORTANT_FOR_ACCESSIBILITY_YES
    } else {
      View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    val state = UiText.label(context, if (open) "操作已展开" else "操作已收起")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) content.stateDescription = state
    content.contentDescription = "$rowAccessibilityLabel，$state"
    if (announce) announceForAccessibility(
      if (UiLanguage.locale(context).language == "zh") "$rowTitle，已显示重命名和移到回收站操作" else "$rowTitle, rename and move-to-Trash actions shown"
    )
  }

  private fun accessibilityDelegate(): View.AccessibilityDelegate = object : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
      super.onInitializeAccessibilityNodeInfo(host, info)
      val english = UiLanguage.locale(context).language != "zh"
      info.addAction(AccessibilityNodeInfo.AccessibilityAction(renameActionId, if (english) "Rename $rowTitle" else "重命名 $rowTitle"))
      info.addAction(AccessibilityNodeInfo.AccessibilityAction(trashActionId, if (english) "Move $rowTitle to Trash" else "移到回收站 $rowTitle"))
    }

    override fun performAccessibilityAction(host: View, action: Int, arguments: android.os.Bundle?): Boolean {
      return when (action) {
        renameActionId -> {
          close(animated = false)
          onRename?.invoke()
          true
        }
        trashActionId -> {
          close(animated = false)
          onMoveToTrash?.invoke()
          true
        }
        else -> super.performAccessibilityAction(host, action, arguments)
      }
    }
  }

  private fun recycleVelocityTracker() {
    velocityTracker?.recycle()
    velocityTracker = null
  }

  private fun addVelocityMovement(tracker: VelocityTracker, event: MotionEvent) {
    val sample = SwipeRowGesturePolicy.velocitySample(event.rawX, event.rawY)
    val rawEvent = MotionEvent.obtain(event)
    rawEvent.setLocation(sample.x, sample.y)
    tracker.addMovement(rawEvent)
    rawEvent.recycle()
  }
}
