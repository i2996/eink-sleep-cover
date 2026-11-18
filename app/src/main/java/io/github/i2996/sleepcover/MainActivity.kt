package io.github.i2996.sleepcover

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var btnStorage: Button
    private lateinit var btnA11y: Button
    private lateinit var readersText: TextView
    private lateinit var editFolder: EditText
    private lateinit var editKey: EditText
    private lateinit var detectedText: TextView
    private lateinit var editQuery: EditText
    private lateinit var searchStatus: TextView
    private lateinit var results: LinearLayout
    private lateinit var pathText: TextView
    private lateinit var preview: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        btnStorage = findViewById(R.id.btnStorage)
        btnA11y = findViewById(R.id.btnA11y)
        readersText = findViewById(R.id.readersText)
        editFolder = findViewById(R.id.editFolder)
        editKey = findViewById(R.id.editKey)
        detectedText = findViewById(R.id.detectedText)
        editQuery = findViewById(R.id.editQuery)
        searchStatus = findViewById(R.id.searchStatus)
        results = findViewById(R.id.results)
        pathText = findViewById(R.id.pathText)
        preview = findViewById(R.id.preview)

        findViewById<CheckBox>(R.id.chkAuto).apply {
            isChecked = Prefs.auto(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setAuto(this@MainActivity, v) }
        }
        findViewById<CheckBox>(R.id.chkFill).apply {
            isChecked = Prefs.fill(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setFill(this@MainActivity, v) }
        }
        findViewById<CheckBox>(R.id.chkGray).apply {
            isChecked = Prefs.gray(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setGray(this@MainActivity, v) }
        }
        editFolder.setText(Prefs.folder(this))
        editKey.setText(Prefs.ttbKey(this))

        btnStorage.setOnClickListener { requestStorage() }
        btnA11y.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<Button>(R.id.btnReaders).setOnClickListener { pickReaders() }
        findViewById<Button>(R.id.btnUseDetected).setOnClickListener {
            val d = Prefs.detected(this)
            if (d == null) {
                toast("아직 감지된 제목이 없어요")
            } else {
                editQuery.setText(d.first)
                search(d.first)
            }
        }
        findViewById<Button>(R.id.btnSearch).setOnClickListener { search(editQuery.text.toString()) }
        editQuery.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                search(editQuery.text.toString())
                true
            } else {
                false
            }
        }

        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
        refresh()
    }

    private fun savePrefs() {
        Prefs.setFolder(this, editFolder.text.toString())
        Prefs.setTtbKey(this, editKey.text.toString())
    }

    private fun a11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, SleepCoverService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun refresh() {
        val storage = CoverWriter.canWrite(this)
        val a11y = a11yEnabled()
        val device = if (Prefs.isCrema()) "크레마" else "${Build.MANUFACTURER} ${Build.MODEL}"
        status.text = buildString {
            append("기기: $device\n")
            append(if (storage) "✓ 저장 권한 있음\n" else "✗ 저장 권한 필요\n")
            append(if (a11y) "✓ 접근성 서비스 켜짐" else "✗ 접근성 서비스 꺼짐 (자동 감지 안 됨)")
        }
        btnStorage.text = if (storage) "1. 저장 권한 ✓" else "1. 저장 권한 허용하기"
        btnA11y.text = if (a11y) "2. 접근성 서비스 ✓" else "2. 접근성 서비스 켜기 ('슬립 표지')"

        val names = Prefs.readers(this).mapNotNull { appLabel(it) }.sorted()
        readersText.text = if (names.isEmpty()) "고른 독서 앱이 이 기기에 없어요. 위 버튼으로 골라 주세요."
        else "감시 중: " + names.joinToString(", ")

        val d = Prefs.detected(this)
        detectedText.text = if (d == null) "아직 감지된 제목이 없어요. 독서 앱에서 책을 열고 메뉴(상단바)를 한 번 띄워 보세요."
        else "최근 감지된 제목: ${d.first}\n(${appLabel(d.second) ?: d.second})"

        loadPreview()
    }

    private fun appLabel(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        null
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 1
            )
        }
    }

    private fun pickReaders() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second }
        val selected = Prefs.readers(this)
        val checked = BooleanArray(apps.size) { apps[it].first in selected }
        AlertDialog.Builder(this)
            .setTitle("책 읽는 앱 고르기")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("저장") { _, _ ->
                val installed = apps.map { it.first }.toSet()
                val keep = selected.filter { it !in installed }
                val chosen = apps.filterIndexed { i, _ -> checked[i] }.map { it.first }
                Prefs.setReaders(this, (keep + chosen).toSet())
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val raw = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT))
            .joinToString("\n")
        val q = TitleFinder.fromShared(raw)
        if (q.isEmpty()) return
        editQuery.setText(q)
        search(q)
    }

    private fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        savePrefs()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(editQuery.windowToken, 0)
        searchStatus.text = "‘$q’ 검색 중…"
        results.removeAllViews()
        val key = Prefs.ttbKey(this)
        Thread {
            val r = runCatching { CoverSearch.search(q, key) }
            main.post {
                if (isFinishing) return@post
                r.onSuccess { list ->
                    searchStatus.text = if (list.isEmpty()) "결과가 없어요. 제목을 짧게 바꿔 보세요."
                    else "표지를 누르면 슬립 이미지로 저장해요."
                    list.forEach { addResult(it) }
                }.onFailure { searchStatus.text = "검색 실패: ${it.message}" }
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addResult(book: Book) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            setOnClickListener { applyBook(book) }
        }
        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(116))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.LTGRAY)
        }
        val text = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(12), 0, 0, 0)
            setTextColor(Color.BLACK)
            textSize = 16f
            this.text = if (book.author.isBlank()) book.title else "${book.title}\n${book.author}"
        }
        row.addView(img)
        row.addView(text)
        results.addView(row)
        Thread {
            val bmp = try {
                val b = Http.bytes(book.coverUrl)
                BitmapFactory.decodeByteArray(b, 0, b.size)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) main.post { img.setImageBitmap(bmp) }
        }.start()
    }

    private fun applyBook(book: Book) {
        if (!CoverWriter.canWrite(this)) {
            toast("먼저 1번 저장 권한을 허용해 주세요")
            return
        }
        savePrefs()
        searchStatus.text = "슬립 이미지 만드는 중…"
        val ctx = applicationContext
        // 자동 감지된 제목으로도 보관해서, 다음에 그 책을 열면 와이파이 없이도 바로 바뀌게 한다.
        val detected = listOfNotNull(Prefs.detected(ctx)?.first, editQuery.text.toString().trim())
        Thread {
            val r = runCatching { CoverWriter.apply(ctx, book.coverUrl, book.title, detected) }
            main.post {
                if (isFinishing) return@post
                r.onSuccess { f ->
                    // 직접 고른 표지를 자동 감지가 다시 덮어쓰지 않게 한다.
                    Prefs.detected(ctx)?.let { Prefs.setAttempted(ctx, it.first) }
                    searchStatus.text = "완료! ${f.path}"
                    loadPreview()
                }.onFailure { searchStatus.text = "실패: ${it.message}" }
            }
        }.start()
    }

    private fun loadPreview() {
        val f = CoverWriter.outputFile(this)
        val applied = Prefs.applied(this)
        pathText.text = (if (applied.isNotEmpty()) "$applied\n" else "") + f.path +
            "\n기기에 보관된 표지: ${CoverCache.count(this)}개 (와이파이 없이 바로 바뀜)"
        if (!CoverWriter.canWrite(this)) return
        Thread {
            val bmp: Bitmap? = try {
                if (f.exists()) BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })
                else null
            } catch (e: Exception) {
                null
            }
            main.post { if (!isFinishing) preview.setImageBitmap(bmp) }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
