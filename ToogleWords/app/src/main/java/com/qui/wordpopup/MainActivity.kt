package com.qui.wordpopup

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.overlayButton).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                statusText.text = "⚠️ Hãy cấp quyền \"Hiển thị trên ứng dụng khác\" trước."
                return@setOnClickListener
            }

            if (!isAccessibilityEnabled()) {
                statusText.text = "⚠️ Hãy bật Word Popup trong Trợ năng trước."
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }

            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, PopupService::class.java)
                )
                statusText.text =
                    "✅ Bong bóng đang chạy. Bấm bong bóng để vào/thoát Toggle Mode."
            } catch (e: Exception) {
                statusText.text = "❌ Không khởi động được: ${e.message}"
            }
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, PopupService::class.java))
            WordAccessibilityService.instance?.setToggleMode(false)
            statusText.text = "Đã dừng bong bóng."
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) {
            updateStatus()
        }
    }

    private fun updateStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val accessibility = isAccessibilityEnabled()

        statusText.text = buildString {
            append("Overlay: ")
            append(if (overlay) "✅" else "❌")
            append("    Trợ năng: ")
            append(if (accessibility) "✅" else "❌")
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(
            this,
            WordAccessibilityService::class.java
        ).flattenToString()

        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabled.split(':').any {
            it.equals(expected, ignoreCase = true)
        }
    }
}
