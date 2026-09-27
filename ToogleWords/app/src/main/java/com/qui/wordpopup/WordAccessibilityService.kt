package com.qui.wordpopup

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.reflect.Method
import java.util.Locale
import kotlin.math.abs

private data class WordHit(
    val word: String,
    val rect: Rect,
    val exact: Boolean
)

class WordAccessibilityService : AccessibilityService() {

    private var toggleMode = false
    private var touchOverlay: FrameLayout? = null
    private var highlightView: View? = null
    private var highlightLabel: TextView? = null
    private var windowManager: WindowManager? = null
    private var lastHighlightedWord: String? = null
    private var lastHighlightedRect: Rect? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    fun toggleMode() {
        setToggleMode(!toggleMode)
    }

    fun setToggleMode(enabled: Boolean) {
        if (toggleMode == enabled) return

        toggleMode = enabled

        if (enabled) {
            showTouchOverlay()
        } else {
            hideTouchOverlay()
            clearHighlight()
        }

        PopupService.instance?.setToggleModeVisual(enabled)
    }

    private fun showTouchOverlay() {
        if (touchOverlay != null) return

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                handleTouch(event)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(root, params)
            touchOverlay = root
        } catch (_: Exception) {
            toggleMode = false
            PopupService.instance?.setToggleModeVisual(false)
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                return true
            }

            MotionEvent.ACTION_UP -> {
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()

                val hit = findWordAt(x, y)

                if (hit != null) {
                    toggleHighlight(hit)
                } else {
                    // Nothing recognized at this position.
                    clearHighlight()
                }

                dispatchTapToApp(x.toFloat(), y.toFloat())
                return true
            }
        }

        return true
    }

    private fun dispatchTapToApp(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val path = android.graphics.Path().apply {
            moveTo(x, y)
        }

        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path,
                    0L,
                    40L
                )
            )
            .build()

        dispatchGesture(
            gesture,
            null,
            null
        )
    }

    private fun findWordAt(x: Int, y: Int): WordHit? {
        val root = rootInActiveWindow ?: return null
        val nodes = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
        collectTextNodes(root, nodes, 0)

        var best: WordHit? = null
        var bestArea = Long.MAX_VALUE

        for (node in nodes) {
            val text = node.text?.toString() ?: continue
            if (text.isBlank()) continue

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.width() <= 0 || bounds.height() <= 0) continue
            if (!bounds.contains(x, y)) continue

            val hit = exactCharacterHit(node, text, x, y)
                ?: approximateWordHit(text, bounds, x, y)

            if (hit != null) {
                val area = bounds.width().toLong() * bounds.height().toLong()
                if (area < bestArea) {
                    bestArea = area
                    best = hit
                }
            }
        }

        return best
    }

    private fun collectTextNodes(
        node: android.view.accessibility.AccessibilityNodeInfo,
        out: MutableList<android.view.accessibility.AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 40 || out.size > 1500) return

        if (!node.text.isNullOrBlank()) {
            if (node.isVisibleToUser) {
                out += node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextNodes(child, out, depth + 1)
        }
    }

    private fun exactCharacterHit(
        node: android.view.accessibility.AccessibilityNodeInfo,
        text: String,
        x: Int,
        y: Int
    ): WordHit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null

        val key = android.view.accessibility.AccessibilityNodeInfo
            .EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY

        val args = Bundle().apply {
            putInt(
                android.view.accessibility.AccessibilityNodeInfo
                    .EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX,
                0
            )
            putInt(
                android.view.accessibility.AccessibilityNodeInfo
                    .EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,
                text.length
            )
        }

        try {
            val method: Method =
                android.view.accessibility.AccessibilityNodeInfo::class.java
                    .getMethod(
                        "refreshWithExtraData",
                        String::class.java,
                        Bundle::class.java
                    )

            method.invoke(node, key, args)

            @Suppress("DEPRECATION")
            val rects = node.extras.getParcelableArrayList<Rect>(key)
                ?: return null

            val max = minOf(text.length, rects.size)

            for (i in 0 until max) {
                val rect = rects[i] ?: continue
                if (rect.width() <= 0 || rect.height() <= 0) continue

                if (rect.contains(x, y)) {
                    val wordRange = findWordRange(text, i) ?: return null
                    val start = wordRange.first
                    val end = wordRange.second

                    val wordRect = unionCharacterRects(rects, start, end)
                        ?: return null

                    return WordHit(
                        word = text.substring(start, end),
                        rect = wordRect,
                        exact = true
                    )
                }
            }
        } catch (_: Exception) {
            // Some apps do not expose character locations.
        }

        return null
    }

    private fun approximateWordHit(
        text: String,
        bounds: Rect,
        x: Int,
        y: Int
    ): WordHit? {
        val regex = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’_-]*")
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return null

        val totalTextLength = text.length.coerceAtLeast(1)

        for (match in matches) {
            val startRatio = match.range.first.toFloat() / totalTextLength
            val endRatio = (match.range.last + 1).toFloat() / totalTextLength

            val left = bounds.left +
                (bounds.width() * startRatio).toInt()
            val right = bounds.left +
                (bounds.width() * endRatio).toInt()

            if (x in left..right && y in bounds.top..bounds.bottom) {
                return WordHit(
                    word = match.value,
                    rect = Rect(
                        left,
                        bounds.top,
                        right.coerceAtLeast(left + 1),
                        bounds.bottom
                    ),
                    exact = false
                )
            }
        }

        return null
    }

    private fun findWordRange(text: String, index: Int): Pair<Int, Int>? {
        if (index !in text.indices) return null

        fun isWordChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '-' || c == '\'' || c == '’'

        if (!isWordChar(text[index])) return null

        var start = index
        var end = index + 1

        while (start > 0 && isWordChar(text[start - 1])) {
            start--
        }

        while (end < text.length && isWordChar(text[end])) {
            end++
        }

        return start to end
    }

    private fun unionCharacterRects(
        rects: ArrayList<Rect>,
        start: Int,
        end: Int
    ): Rect? {
        var result: Rect? = null

        for (i in start until minOf(end, rects.size)) {
            val r = rects[i] ?: continue
            if (r.width() <= 0 || r.height() <= 0) continue

            if (result == null) {
                result = Rect(r)
            } else {
                result.union(r)
            }
        }

        return result
    }

    private fun toggleHighlight(hit: WordHit) {
        val sameWord = lastHighlightedWord == hit.word
        val sameRect = lastHighlightedRect?.let {
            abs(it.left - hit.rect.left) <= 2 &&
                abs(it.top - hit.rect.top) <= 2 &&
                abs(it.right - hit.rect.right) <= 2 &&
                abs(it.bottom - hit.rect.bottom) <= 2
        } == true

        if (sameWord && sameRect) {
            clearHighlight()
            return
        }

        clearHighlight()

        val root = touchOverlay ?: return

        val highlight = View(this).apply {
            setBackgroundColor(0x66FFF200)
            isClickable = false
            isFocusable = false
        }

        val label = TextView(this).apply {
            text = if (hit.exact) {
                "✓ ${hit.word}"
            } else {
                "~ ${hit.word}"
            }
            setTextColor(Color.BLACK)
            textSize = 11f
            setBackgroundColor(0xEEFFFFFF.toInt())
            setPadding(dp(5), dp(2), dp(5), dp(2))
            isClickable = false
            isFocusable = false
        }

        val highlightParams = FrameLayout.LayoutParams(
            hit.rect.width().coerceAtLeast(dp(2)),
            hit.rect.height().coerceAtLeast(dp(2))
        ).apply {
            leftMargin = hit.rect.left
            topMargin = hit.rect.top
        }

        val labelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = hit.rect.left
            topMargin = (hit.rect.top - dp(22)).coerceAtLeast(0)
        }

        root.addView(highlight, highlightParams)
        root.addView(label, labelParams)

        highlightView = highlight
        highlightLabel = label
        lastHighlightedWord = hit.word
        lastHighlightedRect = Rect(hit.rect)
    }

    private fun clearHighlight() {
        highlightView?.let { view ->
            try {
                touchOverlay?.removeView(view)
            } catch (_: Exception) {
            }
        }

        highlightLabel?.let { view ->
            try {
                touchOverlay?.removeView(view)
            } catch (_: Exception) {
            }
        }

        highlightView = null
        highlightLabel = null
        lastHighlightedWord = null
        lastHighlightedRect = null
    }

    private fun hideTouchOverlay() {
        clearHighlight()

        touchOverlay?.let { root ->
            try {
                windowManager?.removeView(root)
            } catch (_: Exception) {
            }
        }

        touchOverlay = null
    }

    override fun onInterrupt() {
        setToggleMode(false)
    }

    override fun onDestroy() {
        setToggleMode(false)

        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        var instance: WordAccessibilityService? = null
            private set
    }
}
