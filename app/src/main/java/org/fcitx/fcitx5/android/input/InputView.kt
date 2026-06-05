/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.unset
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.withTheme
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.imageResource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    companion object {
        private const val MIN_FLOATING_KEYBOARD_WIDTH_PERCENT = 78
        private const val MAX_FLOATING_KEYBOARD_WIDTH_PERCENT = 100
        private const val MIN_FLOATING_KEYBOARD_WIDTH_DP = 320
        private const val FLOATING_DRAG_HANDLE_HEIGHT_DP = 24
        private const val FLOATING_BOTTOM_CONTROLS_HEIGHT_DP = 14
        private const val FLOATING_KEYBOARD_SIDE_INSET_DP = 3
        private const val FLOATING_MOVE_HANDLE_WIDTH_DP = 92
        private const val FLOATING_MOVE_HANDLE_HEIGHT_DP = 5
        private const val FLOATING_DOCK_THRESHOLD_DP = 72
        private const val FLOATING_RESIZE_HANDLE_SIZE_DP = 40
        private const val FLOATING_KEYBOARD_CORNER_RADIUS_DP = 28
        private const val FLOATING_KEYBOARD_ELEVATION_DP = 12
        private const val FLOATING_EDIT_OVERLAY_OUTSET_DP = 18
        private const val FLOATING_CORNER_HANDLE_SIZE_DP = 64
        private const val FLOATING_CORNER_HANDLE_STROKE_DP = 5
        private const val FLOATING_RESET_BUTTON_MIN_WIDTH_DP = 340
        private const val FLOATING_RESET_BUTTON_MIN_HEIGHT_DP = 220
        private const val FLOATING_KEYBOARD_HEIGHT_SCALE = 0.70f
        private const val DEFAULT_FLOATING_KEYBOARD_WIDTH_PERCENT = 80
        private const val DEFAULT_FLOATING_KEYBOARD_X_RATIO = 0.5f
        private const val DEFAULT_FLOATING_KEYBOARD_Y_RATIO = 0.48f
        private const val DEFAULT_FLOATING_KEYBOARD_Y_RATIO_LANDSCAPE = 0.55f
        private const val FLOATING_EDIT_CONTROLS_HIDE_DELAY_MS = 3000L
    }

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }
    private val resizeHandle = imageView {
        imageResource = R.drawable.ic_baseline_drag_handle_24
        scaleType = ImageView.ScaleType.CENTER
        alpha = 0f
        contentDescription = context.getString(R.string.resize_floating_keyboard)
    }
    private val floatingMoveHandle = view(::View) {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(FLOATING_MOVE_HANDLE_HEIGHT_DP).toFloat()
            setColor(theme.keyTextColor.alpha(0.38f))
        }
    }
    private val floatingEditOverlay = view(::FrameLayout) {
        clipChildren = false
        clipToPadding = false
        visibility = GONE
    }
    private val resetFloatingKeyboardButton = textView {
        text = context.getString(R.string.reset)
        gravity = Gravity.CENTER
        setTextColor(theme.altKeyTextColor)
        textSize = 12f
        includeFontPadding = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(theme.altKeyBackgroundColor.alpha(0.86f))
        }
        setOnClickListener { resetFloatingKeyboardLayout() }
    }

    private val scope = DynamicScope()
    private val themedContext = context.withTheme(R.style.Theme_InputViewTheme)
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard
    private val internalPrefs = AppPrefs.getInstance().internal

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private var floatingKeyboardEnabled by keyboardPrefs.floatingKeyboardEnabled
    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape
    private val floatingKeyboardWidthPercent = keyboardPrefs.floatingKeyboardWidthPercent
    private val floatingKeyboardWidthPercentLandscape = keyboardPrefs.floatingKeyboardWidthPercentLandscape

    private val keyboardSizePrefs = listOf(
        keyboardPrefs.floatingKeyboardEnabled,
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
        floatingKeyboardWidthPercent,
        floatingKeyboardWidthPercentLandscape,
    )

    private val keyboardHeightPx: Int
        get() {
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            val baseHeight = (if (floatingKeyboardEnabled) {
                height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            } else {
                resources.displayMetrics.heightPixels
            }) * percent / 100
            return if (floatingKeyboardEnabled) {
                (baseHeight * FLOATING_KEYBOARD_HEIGHT_SCALE).roundToInt()
            } else {
                baseHeight
            }
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    private val floatingKeyboardWidthPx: Int
        get() {
            val parentWidth = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val percentWidth = parentWidth * activeFloatingKeyboardWidthPercent.getValue() / 100
            return max(percentWidth, min(parentWidth, dp(MIN_FLOATING_KEYBOARD_WIDTH_DP)))
        }

    private val floatingDragHandleHeightPx: Int
        get() = dp(FLOATING_DRAG_HANDLE_HEIGHT_DP)

    private val activeFloatingKeyboardWidthPercent
        get() = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> floatingKeyboardWidthPercentLandscape
            else -> floatingKeyboardWidthPercent
        }

    private val activeFloatingKeyboardXRatio
        get() = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> internalPrefs.floatingKeyboardXRatioLandscape
            else -> internalPrefs.floatingKeyboardXRatio
        }

    private val activeFloatingKeyboardYRatio
        get() = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> internalPrefs.floatingKeyboardYRatioLandscape
            else -> internalPrefs.floatingKeyboardYRatio
        }

    private var navBarBottomInset = 0
    private var floatingKeyboardX = 0f
    private var floatingKeyboardY = 0f
    private var floatingDragStartRawX = 0f
    private var floatingDragStartRawY = 0f
    private var floatingDragStartKeyboardX = 0f
    private var floatingDragStartKeyboardY = 0f
    private var floatingResizeStartWidth = 0
    private var floatingEditControlsVisible = false
    private val inputViewLocation = intArrayOf(0, 0)
    private val hideFloatingEditControlsRunnable = Runnable {
        floatingEditControlsVisible = false
        updateFloatingEditOverlayPosition()
    }

    private inner class FloatingCornerHandleView(
        context: android.content.Context,
        private val horizontalSign: Int,
        private val verticalSign: Int
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.genericActiveBackgroundColor
            style = Paint.Style.STROKE
            strokeWidth = dp(FLOATING_CORNER_HANDLE_STROKE_DP).toFloat()
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = paint.strokeWidth / 2f
            val arc = RectF(inset, inset, width - inset, height - inset)
            val startAngle = when {
                horizontalSign < 0 && verticalSign < 0 -> 180f
                horizontalSign > 0 && verticalSign < 0 -> 270f
                horizontalSign > 0 && verticalSign > 0 -> 0f
                else -> 90f
            }
            canvas.drawArc(arc, startAngle, 90f, false, paint)
        }
    }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardPrefs.floatingKeyboardEnabled.key == key) {
            applyKeyboardMode()
        } else if (keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
        }
    }

    val keyboardView: View

    private val floatingKeyboardDragListener = OnTouchListener { _, event ->
        if (!floatingKeyboardEnabled) return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                showFloatingEditControls()
                floatingDragStartRawX = event.rawX
                floatingDragStartRawY = event.rawY
                floatingDragStartKeyboardX = floatingKeyboardX
                floatingDragStartKeyboardY = floatingKeyboardY
                true
            }

            MotionEvent.ACTION_MOVE -> {
                updateFloatingKeyboardPosition(
                    floatingDragStartKeyboardX + event.rawX - floatingDragStartRawX,
                    floatingDragStartKeyboardY + event.rawY - floatingDragStartRawY,
                    persist = true
                )
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dockFloatingKeyboardIfNearBottom()) {
                    scheduleHideFloatingEditControls()
                }
                true
            }
            else -> false
        }
    }

    private fun floatingKeyboardResizeListener(horizontalSign: Int) = OnTouchListener { _, event ->
        if (!floatingKeyboardEnabled) return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                showFloatingEditControls()
                floatingDragStartRawX = event.rawX
                floatingDragStartKeyboardX = floatingKeyboardX
                floatingDragStartKeyboardY = floatingKeyboardY
                floatingResizeStartWidth = keyboardView.width
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val parentWidth = width.takeIf { it > 0 } ?: return@OnTouchListener true
                val delta = horizontalSign * (event.rawX - floatingDragStartRawX)
                val newWidth = (floatingResizeStartWidth + delta).roundToInt()
                val percent = (newWidth * 100f / parentWidth)
                    .roundToInt()
                    .coerceIn(MIN_FLOATING_KEYBOARD_WIDTH_PERCENT, MAX_FLOATING_KEYBOARD_WIDTH_PERCENT)
                activeFloatingKeyboardWidthPercent.setValue(percent)
                updateKeyboardSize()
                val newWidthPx = floatingKeyboardWidthPx
                val newX = if (horizontalSign < 0) {
                    floatingDragStartKeyboardX + floatingResizeStartWidth - newWidthPx
                } else {
                    floatingDragStartKeyboardX
                }
                updateFloatingKeyboardPosition(newX, floatingDragStartKeyboardY, persist = true)
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scheduleHideFloatingEditControls()
                true
            }
            else -> false
        }
    }

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)

        keyboardView = constraintLayout {
            background = theme.backgroundDrawable(keyBorder)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = if (floatingKeyboardEnabled) {
                        dp(FLOATING_KEYBOARD_CORNER_RADIUS_DP).toFloat()
                    } else {
                        0f
                    }
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams(matchParent, matchParent) {
                centerVertically()
                centerHorizontally()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                topOfParent()
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
            add(floatingMoveHandle, lParams(
                dp(FLOATING_MOVE_HANDLE_WIDTH_DP),
                dp(FLOATING_MOVE_HANDLE_HEIGHT_DP)
            ) {
                centerHorizontally()
                bottomOfParent()
                bottomMargin = dp(34)
            })
            add(resizeHandle, lParams(dp(FLOATING_RESIZE_HANDLE_SIZE_DP), dp(FLOATING_RESIZE_HANDLE_SIZE_DP)) {
                endOfParent()
                bottomOfParent()
            })
        }

        bottomPaddingSpace.setOnTouchListener(floatingKeyboardDragListener)
        floatingMoveHandle.setOnTouchListener(floatingKeyboardDragListener)
        resizeHandle.setOnTouchListener(floatingKeyboardResizeListener(1))

        updateKeyboardSize()

        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            above(keyboardView)
            centerHorizontally()
        })
        if (floatingKeyboardEnabled) {
            add(keyboardView, lParams(floatingKeyboardWidthPx, wrapContent) {
                startOfParent()
                topOfParent()
            })
        } else {
            add(keyboardView, lParams(matchParent, wrapContent) {
                centerHorizontally()
                bottomOfParent()
            })
        }
        add(floatingEditOverlay, lParams(floatingKeyboardWidthPx, wrapContent) {
            startOfParent()
            topOfParent()
        })
        setupFloatingEditOverlay()
        post { applyKeyboardMode() }
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
    }

    private fun updateKeyboardSize() {
        windowManager.view.updateLayoutParams {
            height = keyboardHeightPx
        }
        bottomPaddingSpace.updateLayoutParams {
            height = if (floatingKeyboardEnabled) {
                max(dp(FLOATING_BOTTOM_CONTROLS_HEIGHT_DP), floatingDragHandleHeightPx)
            } else {
                keyboardBottomPaddingPx
            }
        }
        val sidePadding = keyboardSidePaddingPx
        if (floatingKeyboardEnabled) {
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            floatingMoveHandle.visibility = VISIBLE
            resizeHandle.visibility = GONE
            windowManager.view.translationY = 0f
            val floatingSideInset = dp(FLOATING_KEYBOARD_SIDE_INSET_DP)
            windowManager.view.setPadding(floatingSideInset, 0, floatingSideInset, 0)
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
            bottomPaddingSpace.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            floatingMoveHandle.visibility = GONE
            resizeHandle.visibility = GONE
            windowManager.view.translationY = 0f
            windowManager.view.setPadding(0, 0, 0, 0)
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            floatingMoveHandle.visibility = GONE
            resizeHandle.visibility = GONE
            windowManager.view.translationY = 0f
            windowManager.view.setPadding(0, 0, 0, 0)
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        val inputSidePadding = if (floatingKeyboardEnabled) 0 else sidePadding
        preedit.ui.root.setPadding(inputSidePadding, 0, inputSidePadding, 0)
        kawaiiBar.view.setPadding(inputSidePadding, 0, inputSidePadding, 0)
        if (floatingKeyboardEnabled) {
            if (keyboardView.parent != null) {
                keyboardView.updateLayoutParams<LayoutParams> {
                    width = floatingKeyboardWidthPx
                }
            }
            updateFloatingEditOverlaySize()
            keyboardView.post {
                restoreFloatingKeyboardPosition()
            }
        }
        service.window.window?.decorView?.requestLayout()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        navBarBottomInset = getNavBarBottomInset(insets)
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = navBarBottomInset
        }
        if (floatingKeyboardEnabled) {
            keyboardView.post {
                restoreFloatingKeyboardPosition()
            }
        }
        return insets
    }

    private fun applyKeyboardMode() {
        keyboardView.updateLayoutParams<LayoutParams> {
            if (floatingKeyboardEnabled) {
                width = floatingKeyboardWidthPx
                height = wrapContent
                startToStart = PARENT_ID
                topToTop = PARENT_ID
                endToEnd = unset
                bottomToBottom = unset
            } else {
                width = matchParent
                height = wrapContent
                startToStart = unset
                topToTop = unset
                endToEnd = unset
                bottomToBottom = PARENT_ID
                centerHorizontally()
            }
        }
        keyboardView.translationX = 0f
        keyboardView.translationY = 0f
        updateKeyboardSize()
        updateFloatingKeyboardChrome()
        kawaiiBar.updateFloatingKeyboardButton()
        if (floatingKeyboardEnabled) {
            keyboardView.post { restoreFloatingKeyboardPosition() }
        }
    }

    private fun updateFloatingKeyboardChrome() {
        keyboardView.clipToOutline = floatingKeyboardEnabled
        keyboardView.elevation = if (floatingKeyboardEnabled) {
            dp(FLOATING_KEYBOARD_ELEVATION_DP).toFloat()
        } else {
            0f
        }
        if (!floatingKeyboardEnabled) {
            floatingEditControlsVisible = false
            removeCallbacks(hideFloatingEditControlsRunnable)
        }
        updateFloatingEditOverlayPosition()
        keyboardView.invalidateOutline()
    }

    private fun setupFloatingEditOverlay() {
        val handleSize = dp(FLOATING_CORNER_HANDLE_SIZE_DP)
        val outset = dp(FLOATING_EDIT_OVERLAY_OUTSET_DP)
        val handles = arrayOf(
            Triple(FloatingCornerHandleView(context, -1, -1), -1, FrameLayout.LayoutParams(handleSize, handleSize).apply {
                gravity = Gravity.START or Gravity.TOP
            }),
            Triple(FloatingCornerHandleView(context, 1, -1), 1, FrameLayout.LayoutParams(handleSize, handleSize).apply {
                gravity = Gravity.END or Gravity.TOP
            }),
            Triple(FloatingCornerHandleView(context, -1, 1), -1, FrameLayout.LayoutParams(handleSize, handleSize).apply {
                gravity = Gravity.START or Gravity.BOTTOM
            }),
            Triple(FloatingCornerHandleView(context, 1, 1), 1, FrameLayout.LayoutParams(handleSize, handleSize).apply {
                gravity = Gravity.END or Gravity.BOTTOM
            }),
        )
        handles.forEach { (view, horizontalSign, params) ->
            view.setOnTouchListener(floatingKeyboardResizeListener(horizontalSign))
            floatingEditOverlay.addView(view, params)
        }
        floatingEditOverlay.addView(
            resetFloatingKeyboardButton,
            FrameLayout.LayoutParams(dp(68), dp(28)).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = outset + dp(6)
                bottomMargin = outset + dp(6)
            }
        )
    }

    private fun updateFloatingEditOverlaySize() {
        if (floatingEditOverlay.parent == null) return
        val outset = dp(FLOATING_EDIT_OVERLAY_OUTSET_DP)
        floatingEditOverlay.updateLayoutParams<LayoutParams> {
            width = if (floatingKeyboardEnabled) {
                floatingKeyboardWidthPx + outset * 2
            } else {
                0
            }
            height = if (floatingKeyboardEnabled && keyboardView.height > 0) {
                keyboardView.height + outset * 2
            } else {
                0
            }
        }
    }

    private fun updateFloatingEditOverlayPosition() {
        val outset = dp(FLOATING_EDIT_OVERLAY_OUTSET_DP).toFloat()
        floatingEditOverlay.visibility =
            if (floatingKeyboardEnabled && floatingEditControlsVisible) VISIBLE else GONE
        floatingEditOverlay.translationX = floatingKeyboardX - outset
        floatingEditOverlay.translationY = floatingKeyboardY - outset
        floatingEditOverlay.elevation = keyboardView.elevation + 1f
        resetFloatingKeyboardButton.visibility = if (
            floatingEditOverlay.visibility == VISIBLE &&
            floatingKeyboardWidthPx >= dp(FLOATING_RESET_BUTTON_MIN_WIDTH_DP) &&
            keyboardView.height >= dp(FLOATING_RESET_BUTTON_MIN_HEIGHT_DP)
        ) {
            VISIBLE
        } else {
            GONE
        }
    }

    private fun showFloatingEditControls() {
        if (!floatingKeyboardEnabled) return
        removeCallbacks(hideFloatingEditControlsRunnable)
        floatingEditControlsVisible = true
        updateFloatingEditOverlaySize()
        updateFloatingEditOverlayPosition()
    }

    private fun scheduleHideFloatingEditControls() {
        removeCallbacks(hideFloatingEditControlsRunnable)
        postDelayed(hideFloatingEditControlsRunnable, FLOATING_EDIT_CONTROLS_HIDE_DELAY_MS)
    }

    private fun resetFloatingKeyboardLayout() {
        activeFloatingKeyboardWidthPercent.setValue(DEFAULT_FLOATING_KEYBOARD_WIDTH_PERCENT)
        activeFloatingKeyboardXRatio.setValue(DEFAULT_FLOATING_KEYBOARD_X_RATIO)
        activeFloatingKeyboardYRatio.setValue(
            when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> DEFAULT_FLOATING_KEYBOARD_Y_RATIO_LANDSCAPE
                else -> DEFAULT_FLOATING_KEYBOARD_Y_RATIO
            }
        )
        updateKeyboardSize()
    }

    private fun restoreFloatingKeyboardPosition() {
        if (!floatingKeyboardEnabled || width <= 0 || keyboardView.width <= 0) return
        val maxX = max(0, width - keyboardView.width).toFloat()
        val maxY = max(0, height - navBarBottomInset - keyboardView.height).toFloat()
        val x = maxX * activeFloatingKeyboardXRatio.getValue().coerceIn(0f, 1f)
        val y = maxY * activeFloatingKeyboardYRatio.getValue().coerceIn(0f, 1f)
        updateFloatingKeyboardPosition(x, y)
    }

    private fun updateFloatingKeyboardPosition(x: Float, y: Float, persist: Boolean = false) {
        if (!floatingKeyboardEnabled) return
        val maxX = max(0, width - keyboardView.width).toFloat()
        val maxY = max(0, height - navBarBottomInset - keyboardView.height).toFloat()
        floatingKeyboardX = min(max(x, 0f), maxX)
        floatingKeyboardY = min(max(y, 0f), maxY)
        keyboardView.translationX = floatingKeyboardX
        keyboardView.translationY = floatingKeyboardY
        updateFloatingEditOverlaySize()
        updateFloatingEditOverlayPosition()
        if (persist) {
            activeFloatingKeyboardXRatio.setValue(if (maxX > 0f) floatingKeyboardX / maxX else 0.5f)
            activeFloatingKeyboardYRatio.setValue(if (maxY > 0f) floatingKeyboardY / maxY else 1f)
        }
        service.window.window?.decorView?.requestLayout()
    }

    private fun dockFloatingKeyboardIfNearBottom(): Boolean {
        if (!floatingKeyboardEnabled || height <= 0 || keyboardView.height <= 0) return false
        val dockY = height - navBarBottomInset - keyboardView.height
        if (dockY - floatingKeyboardY > dp(FLOATING_DOCK_THRESHOLD_DP)) return false
        floatingKeyboardEnabled = false
        floatingEditControlsVisible = false
        removeCallbacks(hideFloatingEditControlsRunnable)
        keyboardView.translationX = 0f
        keyboardView.translationY = 0f
        applyKeyboardMode()
        return true
    }

    fun getFloatingKeyboardTouchableRect(outRect: Rect): Boolean {
        if (!floatingKeyboardEnabled || keyboardView.width <= 0 || keyboardView.height <= 0) {
            return false
        }
        keyboardView.getLocationInWindow(inputViewLocation)
        val outset = dp(FLOATING_EDIT_OVERLAY_OUTSET_DP)
        outRect.set(
            inputViewLocation[0] - outset,
            inputViewLocation[1] - outset,
            inputViewLocation[0] + keyboardView.width + outset,
            inputViewLocation[1] + keyboardView.height + outset
        )
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (floatingKeyboardEnabled) {
            keyboardView.post {
                restoreFloatingKeyboardPosition()
            }
        }
    }

    fun toggleFloatingKeyboard() {
        floatingKeyboardEnabled = !floatingKeyboardEnabled
        applyKeyboardMode()
    }

    fun isFloatingKeyboardEnabled() = floatingKeyboardEnabled

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
