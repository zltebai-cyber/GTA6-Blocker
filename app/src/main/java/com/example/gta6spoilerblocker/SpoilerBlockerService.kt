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
    private var preShieldView: LinearLayout? = null
    private val cardOverlays = mutableListOf<View>()
    private var isMutedByApp = false

    private var allowedFullscreenSignature: String? = null
    private val allowedCardSignatures = mutableSetOf<String>()
    private var lastPreShieldTime: Long = 0

    private val handler = Handler(Looper.getMainLooper())
    private var preShieldTimeoutRunnable: Runnable? = null
    private var followUpScanRunnable: Runnable? = null

    private val defaultKeywords = setOf(
        "gta 6", "gta6", "gta vi", "gtavi", "grand theft auto 6", "grand theft auto vi",
        "قراند 6", "قراند6", "جي تي اي 6", "جي تي أي 6", "حرق قراند", "تختيم قراند",
        "نهاية قراند", "تسريب قراند", "leonida", "vice city", "lucia", "jason",
        "لوسيا", "جيسون", "ليونيدا"
    )

    private val strongCompactMatches = listOf(
        "gta6", "gtavi", "grandtheftauto6", "grandtheftautovi",
        "قراند6", "جيتياي6", "جتا6", "جاتا6", "غتا6",
        "ليونيدا", "leonida", "فايسسيتي", "vicecity"
    )

    private val groupAAnchors = listOf(
        "gta", "grandtheftauto", "قراند", "قرااند", "جيتياي", "جتا", "جاتا", "غتا",
        "روكستار", "rockstar", "ليونيدا", "leonida", "لوسيا", "lucia", "فايسسيتي", "vicecity"
    )

    private val groupBSignals = listOf(
        "حرق", "تسريب", "تختيم", "نهايه", "قصه", "مهمه", "قيمبلاي", "جيسون", "خريطه",
        "تريلر", "شخصيه", "gameplay", "spoiler", "leak", "ending", "story", "trailer", "jason", "map"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val prefs = getSharedPreferences("gta6_blocker_prefs", Context.MODE_PRIVATE)

        if (!prefs.getBoolean("protection_enabled", true)) {
            clearCardOverlays()
            removePreShield()
            removeFullOverlay()
            unmuteMedia()
            return
        }

        val packageName = event?.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        val protectedApps = prefs.getStringSet("protected_apps", emptySet()) ?: emptySet()
        if (!protectedApps.contains(packageName)) {
            clearCardOverlays()
            removePreShield()
            return
        }

        if (fullOverlayView != null) return

        val isTikTok = packageName.contains("musically") || packageName.contains("trill")
        val isPreShieldEnabled = prefs.getBoolean("pre_shield_enabled", true)

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            clearCardOverlays()
            val rootNode = rootInActiveWindow
            if (isTikTok && isPreShieldEnabled && rootNode != null) {
                val now = System.currentTimeMillis()
                val sw = resources.displayMetrics.widthPixels
                val sh = resources.displayMetrics.heightPixels
                if (now - lastPreShieldTime > 850 &&
                    preShieldView == null &&
                    isTikTokFullscreenPlayer(rootNode, sw, sh) &&
                    !isCommentsSheetOpen(rootNode)
                ) {
                    lastPreShieldTime = now
                    activatePreShield(packageName)
                }
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            clearCardOverlays()
        }

        // فحص فوري بدون أي تأخير (0ms)
        scanScreen(packageName)
    }

    private fun activatePreShield(packageName: String) {
        if (preShieldView != null || fullOverlayView != null) return

        muteMedia()

        val shield = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createFrostedBlurDrawable()
        }

        val label = TextView(this).apply {
            text = "⚡ جاري فحص الفيديو..."
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        shield.addView(label)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            preShieldView = shield
            windowManager.addView(preShieldView, params)
        } catch (_: Exception) {
            preShieldView = null
        }

        // إعادة فحص سريعة أثناء تحميل وصف الفيديو الجديد
        followUpScanRunnable?.let { handler.removeCallbacks(it) }
        followUpScanRunnable = Runnable {
            scanScreen(packageName)
            handler.postDelayed({ scanScreen(packageName) }, 170)
        }
        handler.postDelayed(followUpScanRunnable!!, 170)

        // إزالة الدرع تلقائياً بعد 460 ملي ثانية إذا كان الفيديو آمناً
        preShieldTimeoutRunnable?.let { handler.removeCallbacks(it) }
        preShieldTimeoutRunnable = Runnable {
            removePreShield()
            if (fullOverlayView == null) {
                unmuteMedia()
            }
        }
        handler.postDelayed(preShieldTimeoutRunnable!!, 460)
    }

    private fun scanScreen(packageName: String) {
        if (fullOverlayView != null) return

        val rootNode = rootInActiveWindow ?: return
        val prefs = getSharedPreferences("gta6_blocker_prefs", Context.MODE_PRIVATE)
        val rawKeywords = prefs.getStringSet("keywords_set", defaultKeywords) ?: defaultKeywords
        val normalizedCustomKeywords = rawKeywords.map { normalizeSpaced(it) }.filter { it.length >= 2 }

        val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
        findMatchingNodes(rootNode, normalizedCustomKeywords, matchingNodes)

        clearCardOverlays()
        if (matchingNodes.isEmpty()) return

        val isTikTok = packageName.contains("musically") || packageName.contains("trill")
        val isYouTube = packageName.contains("youtube")
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels

        // 1. تيك توك أثناء تشغيل الفيديو
        if (isTikTok && isTikTokFullscreenPlayer(rootNode, screenWidth, screenHeight)) {
            val videoSig = buildFullscreenSignature(rootNode)
            if (videoSig != allowedFullscreenSignature) {
                cancelPreShieldTimers()
                removePreShield()
                muteMedia()
                showFullBlockerOverlay(isTikTok = true, signature = videoSig)
            }
            return
        }

        // 2. يوتيوب أثناء فتح فيديو أو بث مباشر
        if (isYouTube && isYouTubePlayerActive(rootNode)) {
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

        // 3. البطاقات الخارجية (مثل يوتيوب الرئيسية والبحث والمقترحات)
        val coveredRects = mutableListOf<Rect>()
        for (node in matchingNodes) {
            if (cardOverlays.size >= 6) break

            val nodeRect = Rect()
            node.getBoundsInScreen(nodeRect)
            if (nodeRect.top < 170 && nodeRect.height() < 160) continue

            val cardSig = buildNodeSignature(node)
            if (allowedCardSignatures.contains(cardSig)) continue

            val cardRect = findBestCardRect(node, screenWidth, screenHeight) ?: continue
            if (coveredRects.any { Rect.intersects(it, cardRect) }) continue

            coveredRects.add(cardRect)
            showCardBlurOverlay(cardRect, cardSig)
        }
    }

    // محرك الذكاء اللغوي المحلي لتطبيع النص وفهم السياق والتمويه
    private fun isSpoilerText(raw: String, customKeywords: List<String>): Boolean {
        val spaced = normalizeSpaced(raw)
        if (spaced.length < 3) return false
        val compact = spaced.replace(" ", "")

        // استثناء GTA 5 و San Andreas إذا لم يكن هناك ذكر للجزء السادس
        val mentionsGta6Specific = compact.contains("6") ||
                compact.contains("لوسيا") || compact.contains("lucia") ||
                compact.contains("ليونيدا") || compact.contains("leonida") ||
                Regex("\\bvi\\b").containsMatchIn(spaced)

        if (!mentionsGta6Specific &&
            (compact.contains("gta5") || compact.contains("قراند5") ||
                    compact.contains("gtav") || compact.contains("ساناندرياس") ||
                    compact.contains("sanandreas"))
        ) {
            return false
        }

        // 1. فحص الكلمات القوية والمموهة (مثل G.T.A 6 و قراند٦)
        if (strongCompactMatches.any { compact.contains(it) }) return true

        // 2. فحص gta + vi بحدود الكلمات حتى لا تتداخل مع كلمة video
        if (compact.contains("gta") && Regex("\\bvi\\b").containsMatchIn(spaced)) return true

        // 3. فحص كلمات القالب المخصصة من المستخدم
        for (kw in customKeywords) {
            if (spaced.contains(kw)) return true
            val kwCompact = kw.replace(" ", "")
            if (kwCompact.length >= 4 && compact.contains(kwCompact)) return true
        }

        // 4. فحص السياق الذكي (مرساة GTA/روكستار + مؤشر حرق/شخصية/6)
        val hasAnchor = groupAAnchors.any { compact.contains(it) }
        if (hasAnchor) {
            val hasStandalone6 = Regex("\\b6\\b").containsMatchIn(spaced)
            val hasSignal = hasStandalone6 || groupBSignals.any { compact.contains(it) }
            if (hasSignal) return true
        }

        return false
    }

    private fun normalizeSpaced(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase()) {
            when (ch) {
                'أ', 'إ', 'آ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ة' -> sb.append('ه')
                '٠' -> sb.append('0')
                '١' -> sb.append('1')
                '٢' -> sb.append('2')
                '٣' -> sb.append('3')
                '٤' -> sb.append('4')
                '٥' -> sb.append('5')
                '٦' -> sb.append('6')
                '٧' -> sb.append('7')
                '٨' -> sb.append('8')
                '٩' -> sb.append('9')
                in '\u064B'..'\u065F', '\u0670', 'ـ' -> { }
                '.', '-', '_', '/', '#', '@', '!', '?', ':', '،', ',', '|', '(', ')' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun isCommentsSheetOpen(root: AccessibilityNodeInfo): Boolean {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var checked = 0
        while (q.isNotEmpty() && checked < 60) {
            val n = q.removeFirst()
            checked++
            val t = (n.text ?: "").toString().lowercase()
            if (t.contains("add comment") || t.contains("أضف تعليق") || t == "comments" || t == "التعليقات") {
                return true
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { q.add(it) }
            }
        }
        return false
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
        while (queue.isNotEmpty() && checked < 160) {
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
        while (queue.isNotEmpty() && checked < 160) {
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
        customKeywords: List<String>,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null || !node.isVisibleToUser) return

        val resId = (node.viewIdResourceName ?: "").lowercase()
        if (!node.isEditable && !resId.contains("search")) {
            val raw = "${node.text ?: ""} ${node.contentDescription ?: ""}"
            if (raw.isNotBlank() && isSpoilerText(raw, customKeywords)) {
                results.add(node)
                return
            }
        }

        for (i in 0 until node.childCount) {
            findMatchingNodes(node.getChild(i), customKeywords, results)
        }
    }

    private fun findBestCardRect(node: AccessibilityNodeInfo, screenW: Int, screenH: Int): Rect? {
        val baseRect = Rect()
        node.getBoundsInScreen(baseRect)
        var bestRect = Rect(baseRect)

        var current: AccessibilityNodeInfo? = node
        var parent = current?.parent
        var steps = 0

        while (parent != null && steps < 14) {
            val parentCls = parent.className?.toString() ?: ""
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
