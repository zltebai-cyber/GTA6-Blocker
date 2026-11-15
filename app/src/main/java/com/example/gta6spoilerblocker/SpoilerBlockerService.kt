package com.example.gta6spoilerblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SpoilerBlockerService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var fullOverlayView: LinearLayout? = null
    private val cardOverlays = mutableListOf<View>()
    private var isMutedByApp = false
    private var allowUntilTime: Long = 0

    private val handler = Handler(Looper.getMainLooper())
    private var rescanRunnable: Runnable? = null

    private val ignoredPackages = setOf(
        "com.example.gta6spoilerblocker",
        "com.android.systemui",
        "com.android.settings",
        "com.samsung.android.honeyboard",
        "com.google.android.inputmethod.latin",
        "com.sec.android.app.launcher"
    )

    private val blockedKeywords = listOf(
        "gta 6", "gta6", "gta vi", "gtavi", "grand theft auto 6", "grand theft auto vi",
        "قراند 6", "قراند6", "جي تي اي 6", "حرق قراند", "تختيم قراند", "نهاية قراند",
        "leonida", "vice city", "lucia", "jason", "لوسيا", "جيسون", "ليونيدا"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val prefs = getSharedPreferences("gta6_blocker_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("protection_enabled", true)) {
            clearCardOverlays()
            return
        }

        if (System.currentTimeMillis() < allowUntilTime) return
        if (fullOverlayView != null) return

        val packageName = event?.packageName?.toString() ?: return
        if (ignoredPackages.contains(packageName)) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            clearCardOverlays()
            scheduleScan(packageName)
            return
        }

        scheduleScan(packageName)
    }

    private fun scheduleScan(packageName: String) {
        rescanRunnable?.let { handler.removeCallbacks(it) }
        rescanRunnable = Runnable { scanScreenForSpoilers(packageName) }
        handler.postDelayed(rescanRunnable!!, 150)
    }

    private fun scanScreenForSpoilers(packageName: String) {
        if (fullOverlayView != null) return
        val rootNode = rootInActiveWindow ?: return

        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels

        val matchingNodes = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        findMatchingTextNodes(rootNode, matchingNodes)

        clearCardOverlays()

        if (matchingNodes.isEmpty()) return

        val prefs = getSharedPreferences("gta6_blocker_prefs", Context.MODE_PRIVATE)
        val isAutoMuteEnabled = prefs.getBoolean("auto_mute_enabled", true)

        val isVideoApp = packageName.contains("youtube") ||
                packageName.contains("musically") ||
                packageName.contains("trill")

        val coveredRects = mutableListOf<Rect>()

        for ((node, keyword) in matchingNodes) {
            if (cardOverlays.size >= 8) break

            val targetRect = findBestContainerRect(node, screenWidth, screenHeight) ?: continue
            if (coveredRects.any { Rect.intersects(it, targetRect) }) continue

            if (isVideoApp && targetRect.height() > screenHeight * 0.65) {
                clearCardOverlays()
                if (isAutoMuteEnabled) muteMedia()
                val isTikTok = packageName.contains("musically") || packageName.contains("trill")
                showFullBlockerOverlay(keyword, isTikTok)
                return
            }

            coveredRects.add(targetRect)
            if (isAutoMuteEnabled && isVideoApp) muteMedia()
            showMiniSensitiveCardOverlay(targetRect, keyword)
        }
    }

    private fun findMatchingTextNodes(
        node: AccessibilityNodeInfo?,
        results: MutableList<Pair<AccessibilityNodeInfo, String>>
    ) {
        if (node == null || !node.isVisibleToUser) return

        if (!node.isEditable) {
            val combined = "${node.text ?: ""} ${node.contentDescription ?: ""}".lowercase()
            if (combined.isNotBlank()) {
                val found = blockedKeywords.firstOrNull { combined.contains(it) }
                if (found != null) {
                    results.add(Pair(node, found))
                    return
                }
            }
        }

        for (i in 0 until node.childCount) {
            findMatchingTextNodes(node.getChild(i), results)
        }
    }

    private fun findBestContainerRect(
        node: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int
    ): Rect? {
        val currentRect = Rect()
        node.getBoundsInScreen(currentRect)

        var bestRect = Rect(currentRect)
        var parent = node.parent
        var steps = 0

        while (parent != null && steps < 4) {
            val pRect = Rect()
            parent.getBoundsInScreen(pRect)

            val isGoodWidth = pRect.width() >= screenWidth * 0.25
            val isGoodHeight = pRect.height() in 100..(screenHeight * 0.55).toInt()

            if (isGoodWidth && isGoodHeight) {
                bestRect = pRect
            }
            parent = parent.parent
            steps++
        }

        if (bestRect.width() < 80 || bestRect.height() < 40) return null
        return bestRect
    }

    private fun showMiniSensitiveCardOverlay(rect: Rect, keyword: String) {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#F514121E"))
            cornerRadius = 24f
            setStroke(3, Color.parseColor("#FF2E93"))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg
            setPadding(20, 15, 20, 15)
        }

        val eyeBadge = TextView(this).apply {
            text = "🚫👁️ محتوى GTA 6 حساس ⚠️"
            textSize = if (rect.height() < 160) 12f else 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFD600"))
            gravity = Gravity.CENTER
        }

        val subText = TextView(this).apply {
            text = "محجوب لحفظك ($keyword) • اضغط للكشف"
            textSize = 11f
            setTextColor(Color.parseColor("#DDDDDD"))
            gravity = Gravity.CENTER
        }

        card.addView(eyeBadge)
        if (rect.height() >= 150) {
            card.addView(subText)
        }

        card.setOnClickListener {
            allowUntilTime = System.currentTimeMillis() + 8000
            clearCardOverlays()
            unmuteMedia()
        }

        val params = WindowManager.LayoutParams(
            rect.width(),
            rect.height(),
            rect.left,
            rect.top,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager.addView(card, params)
            cardOverlays.add(card)
        } catch (_: Exception) { }
    }

    private fun showFullBlockerOverlay(keyword: String, isTikTok: Boolean) {
        if (fullOverlayView != null) return

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F20A0A12"))
            setPadding(50, 50, 50, 50)
        }

        val cardBg = GradientDrawable().apply {
            setColor(Color.parseColor("#18122B"))
            cornerRadius = 40f
            setStroke(5, Color.parseColor("#FF2E93"))
        }

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = cardBg
            setPadding(50, 60, 50, 60)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val dangerSign = TextView(this).apply {
            text = "⚠️🚫 خطر حرق محتوى 🚫⚠️"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFD600"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }

        val logoBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#FF2E93"),
                Color.parseColor("#FF8A00"),
                Color.parseColor("#00D4FF")
            )
        ).apply {
            cornerRadius = 28f
            setStroke(4, Color.WHITE)
        }

        val gtaLogoBadge = TextView(this).apply {
            text = "  GTA VI  "
            textSize = 36f
            setTypeface(null, Typeface.BOLD_ITALIC)
            setTextColor(Color.WHITE)
            background = logoBg
            gravity = Gravity.CENTER
            setPadding(45, 25, 45, 25)
        }

        val messageText = TextView(this).apply {
            text = "\n🔇 هذا الفيديو يحتوي على محتوى خاص بـ GTA 6\n\nهل تريد الحفظ لنفسك أم لا؟"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 15)
        }

        val keywordHint = TextView(this).apply {
            text = "(الكلمة المرصودة: $keyword)"
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 45)
        }

        val saveBtnBg = GradientDrawable().apply {
            setColor(Color.parseColor("#00C853"))
            cornerRadius = 25f
        }
        val saveMyselfButton = Button(this).apply {
            text = if (isTikTok) "🛡️ نعم، أحفظ نفسي (تخطي)" else "🛡️ نعم، أحفظ نفسي (إغلاق)"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = saveBtnBg
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                removeFullOverlay()
                if (isTikTok) {
                    performSwipeUp()
                    handler.postDelayed({ unmuteMedia() }, 700)
                } else {
                    unmuteMedia()
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 25)
        }

        val watchBtnBg = GradientDrawable().apply {
            setColor(Color.parseColor("#393053"))
            cornerRadius = 25f
            setStroke(2, Color.parseColor("#FF5252"))
        }
        val watchAnywayButton = Button(this).apply {
            text = "👁️ لا، افتح الفيديو وأعد الصوت"
            textSize = 15f
            setTextColor(Color.parseColor("#FF8A80"))
            background = watchBtnBg
            setPadding(30, 25, 30, 25)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                allowUntilTime = System.currentTimeMillis() + 15000
                removeFullOverlay()
                unmuteMedia()
            }
        }

        cardLayout.addView(dangerSign)
        cardLayout.addView(gtaLogoBadge)
        cardLayout.addView(messageText)
        cardLayout.addView(keywordHint)
        cardLayout.addView(saveMyselfButton)
        cardLayout.addView(spacer)
        cardLayout.addView(watchAnywayButton)
        rootLayout.addView(cardLayout)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = 40
            }
        }

        fullOverlayView = rootLayout
        windowManager.addView(fullOverlayView, params)
    }

    private fun clearCardOverlays() {
        for (view in cardOverlays) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Exception) { }
        }
        cardOverlays.clear()
    }

    private fun removeFullOverlay() {
        fullOverlayView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (_: Exception) { }
            fullOverlayView = null
        }
    }

    private fun muteMedia() {
        if (!isMutedByApp) {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            isMutedByApp = true
        }
    }

    private fun unmuteMedia() {
        if (isMutedByApp) {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            isMutedByApp = false
        }
    }

    private fun performSwipeUp() {
        val displayMetrics = resources.displayMetrics
        val startX = displayMetrics.widthPixels / 2f
        val startY = displayMetrics.heightPixels * 0.75f
        val endY = displayMetrics.heightPixels * 0.25f

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
            .build()

        dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        clearCardOverlays()
        removeFullOverlay()
        unmuteMedia()
    }
}
