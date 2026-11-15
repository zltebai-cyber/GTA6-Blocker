package com.example.gta6spoilerblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.*
import android.graphics.drawable.*
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
    private var lastPreShieldTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var preShieldTimeoutRunnable: Runnable? = null
    private var followUpScanRunnable: Runnable? = null

    private val defaultKeywords = setOf(
        "gta 6", "gta6", "gta vi", "gtavi", "grand theft auto 6", "grand theft auto vi",
        "قراند 6", "قراند6", "جي تي اي 6", "جي تي أي 6", "حرق قراند", "تختيم قراند",
        "نهاية قراند", "تسريب قراند", "leonida", "vice city", "lucia", "jason",
        "لوسيا", "جيسون", "ليونيدا"
    )

    private val strongMatches = listOf(
        "gta6", "gtavi", "grandtheftauto6", "grandtheftautovi",
        "قراند6", "جيتياي6", "جتا6", "جاتا6", "غتا6",
        "ليونيدا", "leonida", "فايسسيتي", "vicecity"
    )

    private val anchors = listOf(
        "gta", "grandtheftauto", "قراند", "قرااند", "جيتياي", "جتا", "جاتا", "غتا",
        "روكستار", "rockstar", "ليونيدا", "leonida", "لوسيا", "lucia", "فايسسيتي", "vicecity"
    )

    private val signals = listOf(
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
            clearCardOverlays(); removePreShield(); removeFullOverlay(); unmuteMedia()
            return
        }

        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        val protectedApps = prefs.getStringSet("protected_apps", emptySet()) ?: emptySet()
        if (!protectedApps.contains(pkg)) {
            clearCardOverlays(); removePreShield()
            return
        }
        if (fullOverlayView != null) return

        val isTikTok = pkg.contains("musically") || pkg.contains("trill")
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            clearCardOverlays()
            val root = rootInActiveWindow
            if (isTikTok && prefs.getBoolean("pre_shield_enabled", true) && root != null) {
                val now = System.currentTimeMillis()
                val dm = resources.displayMetrics
                if (now - lastPreShieldTime > 850 && preShieldView == null &&
                    isTikTokFullscreenPlayer(root, dm.widthPixels, dm.heightPixels) &&
                    !isCommentsSheetOpen(root)
                ) {
                    lastPreShieldTime = now
                    activatePreShield(pkg)
                }
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            clearCardOverlays()
        }
        scanScreen(pkg)
    }

    private fun activatePreShield(pkg: String) {
        if (preShieldView != null || fullOverlayView != null) return
        muteMedia()
        val shield = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createFrostedBlurDrawable()
            addView(TextView(this@SpoilerBlockerService).apply {
                text = "⚡ جاري فحص الفيديو..."
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
        }
        val params = fullScreenParams(touchable = false)
        try { preShieldView = shield; windowManager.addView(preShieldView, params) } catch (_: Exception) { preShieldView = null }

        followUpScanRunnable?.let { handler.removeCallbacks(it) }
        followUpScanRunnable = Runnable {
            scanScreen(pkg)
            handler.postDelayed({ scanScreen(pkg) }, 170)
        }
        handler.postDelayed(followUpScanRunnable!!, 170)

        preShieldTimeoutRunnable?.let { handler.removeCallbacks(it) }
        preShieldTimeoutRunnable = Runnable {
            removePreShield()
            if (fullOverlayView == null) unmuteMedia()
        }
        handler.postDelayed(preShieldTimeoutRunnable!!, 460)
    }

    private fun scanScreen(pkg: String) {
        if (fullOverlayView != null) return
        val root = rootInActiveWindow ?: return
        val prefs = getSharedPreferences("gta6_blocker_prefs", Context.MODE_PRIVATE)
        val rawKw = prefs.getStringSet("keywords_set", defaultKeywords) ?: defaultKeywords
        val customKw = rawKw.map { normalizeSpaced(it) }.filter { it.length >= 2 }

        val matches = mutableListOf<AccessibilityNodeInfo>()
        findMatchingNodes(root, customKw, matches)
        clearCardOverlays()
        if (matches.isEmpty()) return

        val isTikTok = pkg.contains("musically") || pkg.contains("trill")
        val isYouTube = pkg.contains("youtube")
        val sh = resources.displayMetrics.heightPixels
        val sw = resources.displayMetrics.widthPixels

        if (isTikTok && isTikTokFullscreenPlayer(root, sw, sh)) {
            val sig = buildFullscreenSignature(root)
            if (sig != allowedFullscreenSignature) {
                cancelPreShieldTimers(); removePreShield(); muteMedia()
                showFullBlockerOverlay(true, sig)
            }
            return
        }

        if (isYouTube && isYouTubePlayerActive(root)) {
            val mainHasSpoiler = matches.any { n -> Rect().apply { n.getBoundsInScreen(this) }.top < sh * 0.60 }
            if (mainHasSpoiler) {
                val sig = buildFullscreenSignature(root)
                if (sig != allowedFullscreenSignature) {
                    muteMedia(); showFullBlockerOverlay(false, sig)
                    return
                }
            }
        }

        val covered = mutableListOf<Rect>()
        for (node in matches) {
            if (cardOverlays.size >= 6) break
            val nr = Rect().apply { node.getBoundsInScreen(this) }
            if (nr.top < 170 && nr.height() < 160) continue
            val sig = "${node.text ?: ""}_${node.contentDescription ?: ""}".trim().take(100)
            if (allowedCardSignatures.contains(sig)) continue
            val rect = findBestCardRect(node, sw, sh) ?: continue
            if (covered.any { Rect.intersects(it, rect) }) continue
            covered.add(rect)
            showCardBlurOverlay(rect, sig)
        }
    }

    private fun isSpoilerText(raw: String, customKw: List<String>): Boolean {
        val spaced = normalizeSpaced(raw)
        if (spaced.length < 3) return false
        val compact = spaced.replace(" ", "")
        val has6 = compact.contains("6") || compact.contains("لوسيا") || compact.contains("lucia") ||
                compact.contains("ليونيدا") || compact.contains("leonida") || Regex("\\bvi\\b").containsMatchIn(spaced)
        if (!has6 && (compact.contains("gta5") || compact.contains("قراند5") ||
                    compact.contains("gtav") || compact.contains("ساناندرياس") || compact.contains("sanandreas"))
        ) return false

        if (strongMatches.any { compact.contains(it) }) return true
        if (compact.contains("gta") && Regex("\\bvi\\b").containsMatchIn(spaced)) return true
        for (kw in customKw) {
            if (spaced.contains(kw)) return true
            val kwC = kw.replace(" ", "")
            if (kwC.length >= 4 && compact.contains(kwC)) return true
        }
        if (anchors.any { compact.contains(it) }) {
            if (Regex("\\b6\\b").containsMatchIn(spaced) || signals.any { compact.contains(it) }) return true
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
                '٠' -> sb.append('0'); '١' -> sb.append('1'); '٢' -> sb.append('2')
                '٣' -> sb.append('3'); '٤' -> sb.append('4'); '٥' -> sb.append('5')
                '٦' -> sb.append('6'); '٧' -> sb.append('7'); '٨' -> sb.append('8'); '٩' -> sb.append('9')
                in '\u064B'..'\u065F', '\u0670', 'ـ' -> {}
                '.', '-', '_', '/', '#', '@', '!', '?', ':', '،', ',', '|', '(', ')' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun isCommentsSheetOpen(root: AccessibilityNodeInfo): Boolean {
        val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var c = 0
        while (q.isNotEmpty() && c < 60) {
            val n = q.removeFirst(); c++
            val t = (n.text ?: "").toString().lowercase()
            if (t.contains("add comment") || t.contains("أضف تعليق") || t == "comments" || t == "التعليقات") return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return false
    }

    private fun buildFullscreenSignature(root: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var c = 0
        while (q.isNotEmpty() && c < 90 && parts.size < 6) {
            val n = q.removeFirst(); c++
            val t = (n.text ?: "").toString().trim()
            if (t.length >= 3 && !t.all { it.isDigit() || it == ':' }) parts.add(t.take(40))
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return parts.joinToString("|")
    }

    private fun isYouTubePlayerActive(root: AccessibilityNodeInfo): Boolean {
        val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var c = 0
        while (q.isNotEmpty() && c < 160) {
            val n = q.removeFirst(); c++
            val id = n.viewIdResourceName ?: ""
            if (id.contains("watch_player") || id.contains("player_fragment_container") ||
                id.contains("reel_player_page_container") || id.contains("next_gen_watch_layout")
            ) {
                if (Rect().apply { n.getBoundsInScreen(this) }.height() > 260) return true
            }
            val d = (n.contentDescription ?: "").toString().lowercase()
            if (d.contains("enter fullscreen") || d.contains("ملء الشاشة") || d.contains("pause video") || d.contains("إيقاف الفيديو")) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return false
    }

    private fun isTikTokFullscreenPlayer(root: AccessibilityNodeInfo, sw: Int, sh: Int): Boolean {
        val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var c = 0
        while (q.isNotEmpty() && c < 160) {
            val n = q.removeFirst(); c++
            val d = (n.contentDescription ?: "").toString().lowercase()
            if (d.contains("like") || d.contains("إعجاب") || d.contains("أعجبني") ||
                d.contains("comment") || d.contains("تعليق") || d.contains("share") ||
                d.contains("مشاركة") || d.contains("صوت أصلي") || d.contains("original sound")
            ) return true
            val r = Rect().apply { n.getBoundsInScreen(this) }
            val cls = n.className?.toString() ?: ""
            if (r.width() > sw * 0.85 && r.height() > sh * 0.55 &&
                (cls.contains("ViewPager") || cls.contains("SurfaceView") || cls.contains("TextureView"))
            ) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return false
    }

    private fun findMatchingNodes(node: AccessibilityNodeInfo?, customKw: List<String>, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null || !node.isVisibleToUser) return
        val id = (node.viewIdResourceName ?: "").lowercase()
        if (!node.isEditable && !id.contains("search")) {
            val raw = "${node.text ?: ""} ${node.contentDescription ?: ""}"
            if (raw.isNotBlank() && isSpoilerText(raw, customKw)) { out.add(node); return }
        }
        for (i in 0 until node.childCount) findMatchingNodes(node.getChild(i), customKw, out)
    }

    private fun findBestCardRect(node: AccessibilityNodeInfo, sw: Int, sh: Int): Rect? {
        var best = Rect().apply { node.getBoundsInScreen(this) }
        var cur: AccessibilityNodeInfo? = node
        var parent = cur?.parent
        var steps = 0
        while (parent != null && steps < 14) {
            val pCls = parent.className?.toString() ?: ""
            if (pCls.contains("RecyclerView") || pCls.contains("ListView")) {
                val cr = Rect().apply { cur?.getBoundsInScreen(this) }
                if (cr.height() in 140..(sh * 0.72).toInt() && cr.width() >= sw * 0.40) best = cr
                break
            }
            val pr = Rect().apply { parent.getBoundsInScreen(this) }
            if (pr.width() >= sw * 0.40 && pr.height() in 160..(sh * 0.68).toInt()) best = pr
            cur = parent; parent = parent.parent; steps++
        }
        return if (best.width() < 100 || best.height() < 70) null else best
    }

    private fun createFrostedBlurDrawable(): Drawable {
        val w = 90; val h = 160
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { drawColor(Color.parseColor("#120E1E")) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(w * 0.25f, h * 0.25f, w * 0.85f, intArrayOf(Color.parseColor("#66FF2E93"), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.8f, h * 0.75f, w * 0.9f, intArrayOf(Color.parseColor("#5500D4FF"), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return BitmapDrawable(resources, bmp).apply { isFilterBitmap = true }
    }

    private fun showCardBlurOverlay(rect: Rect, sig: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 16, 24, 16)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                Color.parseColor("#FF24132E"), Color.parseColor("#FF141122"), Color.parseColor("#FF102432")
            )).apply { cornerRadius = 24f; setStroke(3, Color.parseColor("#FF2E93")) }
        }
        card.addView(TextView(this).apply {
            text = "🌫️ هذا محتوى حرق لـ GTA 6\nهل تريد عرض الصورة والوصف؟"
            textSize = if (rect.height() < 220) 13f else 15f
            setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 0, 0, 14)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(Button(this).apply {
            text = "متابعة"; textSize = 13f; setTextColor(Color.WHITE); minHeight = 0; minimumHeight = 0; setPadding(28, 12, 28, 12)
            background = GradientDrawable().apply { setColor(Color.parseColor("#00C853")); cornerRadius = 18f }
            setOnClickListener { allowedCardSignatures.add(sig); clearCardOverlays(); unmuteMedia() }
        })
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(24, 1) })
        row.addView(Button(this).apply {
            text = "لا"; textSize = 13f; setTextColor(Color.WHITE); minHeight = 0; minimumHeight = 0; setPadding(36, 12, 36, 12)
            background = GradientDrawable().apply { setColor(Color.parseColor("#D50000")); cornerRadius = 18f }
            setOnClickListener {
                card.removeAllViews()
                card.addView(TextView(this@SpoilerBlockerService).apply {
                    text = "🌫️ محتوى GTA 6 محجوب"; textSize = 14f; setTextColor(Color.parseColor("#CCCCCC")); gravity = Gravity.CENTER
                })
            }
        })
        card.addView(row)
        val params = WindowManager.LayoutParams(
            rect.width(), rect.height(), rect.left, rect.top, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try { windowManager.addView(card, params); cardOverlays.add(card) } catch (_: Exception) {}
    }

    private fun showFullBlockerOverlay(isTikTok: Boolean, sig: String) {
        if (fullOverlayView != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = createFrostedBlurDrawable(); setPadding(55, 55, 55, 55)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(50, 60, 50, 60)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply { setColor(Color.parseColor("#E6161124")); cornerRadius = 36f; setStroke(4, Color.parseColor("#FF2E93")) }
        }
        card.addView(TextView(this).apply {
            text = "🌫️🔇\n\nهذا محتوى حرق لـ GTA 6\n\nهل تريد متابعة المشاهدة أو لا؟"
            textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 0, 0, 50)
        })
        card.addView(Button(this).apply {
            text = "متابعة"; textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply { setColor(Color.parseColor("#00C853")); cornerRadius = 25f }
            setOnClickListener { allowedFullscreenSignature = sig; removeFullOverlay(); unmuteMedia() }
        })
        card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 25) })
        card.addView(Button(this).apply {
            text = "إلغاء"; textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply { setColor(Color.parseColor("#D50000")); cornerRadius = 25f }
            setOnClickListener {
                allowedFullscreenSignature = null; removeFullOverlay()
                if (isTikTok) {
                    performSwipeUp()
                    handler.postDelayed({ if (preShieldView == null && fullOverlayView == null) unmuteMedia() }, 700)
                } else {
                    unmuteMedia(); performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        })
        root.addView(card)
        fullOverlayView = root
        try { windowManager.addView(fullOverlayView, fullScreenParams(touchable = true)) } catch (_: Exception) { fullOverlayView = null }
    }

    private fun fullScreenParams(touchable: Boolean): WindowManager.LayoutParams {
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val flags = if (touchable) baseFlags else (baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun cancelPreShieldTimers() {
        preShieldTimeoutRunnable?.let { handler.removeCallbacks(it) }
        followUpScanRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun removePreShield() {
        preShieldView?.let { try { windowManager.removeViewImmediate(it) } catch (_: Exception) {}; preShieldView = null }
    }

    private fun clearCardOverlays() {
        for (v in cardOverlays) { try { windowManager.removeViewImmediate(v) } catch (_: Exception) {} }
        cardOverlays.clear()
    }

    private fun removeFullOverlay() {
        fullOverlayView?.let { try { windowManager.removeViewImmediate(it) } catch (_: Exception) {}; fullOverlayView = null }
    }

    private fun muteMedia() {
        if (!isMutedByApp) {
            (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            isMutedByApp = true
        }
    }

    private fun unmuteMedia() {
        if (isMutedByApp) {
            (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            isMutedByApp = false
        }
    }

    private fun performSwipeUp() {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val path = Path().apply { moveTo(x, dm.heightPixels * 0.78f); lineTo(x, dm.heightPixels * 0.18f) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 180)).build(), null, null)
    }

    override fun onInterrupt() {
        cancelPreShieldTimers(); removePreShield(); clearCardOverlays(); removeFullOverlay(); unmuteMedia()
    }
}
