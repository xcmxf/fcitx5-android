/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.annotation.CallSuper
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.swipe.SwipeKey
import org.fcitx.fcitx5.android.input.swipe.SwipeLayout
import org.fcitx.fcitx5.android.input.swipe.SwipePoint
import org.fcitx.fcitx5.android.input.swipe.SwipeRecognitionRequest
import org.fcitx.fcitx5.android.input.swipe.SwipeTypingDecoder
import org.fcitx.fcitx5.android.input.swipe.SwipeTypingDecoders
import org.fcitx.fcitx5.android.input.swipe.SwipeTypingMode
import org.fcitx.fcitx5.android.utils.alpha
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import timber.log.Timber
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.roundToInt

abstract class BaseKeyboard(
    context: Context,
    protected val theme: Theme,
    private val keyLayout: List<List<KeyDef>>
) : ConstraintLayout(context) {

    var keyActionListener: KeyActionListener? = null

    private val prefs = AppPrefs.getInstance()

    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress
    private val expandKeypressArea by prefs.keyboard.expandKeypressArea
    private val swipeSymbolDirection by prefs.keyboard.swipeSymbolDirection
    private val swipeTyping by prefs.keyboard.swipeTyping

    private val spaceSwipeMoveCursor = prefs.keyboard.spaceSwipeMoveCursor
    private val spaceKeys = mutableListOf<KeyView>()
    private val spaceSwipeChangeListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        spaceKeys.forEach {
            it.swipeEnabled = v
        }
    }

    private val vivoKeypressWorkaround by prefs.advanced.vivoKeypressWorkaround

    private val hapticOnRepeat by prefs.keyboard.hapticOnRepeat

    var popupActionListener: PopupActionListener? = null

    private val selectionSwipeThreshold = dp(10f)
    private val inputSwipeThreshold = dp(36f)
    private val swipeVisualPointMinDistance = dp(4f)

    // a rather large threshold effectively disables swipe of the direction
    private val disabledSwipeThreshold = dp(800f)

    private val bounds = Rect()
    private val keyRows: List<ConstraintLayout>
    private val swipeKeyLabels = hashMapOf<View, String>()
    private var swipeDecoder: SwipeTypingDecoder? = null
    private var swipeDecoderPinyinMode: Boolean? = null
    private var currentInputMethod: InputMethodEntry? = null
    private var swipePointerId = MotionEvent.INVALID_POINTER_ID
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartTime = 0L
    private var swipeTracking = false
    private var swipeIntercepted = false
    private val swipePoints = mutableListOf<SwipePoint>()
    private val swipeVisualPoints = mutableListOf<Pair<Float, Float>>()
    private val swipeTrace = StringBuilder()
    private val swipeTrailPath = Path()
    private val swipeTrailHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(13f)
        color = theme.genericActiveBackgroundColor.alpha(0.18f)
    }
    private val swipeTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(9f)
        color = theme.genericActiveBackgroundColor.alpha(0.44f)
    }
    private val swipeDotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = theme.genericActiveBackgroundColor.alpha(0.22f)
    }
    private val swipeDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = theme.genericActiveBackgroundColor.alpha(0.92f)
    }

    /**
     * HashMap of [PointerId (Int)][MotionEvent.getPointerId] to [KeyView]
     */
    private val touchTarget = hashMapOf<Int, View>()

    init {
        isMotionEventSplittingEnabled = true
        keyRows = keyLayout.map { row ->
            val keyViews = row.map(::createKeyView)
            constraintLayout Row@{
                var totalWidth = 0f
                keyViews.forEachIndexed { index, view ->
                    add(view, lParams {
                        centerVertically()
                        if (index == 0) {
                            leftOfParent()
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            leftToRightOf(keyViews[index - 1])
                        }
                        if (index == keyViews.size - 1) {
                            rightOfParent()
                            // for RTL
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            rightToLeftOf(keyViews[index + 1])
                        }
                        val def = row[index]
                        matchConstraintPercentWidth = def.appearance.percentWidth
                    })
                    row[index].appearance.percentWidth.let {
                        // 0f means fill remaining space, thus does not need expanding
                        totalWidth += if (it != 0f) it else 1f
                    }
                }
                if (expandKeypressArea && totalWidth < 1f) {
                    val free = (1f - totalWidth) / 2f
                    keyViews.first().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginLeft = free / (row.first().appearance.percentWidth + free)
                    }
                    keyViews.last().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginRight = free / (row.last().appearance.percentWidth + free)
                    }
                }
            }
        }
        keyRows.forEachIndexed { index, row ->
            add(row, lParams {
                if (index == 0) topOfParent()
                else below(keyRows[index - 1])
                if (index == keyRows.size - 1) bottomOfParent()
                else above(keyRows[index + 1])
                centerHorizontally()
            })
        }
        spaceSwipeMoveCursor.registerOnChangeListener(spaceSwipeChangeListener)
    }

    private fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, theme, def.appearance)
        }.apply {
            if (def is AlphabetKey) {
                swipeKeyLabels[this] = def.character.lowercase()
            }
            soundEffect = when (def) {
                is SpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is MiniSpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is BackspaceKey -> InputFeedbacks.SoundEffect.Delete
                is ReturnKey -> InputFeedbacks.SoundEffect.Return
                else -> InputFeedbacks.SoundEffect.Standard
            }
            if (def is SpaceKey) {
                spaceKeys.add(this)
                swipeEnabled = spaceSwipeMoveCursor.getValue()
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> when (val count = event.countX) {
                            0 -> false
                            else -> {
                                val sym =
                                    if (count > 0) FcitxKeyMapping.FcitxKey_Right else FcitxKeyMapping.FcitxKey_Left
                                val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                                repeat(count.absoluteValue) {
                                    onAction(action)
                                    if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                }
                                true
                            }
                        }
                        else -> false
                    }
                }
            } else if (def is BackspaceKey) {
                swipeEnabled = true
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> {
                            val count = event.countX
                            if (count != 0) {
                                onAction(KeyAction.MoveSelectionAction(count))
                                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                true
                            } else false
                        }
                        GestureType.Up -> {
                            onAction(KeyAction.DeleteSelectionAction(event.totalX))
                            false
                        }
                        else -> false
                    }
                }
            }
            def.behaviors.forEach {
                when (it) {
                    is KeyDef.Behavior.Press -> {
                        setOnClickListener { _ ->
                            onAction(it.action)
                        }
                    }
                    is KeyDef.Behavior.LongPress -> {
                        setOnLongClickListener { _ ->
                            onAction(it.action)
                            true
                        }
                    }
                    is KeyDef.Behavior.Repeat -> {
                        repeatEnabled = true
                        onRepeatListener = { view ->
                            onAction(it.action)
                            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                        }
                    }
                    is KeyDef.Behavior.Swipe -> {
                        swipeEnabled = true
                        swipeThresholdX = disabledSwipeThreshold
                        swipeThresholdY = inputSwipeThreshold
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            when (event.type) {
                                GestureType.Up -> {
                                    if (!swipeTyping &&
                                        !event.consumed &&
                                        swipeSymbolDirection.checkY(event.totalY)
                                    ) {
                                        onAction(it.action)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Behavior.DoubleTap -> {
                        doubleTapEnabled = true
                        onDoubleTapListener = { _ ->
                            onAction(it.action)
                        }
                    }
                }
            }
            def.popup?.forEach {
                when (it) {
                    // TODO: gesture processing middleware
                    is KeyDef.Popup.Menu -> {
                        setOnLongClickListener { view ->
                            view as KeyView
                            onPopupAction(PopupAction.ShowMenuAction(view.id, it, view.bounds))
                            // do not consume this LongClick gesture
                            false
                        }
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        swipeEnabled = true
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(view.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(view.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Keyboard -> {
                        setOnLongClickListener { view ->
                            view as KeyView
                            onPopupAction(PopupAction.ShowKeyboardAction(view.id, it, view.bounds))
                            // do not consume this LongClick gesture
                            false
                        }
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        swipeEnabled = true
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(view.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(view.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.AltPreview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, it.content, view.bounds)
                                    )
                                    GestureType.Move -> {
                                        val triggered =
                                            !swipeTyping && swipeSymbolDirection.checkY(event.totalY)
                                        val text = if (triggered) it.alternative else it.content
                                        onPopupAction(
                                            PopupAction.PreviewUpdateAction(view.id, text)
                                        )
                                    }
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Preview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, it.content, view.bounds)
                                    )
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                    else -> {}
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val (x, y) = intArrayOf(0, 0).also { getLocationInWindow(it) }
        bounds.set(x, y, x + width, y + height)
    }

    private fun findTargetChild(x: Float, y: Float): View? {
        if (bounds.height() <= 0 || keyRows.isEmpty()) return null
        val y0 = y.roundToInt()
        // assume all rows have equal height
        val row = keyRows.getOrNull(y0 * keyRows.size / bounds.height()) ?: return null
        val x1 = x.roundToInt() + bounds.left
        val y1 = y0 + bounds.top
        return row.children.find {
            if (it !is KeyView) false else it.bounds.contains(x1, y1)
        }
    }

    private fun transformMotionEventToChild(
        child: View,
        event: MotionEvent,
        action: Int,
        pointerIndex: Int
    ): MotionEvent {
        if (child !is KeyView) {
            Timber.w("child view is not KeyView when transforming MotionEvent $event")
            return event
        }
        val childX = event.getX(pointerIndex) + bounds.left - child.bounds.left
        val childY = event.getY(pointerIndex) + bounds.top - child.bounds.top
        return MotionEvent.obtain(
            event.downTime, event.eventTime, action,
            childX, childY, event.getPressure(pointerIndex), event.getSize(pointerIndex),
            event.metaState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags
        )
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (handleSwipeIntercept(ev)) return true
        // intercept ACTION_DOWN and all following events will go to parent's onTouchEvent
        return if (vivoKeypressWorkaround && ev.actionMasked == MotionEvent.ACTION_DOWN) true
        else super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handleSwipeTouchEvent(event)) return true
        if (vivoKeypressWorkaround) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val target = findTargetChild(event.x, event.y) ?: return false
                    touchTarget[event.getPointerId(0)] = target
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_DOWN, 0)
                    )
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = event.actionIndex
                    val target = findTargetChild(event.getX(i), event.getY(i)) ?: return false
                    touchTarget[event.getPointerId(i)] = target
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_DOWN, i)
                    )
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val target = touchTarget[event.getPointerId(i)] ?: continue
                        target.dispatchTouchEvent(
                            transformMotionEventToChild(target, event, MotionEvent.ACTION_MOVE, i)
                        )
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTarget[event.getPointerId(i)] ?: return false
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_UP, i)
                    )
                    touchTarget.remove(pid)
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTarget[event.getPointerId(i)] ?: return false
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_UP, i)
                    )
                    touchTarget.remove(pid)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTarget[pid] ?: return false
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_CANCEL, i)
                    )
                    touchTarget.remove(pid)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!swipeTracking || swipeVisualPoints.isEmpty()) return
        val visualPoints = smoothedSwipeVisualPoints()
        if (visualPoints.size >= 2) {
            swipeTrailPath.reset()
            visualPoints.first().let { (x, y) -> swipeTrailPath.moveTo(x, y) }
            if (visualPoints.size == 2) {
                visualPoints.last().let { (x, y) -> swipeTrailPath.lineTo(x, y) }
            } else {
                for (i in 1 until visualPoints.lastIndex) {
                    val (x, y) = visualPoints[i]
                    val (nextX, nextY) = visualPoints[i + 1]
                    swipeTrailPath.quadTo(x, y, (x + nextX) / 2f, (y + nextY) / 2f)
                }
                visualPoints.last().let { (x, y) -> swipeTrailPath.lineTo(x, y) }
            }
            drawSwipeTrail(canvas)
        }
        val (x, y) = visualPoints.last()
        canvas.drawCircle(x, y, dp(10.5f), swipeDotHaloPaint)
        canvas.drawCircle(x, y, dp(7.25f), swipeDotPaint)
    }

    private fun drawSwipeTrail(canvas: Canvas) {
        canvas.drawPath(swipeTrailPath, swipeTrailHaloPaint)
        canvas.drawPath(swipeTrailPath, swipeTrailPaint)
    }

    private fun handleSwipeIntercept(event: MotionEvent): Boolean {
        if (!swipeTyping) {
            resetSwipeTracking()
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = findTargetChild(event.x, event.y) ?: run {
                    resetSwipeTracking()
                    return false
                }
                val label = swipeKeyLabels[target] ?: run {
                    resetSwipeTracking()
                    return false
                }
                swipePointerId = event.getPointerId(0)
                swipeStartX = event.x
                swipeStartY = event.y
                swipeStartTime = event.eventTime
                swipeTracking = true
                swipeIntercepted = false
                swipePoints.clear()
                swipeVisualPoints.clear()
                swipeTrace.clear()
                appendSwipePoint(event, 0)
                appendSwipeLetter(label)
                getSwipeDecoder(SwipeTypingMode.usePinyinBridge(currentInputMethod)).warmUp()
            }
            MotionEvent.ACTION_POINTER_DOWN -> resetSwipeTracking()
            MotionEvent.ACTION_MOVE -> {
                if (!swipeTracking || swipeIntercepted) return false
                val index = event.findPointerIndex(swipePointerId)
                if (index < 0 || event.pointerCount != 1) {
                    resetSwipeTracking()
                    return false
                }
                appendSwipePoint(event, index)
                findTargetChild(event.getX(index), event.getY(index))?.let { target ->
                    swipeKeyLabels[target]?.let(::appendSwipeLetter)
                }
                if (shouldInterceptSwipe(event.getX(index), event.getY(index))) {
                    swipeIntercepted = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetSwipeTracking()
        }
        return false
    }

    private fun handleSwipeTouchEvent(event: MotionEvent): Boolean {
        if (!swipeIntercepted) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(swipePointerId)
                if (index >= 0) {
                    appendSwipePoint(event, index)
                    findTargetChild(event.getX(index), event.getY(index))?.let { target ->
                        swipeKeyLabels[target]?.let(::appendSwipeLetter)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(swipePointerId)
                if (index >= 0) {
                    appendSwipePoint(event, index)
                    findTargetChild(event.getX(index), event.getY(index))?.let { target ->
                        swipeKeyLabels[target]?.let(::appendSwipeLetter)
                    }
                }
                finishSwipe()
                resetSwipeTracking()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                resetSwipeTracking()
                return true
            }
        }
        return true
    }

    private fun shouldInterceptSwipe(x: Float, y: Float): Boolean {
        if (swipeTrace.toString().toSet().size < 2) return false
        val dx = x - swipeStartX
        val dy = y - swipeStartY
        return dx * dx + dy * dy >= inputSwipeThreshold * inputSwipeThreshold
    }

    private fun appendSwipePoint(event: MotionEvent, pointerIndex: Int) {
        val keyboardWidth = max(1, width)
        val keyboardHeight = max(1, height)
        val rawX = event.getX(pointerIndex)
        val rawY = event.getY(pointerIndex)
        val x = (rawX / keyboardWidth).coerceIn(0f, 1f)
        val y = (rawY / keyboardHeight).coerceIn(0f, 1f)
        val t = (event.eventTime - swipeStartTime).coerceAtLeast(0L).toFloat()
        swipePoints.add(SwipePoint(x, y, t))
        val visualPoint = rawX.coerceIn(0f, width.toFloat()) to rawY.coerceIn(0f, height.toFloat())
        val lastVisualPoint = swipeVisualPoints.lastOrNull()
        if (lastVisualPoint == null || visualDistanceSquared(lastVisualPoint, visualPoint) >=
            swipeVisualPointMinDistance * swipeVisualPointMinDistance
        ) {
            swipeVisualPoints.add(visualPoint)
        } else {
            swipeVisualPoints[swipeVisualPoints.lastIndex] = visualPoint
        }
        invalidate()
    }

    private fun smoothedSwipeVisualPoints(): List<Pair<Float, Float>> {
        if (swipeVisualPoints.size <= 2) return swipeVisualPoints

        val points = ArrayList<Pair<Float, Float>>(swipeVisualPoints.size)
        points += swipeVisualPoints.first()
        for (i in 1 until swipeVisualPoints.lastIndex) {
            val previous = swipeVisualPoints[i - 1]
            val current = swipeVisualPoints[i]
            val next = swipeVisualPoints[i + 1]
            points += (
                (previous.first + current.first * 2f + next.first) / 4f to
                    (previous.second + current.second * 2f + next.second) / 4f
                )
        }
        points += swipeVisualPoints.last()
        return points
    }

    private fun visualDistanceSquared(
        left: Pair<Float, Float>,
        right: Pair<Float, Float>
    ): Float {
        val dx = left.first - right.first
        val dy = left.second - right.second
        return dx * dx + dy * dy
    }

    private fun appendSwipeLetter(label: String) {
        if (label.length != 1 || !label[0].isLetter()) return
        val normalized = label.lowercase()
        if (swipeTrace.isEmpty() || swipeTrace.last().toString() != normalized) {
            swipeTrace.append(normalized)
        }
    }

    private fun finishSwipe() {
        val layout = buildSwipeLayout() ?: return
        val request = SwipeRecognitionRequest(
            points = swipePoints.toList(),
            layout = layout,
            tracedLetters = swipeTrace.toString()
        )
        val bridgeToFcitx = SwipeTypingMode.usePinyinBridge(currentInputMethod)
        val candidate = runCatching {
            getSwipeDecoder(bridgeToFcitx).recognize(request).firstOrNull()
        }.onFailure {
            Timber.w(it, "Swipe typing failed")
        }.getOrNull() ?: return
        val word = candidate.word
        if (word.isBlank()) return
        onAction(
            if (bridgeToFcitx) KeyAction.FcitxKeySequenceAction(word)
            else KeyAction.CommitAction(word.lowercase())
        )
    }

    private fun getSwipeDecoder(bridgeToFcitx: Boolean): SwipeTypingDecoder {
        return swipeDecoder.takeIf {
            swipeDecoderPinyinMode == bridgeToFcitx
        } ?: SwipeTypingDecoders.create(context, bridgeToFcitx).also {
            swipeDecoder?.close()
            swipeDecoder = it
            swipeDecoderPinyinMode = bridgeToFcitx
        }
    }

    private fun buildSwipeLayout(): SwipeLayout? {
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        val keys = swipeKeyLabels.mapNotNull { (view, label) ->
            val keyView = view as? KeyView ?: return@mapNotNull null
            val keyBounds = keyView.bounds
            SwipeKey(
                label,
                (keyBounds.centerX() - bounds.left).toFloat() / bounds.width(),
                (keyBounds.centerY() - bounds.top).toFloat() / bounds.height()
            )
        }
        return keys.takeIf { it.isNotEmpty() }?.let(::SwipeLayout)
    }

    private fun resetSwipeTracking() {
        swipePointerId = MotionEvent.INVALID_POINTER_ID
        swipeTracking = false
        swipeIntercepted = false
        swipePoints.clear()
        swipeVisualPoints.clear()
        swipeTrace.clear()
        invalidate()
    }

    @CallSuper
    protected open fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source = KeyActionListener.Source.Keyboard
    ) {
        keyActionListener?.onKeyAction(action, source)
    }

    @CallSuper
    protected open fun onPopupAction(action: PopupAction) {
        popupActionListener?.onPopupAction(action)
    }

    private fun onPopupChangeFocus(viewId: Int, x: Float, y: Float): Boolean {
        val changeFocusAction = PopupAction.ChangeFocusAction(viewId, x, y)
        popupActionListener?.onPopupAction(changeFocusAction)
        return changeFocusAction.outResult
    }

    private fun onPopupTrigger(viewId: Int): Boolean {
        val triggerAction = PopupAction.TriggerAction(viewId)
        // ask popup keyboard whether there's a pending KeyAction
        onPopupAction(triggerAction)
        val action = triggerAction.outAction ?: return false
        onAction(action, KeyActionListener.Source.Popup)
        onPopupAction(PopupAction.DismissAction(viewId))
        return true
    }

    open fun onAttach() {
        // do nothing by default
    }

    open fun onReturnDrawableUpdate(@DrawableRes returnDrawable: Int) {
        // do nothing by default
    }

    open fun onPunctuationUpdate(mapping: Map<String, String>) {
        // do nothing by default
    }

    open fun onInputMethodUpdate(ime: InputMethodEntry) {
        currentInputMethod = ime
        swipeDecoder?.close()
        swipeDecoder = null
        swipeDecoderPinyinMode = null
        if (swipeTyping) {
            getSwipeDecoder(SwipeTypingMode.usePinyinBridge(ime)).warmUp()
        }
    }

    open fun onDetach() {
        swipeDecoder?.close()
        swipeDecoder = null
        swipeDecoderPinyinMode = null
    }

}
