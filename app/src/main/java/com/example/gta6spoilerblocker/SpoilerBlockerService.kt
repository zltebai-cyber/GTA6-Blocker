package com.example.gta6spoilerblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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

    private var allowedFullscreenSignature: String? = null
    private val allowedCardSignatures = mutableSetOf<String>()

    private val handler = Handler(Looper.getMainLooper())
    private var rescanRunnable: Runnable? = null

    private val defaultKeywords = setOf(
        "gta 6", "gta6", "gta vi", "gtavi", "grand theft auto 6", "grand theft auto vi",
        "قراند 6", "قراند6", "جي تي اي 6", "جي تي أي 6", "حرق قراند", "تختيم قراند",
        "نهاية قراند", "تسريب قراند", "leonida", "vice city", "lucia", "jason",
        "لوسيا", "جيسون", "ليونيدا"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val prefs = getSharedPreferences("gta6_blocker_prefs", Context.MODE_PRIVATE)

        if (!prefs.getBoolean("protection_enabled", true)) {
            clearCardOverlays()
            removeFullOverlay()
            unmuteMedia()
            return
        }

        val packageName = event?.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        val protectedApps = prefs.getStringSet("protected_apps", emptySet()) ?: emptySet()
        if (!protectedApps.contains(packageName)) {
            clearCardOverlays()
            return
        }

        if (fullOverlayView != null) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            clearCardOverlays()
            scheduleScan(packageName, 90)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            clearCardOverlays()
            scheduleScan(packageName, 30)
            return
        }

        scheduleScan(packageName, 70)
    }

    private fun scheduleScan(packageName: String, delayMs: Long) {
        rescanRunnable?.let { handler.removeCallbacks(it) }
        rescanRunnable = Runnable { scanScreen(packageName) }
        handler.postDelayed(rescanRunnable!!, delayMs)
    }

    private fun scanScreen(packageName: String) {
        if (fullOverlayView != null) return

        val rootNode = rootInActiveWindow ?: return
        val prefs = getSharedPreferences("gta6_blocker_prefs", Context.MODE_PRIVATE)
        val keywords = prefs.getStringSet("keywords_set", defaultKeywords) ?: defaultKeywords

        val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
        findMatchingNodes(rootNode, keywords, matchingNodes)

        clearCardOverlays()
        if (matchingNodes.isEmpty()) return

        val isTikTok = packageName.contains("musically") || packageName.contains("trill")
        val isYouTube = packageName.contains("youtube")
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels

        // 1. حالة تيك توك أثناء تشغيل فيديو
        if (isTikTok && isTikTokFullscreenPlayer(rootNode, screenWidth, screenHeight)) {
            val videoSig = buildFullscreenSignature(rootNode)
            if (videoSig != allowedFullscreenSignature) {
                muteMedia()
                showFullBlockerOverlay(isTikTok = true, signature = videoSig)
            }
            return
        }

        // 2. حالة يوتيوب أثناء فتح فيديو أو بث مباشر
        if (isYouTube && isYouTubePlayerActive(rootNode)) {
            // نفحص هل كلمة الحرق موجودة في عنوان/معلومات الفيديو نفسه (أعلى 60% من الشاشة) أم فقط في المقترحات بالأسفل
            val mainVideoHasSpoiler = matchingNodes.any { node ->
                val r = Rect()
                node.getBoundsInScreen(r)
                r.top < screenHeight * 0.60
            }

            if (mainVideoHasSpoiler) {
                val videoSig = buildFullscreenSignature(rootNode)
                if (videoSig != allowedFullscreenSignature) {
                    muteMedia()
                    showFullBlockerOverlay(isTikTok = false, signature = videoSig)
                    return
                }
            }
        }

        // 3. حالة تصفح القائمة الخارجية في يوتيوب أو المقترحات أسفل الفيديو
        val coveredRects = mutableListOf<Rect>()
        for (node in matchingNodes) {
            if (cardOverlays.size >= 6) break

            val nodeRect = Rect()
            node.getBoundsInScreen(nodeRect)
            // تجاهل شريط البحث والأزرار العلوية الصغيرة جداً
            if (nodeRect.top < 170 && nodeRect.height() < 160) continue

            val cardSig = buildNodeSignature(node)
            if (allowedCardSignatures.contains(cardSig)) continue

            val cardRect = findBestCardRect(node, screenWidth, screenHeight) ?: continue
            if (coveredRects.any { Rect.intersects(it, cardRect) }) continue

            coveredRects.add(cardRect)
            showCardBlurOverlay(cardRect, cardSig)
        }
    }

    private fun buildFullscreenSignature(root: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var count = 0
        while (q.isNotEmpty() && count < 90 && parts.size < 6) {
            val n = q.removeFirst()
            count++
            val t = (n.text ?: "").toString().trim()
            if (t.length >= 3 && !t.all { it.isDigit() || it == ':' }) {
                parts.add(t.take(40))
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { q.add(it) }
            }
        }
        return parts.joinToString("|")
    }

    private fun buildNodeSignature(node: AccessibilityNodeInfo): String {
        return "${node.text ?: ""}_${node.contentDescription ?: ""}".trim().take(100)
    }

    private fun isYouTubePlayerActive(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var checked = 0
        while (queue.isNotEmpty() && checked < 180) {
            val node = queue.removeFirst()
            checked++
            val resId = node.viewIdResourceName ?: ""
            if (resId.contains("watch_player") ||
                resId.contains("player_fragment_container") ||
                resId.contains("reel_player_page_container") ||
                resId.contains("next_gen_watch_layout")
            ) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.height() > 260) return true
            }
            val desc = (node.contentDescription ?: "").toString().lowercase()
            if (desc.contains("enter fullscreen") ||
                desc.contains("ملء الشاشة") ||
                desc.contains("pause video") ||
                desc.contains("إيقاف الفيديو")
            ) {
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun isTikTokFullscreenPlayer(root: AccessibilityNodeInfo, screenW: Int, screenH: Int): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var checked = 0
        while (queue.isNotEmpty() && checked < 180) {
            val node = queue.removeFirst()
            checked++
            val desc = (node.contentDescription ?: "").toString().lowercase()
            if (desc.contains("like") ||
                desc.contains("إعجاب") ||
                desc.contains("أعجبني") ||
                desc.contains("comment") ||
                desc.contains("تعليق") ||
                desc.contains("share") ||
                desc.contains("مشاركة") ||
                desc.contains("صوت أصلي") ||
                desc.contains("original sound")
            ) {
                return true
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > screenW * 0.85 && rect.height() > screenH * 0.55) {
                val cls = node.className?.toString() ?: ""
                if (cls.contains("ViewPager") || cls.contains("SurfaceView") || cls.contains("TextureView")) {
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun findMatchingNodes(
        node: AccessibilityNodeInfo?,
        keywords: Set<String>,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null || !node.isVisibleToUser) return

        val resId = (node.viewIdResourceName ?: "").lowercase()
        if (!node.isEditable && !resId.contains("search")) {
            val text = "${node.text ?: ""} ${node.contentDescription ?: ""}".lowercase()
            if (text.isNotBlank() && keywords.any { text.contains(it) }) {
                results.add(node)
                return
            }
        }

        for (i in 0 until node.childCount) {
            findMatchingNodes(node.getChild(i), keywords, results)
        }
    }

    // الصعود حتى نجد حاوية البطاقة الكاملة (صورة العرض + العنوان والوصف معاً)
    private fun findBestCardRect(node: AccessibilityNodeInfo, screenW: Int, screenH: Int): Rect? {
        val baseRect = Rect()
        node.getBoundsInScreen(baseRect)
        var bestRect = Rect(baseRect)

        var current: AccessibilityNodeInfo? = node
        var parent = current?.parent
        var steps = 0

        while (parent != null && steps < 14) {
            val parentCls = parent.className?.toString() ?: ""
            // إذا وصلنا للقائمة الأم (RecyclerView) فالابن الحالي هو البطاقة الكاملة بالضبط
            if (parentCls.contains("RecyclerView") || parentCls.contains("ListView")) {
                val cRect = Rect()
                current?.getBoundsInScreen(cRect)
                if (cRect.height() in 140..(screenH * 0.72).toInt() && cRect.width() >= screenW * 0.40) {
                    bestRect = cRect
                }
                break
            }

            val pRect = Rect()
            parent.getBoundsInScreen(pRect)
            val goodWidth = pRect.width() >= screenW * 0.40
            val goodHeight = pRect.height() in 160..(screenH * 0.68).toInt()
            if (goodWidth && goodHeight) {
                bestRect = pRect
            }

            current = parent
            parent = parent.parent
            steps++
        }

        if (bestRect.width() < 100 || bestRect.height() < 70) return null
        return bestRect
    }

    // خلفية ضبابية ناعمة غير شفافة تماماً تمنع ظهور أي حركة من الفيديو
    private fun createFrostedBlurDrawable(): Drawable {
        val w = 90
        val h = 160
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#120E1E"))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = RadialGradient(
            w * 0.25f, h * 0.25f, w * 0.85f,
            intArrayOf(Color.parseColor("#66FF2E93"), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        paint.shader = RadialGradient(
            w * 0.8f, h * 0.75f, w * 0.9f,
            intArrayOf(Color.parseColor("#5500D4FF"), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        paint.shader = RadialGradient(
            w * 0.5f, h * 0.5f, w * 0.7f,
            intArrayOf(Color.parseColor("#44FF8A00"), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        return BitmapDrawable(resources, bmp).apply {
            isFilterBitmap = true
        }
    }

    // تغبيش ضبابي كامل لصورة العرض والوصف في اليوتيوب
    private fun showCardBlurOverlay(rect: Rect, cardSig: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#FF24132E"),
                    Color.parseColor("#FF141122"),
                    Color.parseColor("#FF102432")
                )
            ).apply {
                cornerRadius = 24f
                setStroke(3, Color.parseColor("#FF2E93"))
            }
            setPadding(24, 16, 24, 16)
        }

        val msg = TextView(this).apply {
            text = "🌫️ هذا محتوى حرق لـ GTA 6\nهل تريد عرض الصورة والوصف؟"
            textSize = if (rect.height() < 220) 13f else 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 14)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val yesBtn = Button(this).apply {
            text = "متابعة"
            textSize = 13f
            setTextColor(Color.WHITE)
            minHeight = 0
            minimumHeight = 0
            setPadding(28, 12, 28, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00C853"))
                cornerRadius = 18f
            }
            setOnClickListener {
                allowedCardSignatures.add(cardSig)
                clearCardOverlays()
                unmuteMedia()
            }
        }

        val gap = View(this).apply { layoutParams = LinearLayout.LayoutParams(24, 1) }

        val noBtn = Button(this).apply {
            text = "لا"
            textSize = 13f
            setTextColor(Color.WHITE)
            minHeight = 0
            minimumHeight = 0
            setPadding(36, 12, 36, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D50000"))
                cornerRadius = 18f
            }
            setOnClickListener {
                card.removeAllViews()
                card.addView(TextView(this@SpoilerBlockerService).apply {
                    text = "🌫️ محتوى GTA 6 محجوب"
                    textSize = 14f
                    setTextColor(Color.parseColor("#CCCCCC"))
                    gravity = Gravity.CENTER
                })
            }
        }

        btnRow.addView(yesBtn)
        btnRow.addView(gap)
        btnRow.addView(noBtn)

        card.addView(msg)
        card.addView(btnRow)

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

    // تغطية ضبابية كاملة 100% من الحافة للحافة مع كتم الصوت
    private fun showFullBlockerOverlay(isTikTok: Boolean, signature: String) {
        if (fullOverlayView != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createFrostedBlurDrawable()
            setPadding(55, 55, 55, 55)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6161124"))
                cornerRadius = 36f
                setStroke(4, Color.parseColor("#FF2E93"))
            }
            setPadding(50, 60, 50, 60)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val textView = TextView(this).apply {
            text = "🌫️🔇\n\nهذا محتوى حرق لـ GTA 6\n\nهل تريد متابعة المشاهدة أو لا؟"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 50)
        }

        val continueBtn = Button(this).apply {
            text = "متابعة"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00C853"))
                cornerRadius = 25f
            }
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                allowedFullscreenSignature = signature
                removeFullOverlay()
                unmuteMedia()
            }
        }

        val gap = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 25) }

        val cancelBtn = Button(this).apply {
            text = "إلغاء"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D50000"))
                cornerRadius = 25f
            }
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                allowedFullscreenSignature = null
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

        card.addView(textView)
        card.addView(continueBtn)
        card.addView(gap)
        card.addView(cancelBtn)
        root.addView(card)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        fullOverlayView = root
        windowManager.addView(fullOverlayView, params)
    }

    private fun clearCardOverlays() {
        for (v in cardOverlays) {
            try {
                windowManager.removeViewImmediate(v)
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
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, dm.heightPixels * 0.78f)
            lineTo(x, dm.heightPixels * 0.18f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 180))
            .build()
        dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        clearCardOverlays()
        removeFullOverlay()
        unmuteMedia()
    }
}
