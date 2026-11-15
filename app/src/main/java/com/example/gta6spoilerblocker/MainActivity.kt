package com.example.gta6spoilerblocker

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("gta6_blocker_prefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(60, 120, 60, 60)
            setBackgroundColor(Color.parseColor("#12101B"))
        }

        val header = TextView(this).apply {
            text = "🛡️ درع الحماية من حرق GTA 6"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 60)
        }

        val protectionSwitch = Switch(this).apply {
            text = "تفعيل الحماية في النظام كله  "
            textSize = 17f
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("protection_enabled", true)
            setPadding(0, 30, 0, 30)
            setOnCheckedChangeListener { buttonView, isChecked ->
                prefs.edit().putBoolean("protection_enabled", isChecked).apply()
            }
        }

        val muteSwitch = Switch(this).apply {
            text = "كتم الصوت تلقائياً عند رصد حرق  "
            textSize = 17f
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("auto_mute_enabled", true)
            setPadding(0, 30, 0, 60)
            setOnCheckedChangeListener { buttonView, isChecked ->
                prefs.edit().putBoolean("auto_mute_enabled", isChecked).apply()
            }
        }

        val btnBg = GradientDrawable().apply {
            setColor(Color.parseColor("#FF2E93"))
            cornerRadius = 25f
        }

        val permissionButton = Button(this).apply {
            text = "⚙️ تفعيل إذن الخدمة من إعدادات السامسونج"
            setTextColor(Color.WHITE)
            background = btnBg
            setPadding(30, 30, 30, 30)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        layout.addView(header)
        layout.addView(protectionSwitch)
        layout.addView(muteSwitch)
        layout.addView(permissionButton)

        setContentView(layout)
    }
}
