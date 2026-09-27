package com.qui.wordpopup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class PopupService : Service() {

    private var bubbleView: View? = null
    private var windowManager: WindowManager? = null
    private lateinit var bubbleText: TextView

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        showBubble()
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) return
        if (bubbleView != null) return

        val view = LayoutInflater.from(this).inflate(
            R.layout.bubble_view,
            null
        )
        bubbleText = view.findViewById(R.id.bubbleText)

        val params = WindowManager.LayoutParams(
            dp(56),
            dp(56),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(180)
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY

                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        moved = true
                    }

                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()

                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        handleBubbleClick()
                    }
                    true
                }

                else -> true
            }
        }

        try {
            windowManager?.addView(view, params)
            bubbleView = view
        } catch (_: Exception) {
            bubbleView = null
        }
    }

    private fun handleBubbleClick() {
        val service = WordAccessibilityService.instance

        if (service == null) {
            statusText("⚠️ Chưa bật Accessibility Service.")
            try {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
            }
            return
        }

        service.toggleMode()
    }

    fun setToggleModeVisual(enabled: Boolean) {
        if (!::bubbleText.isInitialized) return

        bubbleText.text = if (enabled) "✓" else "💬"
        bubbleText.setTextColor(
            if (enabled) Color.WHITE else Color.WHITE
        )
        bubbleText.alpha = if (enabled) 1f else 0.92f
    }

    private fun statusText(message: String) {
        // Status is intentionally kept out of the overlay.
        // MainActivity will show permission state when it resumes.
    }

    override fun onDestroy() {
        WordAccessibilityService.instance?.setToggleMode(false)

        bubbleView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }

        bubbleView = null
        if (instance === this) instance = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Word Popup",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Word Popup")
            .setContentText("Bong bóng đang chạy")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val CHANNEL_ID = "word_popup"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var instance: PopupService? = null
            private set
    }
}
