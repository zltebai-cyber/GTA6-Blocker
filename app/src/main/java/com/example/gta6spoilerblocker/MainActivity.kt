package com.example.gta6spoilerblocker

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var appsContainer: LinearLayout
    private lateinit var permissionBanner: Button

    private val defaultKeywords = setOf(
        "gta 6", "gta6", "gta vi", "gtavi", "grand theft auto 6", "grand theft auto vi",
        "قراند 6", "قراند6", "جي تي اي 6", "جي تي أي 6", "حرق قراند", "تختيم قراند",
        "نهاية قراند", "تسريب قراند", "leonida", "vice city", "lucia", "jason",
        "لوسيا", "جيسون", "ليونيدا"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("gta6_blocker_prefs", Context.MODE_PRIVATE)
        if (!prefs.contains("keywords_set")) {
            prefs.edit().putStringSet("keywords_set", defaultKeywords).apply()
        }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#110E1B"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(45, 70, 45, 60)
        }

        val title = TextView(this).apply {
            text = "🛡️ مانع حرق GTA 6"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 35)
        }

        permissionBanner = Button(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(25, 25, 25, 25)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val toggleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1D1830"))
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#FF2E93"))
            }
            setPadding(35, 35, 35, 35)
        }

        val isEnabledInitially = prefs.getBoolean("protection_enabled", true)
        val mainSwitch = Switch(this).apply {
            text = if (isEnabledInitially) "🟢 الحماية مفعّلة (مع كتم الصوت)" else "🔴 الحماية مطفأة"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isChecked = isEnabledInitially
            setOnCheckedChangeListener { buttonView, isChecked ->
                prefs.edit().putBoolean("protection_enabled", isChecked).apply()
                buttonView.text = if (isChecked) "🟢 الحماية مفعّلة (مع كتم الصوت)" else "🔴 الحماية مطفأة"
            }
        }
        toggleBox.addView(mainSwitch)

        val addAppBtn = Button(this).apply {
            text = "➕ إضافة تطبيق للحماية"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00C853"))
                cornerRadius = 25f
            }
            setPadding(30, 30, 30, 30)
            setOnClickListener { showAppPickerDialog() }
        }

        val updateTemplateBtn = Button(this).apply {
            text = "🔄 تحديث القالب وإضافة كلمات"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#393053"))
                cornerRadius = 25f
                setStroke(2, Color.parseColor("#00D4FF"))
            }
            setPadding(30, 25, 30, 25)
            setOnClickListener { showTemplateDialog() }
        }

        val appsHeader = TextView(this).apply {
            text = "📱 التطبيقات المؤمّنة حالياً:"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFD600"))
            setPadding(0, 40, 0, 15)
        }

        appsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(title)
        root.addView(permissionBanner)
        root.addView(space(25))
        root.addView(toggleBox)
        root.addView(space(30))
        root.addView(addAppBtn)
        root.addView(space(20))
        root.addView(updateTemplateBtn)
        root.addView(appsHeader)
        root.addView(appsContainer)

        scrollView.addView(root)
        setContentView(scrollView)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBanner()
        refreshProtectedAppsList()
    }

    private fun space(h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun refreshPermissionBanner() {
        if (isAccessibilityEnabled()) {
            permissionBanner.text = "✅ إذن التشغيل فعّال وجاهز"
            permissionBanner.background = GradientDrawable().apply {
                setColor(Color.parseColor("#1B5E20"))
                cornerRadius = 20f
            }
        } else {
            permissionBanner.text = "⚠️ اضغط هنا لتفعيل الإذن من الضبط حتى يعمل التطبيق"
            permissionBanner.background = GradientDrawable().apply {
                setColor(Color.parseColor("#B71C1C"))
                cornerRadius = 20f
            }
        }
    }

    private fun refreshProtectedAppsList() {
        appsContainer.removeAllViews()
        val prefs = getSharedPreferences("gta6_blocker_prefs", Context.MODE_PRIVATE)
        val protectedApps = prefs.getStringSet("protected_apps", emptySet())?.toMutableSet() ?: mutableSetOf()

        if (protectedApps.isEmpty()) {
            val emptyMsg = TextView(this).apply {
                text = "لا توجد تطبيقات مضافة بعد.\nاضغط (➕ إضافة تطبيق للحماية) واختر يوتيوب وتيك توك."
                textSize = 14f
                setTextColor(Color.parseColor("#BBBBBB"))
                setPadding(15, 20, 15, 20)
            }
            appsContainer.addView(emptyMsg)
            return
        }

        for (pkg in protectedApps) {
            val appName = try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                pkg
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1D1830"))
                    cornerRadius = 20f
                }
                setPadding(30, 20, 20, 20)
            }

            val nameTv = TextView(this).apply {
                text = "🛡️ $appName"
                textSize = 16f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val removeBtn = Button(this).apply {
                text = "إزالة ❌"
                textSize = 13f
                setTextColor(Color.parseColor("#FF8A80"))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    val updated = prefs.getStringSet("protected_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
                    updated.remove(pkg)
                    prefs.edit().putStringSet("protected_apps", updated).apply()
                    refreshProtectedAppsList()
                }
            }

            row.addView(nameTv)
            row.addView(removeBtn)
            appsContainer.addView(row)
            appsContainer.addView(space(14))
        }
    }

    private fun showAppPickerDialog() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }

        val appList = resolveInfos
            .map { info ->
                val pkg = info.activityInfo.packageName
                val label = info.loadLabel(packageManager).toString()
                Pair(label, pkg)
            }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        val prefs = getSharedPreferences("gta6_blocker_prefs", Context.MODE_PRIVATE)
        val currentSet = prefs.getStringSet("protected_apps", emptySet())?.toMutableSet() ?: mutableSetOf()

        val labels = appList.map { it.first }.toTypedArray()
        val checked = appList.map { currentSet.contains(it.second) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("اختر التطبيقات التي تريد حمايتها:")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = appList[which].second
                if (isChecked) currentSet.add(pkg) else currentSet.remove(pkg)
            }
            .setPositiveButton("حفظ") { _, _ ->
                prefs.edit().putStringSet("protected_apps", currentSet).apply()
                refreshProtectedAppsList()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showTemplateDialog() {
        val prefs = getSharedPreferences("gta6_blocker_prefs", Context.MODE_PRIVATE)
        val currentWords = prefs.getStringSet("keywords_set", defaultKeywords)?.toMutableSet() ?: defaultKeywords.toMutableSet()

        val input = EditText(this).apply {
            hint = "اكتب كلمة جديدة لإضافتها (اختياري)"
        }

        AlertDialog.Builder(this)
            .setTitle("🔄 قالب كلمات حرق GTA 6")
            .setMessage("عدد الكلمات المحظورة حالياً: ${currentWords.size}\nيمكنك إضافة كلمة جديدة بالأسفل أو تحديث القالب الافتراضي.")
            .setView(input)
            .setPositiveButton("حفظ / إضافة") { _, _ ->
                val word = input.text.toString().trim().lowercase()
                if (word.isNotEmpty()) {
                    currentWords.add(word)
                }
                currentWords.addAll(defaultKeywords)
                prefs.edit().putStringSet("keywords_set", currentWords).apply()
                Toast.makeText(this, "✅ تم تحديث القالب (${currentWords.size} كلمة)", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("استعادة القالب الأساسي") { _, _ ->
                prefs.edit().putStringSet("keywords_set", defaultKeywords).apply()
                Toast.makeText(this, "✅ تم تحديث القالب بنجاح", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val longName = "$packageName/${SpoilerBlockerService::class.java.name}"
        val shortName = "$packageName/.${SpoilerBlockerService::class.java.simpleName}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.equals(longName, ignoreCase = true) || it.equals(shortName, ignoreCase = true)
        }
    }
}
