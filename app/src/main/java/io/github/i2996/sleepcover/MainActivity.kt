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
import android.view.MotionEvent
import android.view.View
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

        findViewById<Button>(R.id.btnUpdate).setOnClickListener { checkUpdate(manual = true) }
        if (savedInstanceState == null) checkUpdate(manual = false)

        btnStorage.setOnClickListener { requestStorage() }
        btnA11y.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        // Boox처럼 앱 정보 화면을 바꿔 놓은 기기에서도 안드로이드 원래 화면(⋮ 메뉴 있음)을 연다.
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            try {
                startActivity(i.setClassName("com.android.settings", "com.android.settings.applications.InstalledAppDetails"))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (e2: Exception) {
                    toast("앱 정보 화면을 열 수 없어요")
                }
            }
        }
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
        findViewById<CheckBox>(R.id.chkRemote).apply {
            isChecked = RemoteKeys.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, v -> RemoteKeys.setEnabled(this@MainActivity, v) }
        }
        findViewById<Button>(R.id.btnLearnKey).setOnClickListener { learnKey() }
        findViewById<Button>(R.id.btnRefreshSpeed).apply {
            fun label() = "새로고침 속도: ${RemoteKeys.REFRESH_SPEEDS[RemoteKeys.refreshSpeed(this@MainActivity)].first} (눌러서 바꾸기)"
            text = label()
            setOnClickListener {
                val next = (RemoteKeys.refreshSpeed(this@MainActivity) + 1) % RemoteKeys.REFRESH_SPEEDS.size
                RemoteKeys.setRefreshSpeed(this@MainActivity, next)
                text = label()
            }
        }

        findViewById<Button>(R.id.btnClearRules).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("'제목 맞음'/'아님'으로 가르친 내용을 모두 지울까요?")
                .setPositiveButton("초기화") { _, _ ->
                    Prefs.clearRules(this)
                    refresh()
                }
                .setNegativeButton("취소", null)
                .show()
        }
        findViewById<Button>(R.id.btnClearCache).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("기기에 보관된 표지를 모두 지울까요?\n(지금 슬립 화면 이미지는 그대로 둬요)")
                .setPositiveButton("지우기") { _, _ ->
                    CoverCache.clear(this)
                    Prefs.setAttempted(this, "")
                    refresh()
                }
                .setNegativeButton("취소", null)
                .show()
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
            append("기기: $device · 버전 #${Updater.currentVersion(this@MainActivity)}\n")
            append(if (storage) "✓ 저장 권한 있음\n" else "✗ 저장 권한 필요\n")
            append(if (a11y) "✓ 접근성 서비스 켜짐" else "✗ 접근성 서비스 꺼짐 (자동 감지 안 됨)")
        }
        btnStorage.text = if (storage) "1. 저장 권한 ✓" else "1. 저장 권한 허용하기"
        findViewById<Button>(R.id.btnAppInfo).visibility = if (a11y) View.GONE else View.VISIBLE
        btnA11y.text = if (a11y) "2. 접근성 서비스 ✓" else "2. 접근성 서비스 켜기 ('슬립 표지')"

        val names = Prefs.readers(this).mapNotNull { appLabel(it) }.sorted()
        readersText.text = if (names.isEmpty()) "고른 독서 앱이 이 기기에 없어요. 위 버튼으로 골라 주세요."
        else "감시 중: " + names.joinToString(", ")

        val d = Prefs.detected(this)
        detectedText.text = if (d == null) "아직 감지된 제목이 없어요. 독서 앱에서 책을 열고 메뉴(상단바)를 한 번 띄워 보세요."
        else "최근 감지된 제목: ${d.first}\n(${appLabel(d.second) ?: d.second})"

        showRecent()
        showKeys()
        loadPreview()
    }

    override fun onDestroy() {
        RemoteKeys.learner = null
        super.onDestroy()
    }

    /** 리모컨 버튼을 한 번 눌러 받고, 그 버튼에 붙일 동작을 고르게 한다. */
    private fun learnKey() {
        if (!a11yEnabled()) {
            toast("먼저 2번 접근성 서비스를 켜 주세요")
            return
        }
        var done = false
        lateinit var waiting: AlertDialog
        // viaService=false: 접근성 서비스가 키를 못 받아서 이 창이 대신 받은 경우
        val onKey: (Int, Boolean) -> Unit = { code, viaService ->
            if (!done) {
                done = true
                RemoteKeys.learner = null
                waiting.dismiss()
                if (viaService) {
                    chooseAction(code)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("리모컨 신호가 접근성으로 안 들어와요")
                        .setMessage(
                            "이대로면 독서 앱에서 리모컨 버튼이 바뀌지 않아요.\n\n" +
                                "설정 → 접근성 → 슬립 표지를 껐다가 다시 켠 뒤, 버튼을 다시 배워 주세요."
                        )
                        .setPositiveButton("접근성 설정 열기") { _, _ ->
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        .setNegativeButton("그래도 저장") { _, _ -> chooseAction(code) }
                        .show()
                }
            }
        }
        waiting = AlertDialog.Builder(this)
            .setTitle("버튼 배우기")
            .setMessage("리모컨에서 바꾸고 싶은 버튼을 한 번 눌러 주세요.")
            .setNegativeButton("취소") { _, _ -> RemoteKeys.learner = null }
            // 접근성 서비스가 키를 못 받는 경우를 대비해 이 창에서도 받는다.
            .setOnKeyListener { _, code, ev ->
                if (code == android.view.KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
                if (ev.action == android.view.KeyEvent.ACTION_DOWN) onKey(code, false)
                true
            }
            .setOnDismissListener { if (!done) RemoteKeys.learner = null }
            .create()
        RemoteKeys.learner = { code -> onKey(code, true) }
        waiting.show()
        // L2/R2 트리거·십자키 중에는 키가 아니라 아날로그(축) 신호를 보내는 것이 있다. 그런 건 바꿀 수 없음을 알린다.
        var warnedAxis = false
        waiting.window?.decorView?.setOnGenericMotionListener { _, ev ->
            val axes = listOf(
                MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE,
                MotionEvent.AXIS_GAS, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
            )
            val moved = axes.any { kotlin.math.abs(ev.getAxisValue(it)) > 0.5f }
            if (moved && !warnedAxis && !done) {
                warnedAxis = true
                toast("이 버튼은 키가 아니라 아날로그(축) 신호를 보내서 바꿀 수 없어요")
            }
            moved
        }
    }

    private fun chooseAction(code: Int) {
        val actions = RemoteKeys.Action.values()
        AlertDialog.Builder(this)
            .setTitle("‘${RemoteKeys.keyName(code)}’ 버튼을 누르면")
            .setItems(actions.map { it.label }.toTypedArray()) { _, i ->
                if (actions[i] == RemoteKeys.Action.LAUNCH_APP) {
                    chooseApp(code)
                } else {
                    RemoteKeys.put(this, code, RemoteKeys.Binding(actions[i]))
                    showKeys()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun launcherApps(): List<Pair<String, String>> {
        val pm = packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }

    private fun chooseApp(code: Int) {
        val apps = launcherApps()
        AlertDialog.Builder(this)
            .setTitle("‘${RemoteKeys.keyName(code)}’ 버튼으로 열 앱")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                RemoteKeys.put(this, code, RemoteKeys.Binding(RemoteKeys.Action.LAUNCH_APP, apps[i].first))
                showKeys()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showKeys() {
        val list = findViewById<LinearLayout>(R.id.keyList)
        list.removeAllViews()
        val map = RemoteKeys.map(this)
        if (map.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "아직 설정한 버튼이 없어요."
                setTextColor(Color.DKGRAY)
            })
            return
        }
        for ((code, binding) in map.entries.sortedBy { it.key }) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(Color.BLACK)
                textSize = 15f
                text = "${RemoteKeys.keyName(code)} → ${RemoteKeys.label(this@MainActivity, binding)}"
            })
            row.addView(Button(this).apply {
                text = "변경"
                setOnClickListener { chooseAction(code) }
            })
            row.addView(Button(this).apply {
                text = "삭제"
                setOnClickListener {
                    RemoteKeys.remove(this@MainActivity, code)
                    showKeys()
                }
            })
            list.addView(row)
        }
    }

    /** 감지 기록과 '제목 맞음 / 아님' 버튼. */
    private fun showRecent() {
        val list = findViewById<LinearLayout>(R.id.recentList)
        list.removeAllViews()
        val titles = Prefs.titleKeys(this)
        val blocks = Prefs.blockKeys(this)
        val recent = Prefs.recent(this)
        if (recent.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "아직 기록이 없어요."
                setTextColor(Color.DKGRAY)
            })
            return
        }
        for ((pkg, viewId, text) in recent) {
            val idK = TitleFinder.idKey(pkg, viewId)
            val state = when {
                viewId.isNotEmpty() && idK in titles -> " ✓제목 자리"
                TitleFinder.textKey(pkg, text) in blocks || (viewId.isNotEmpty() && idK in blocks) -> " ✗막음"
                else -> ""
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                setTextColor(Color.BLACK)
                textSize = 15f
                this.text = "$text$state\n" + (appLabel(pkg) ?: pkg) +
                    (if (viewId.isNotEmpty()) " · ${viewId.substringAfter('/')}" else "")
            })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Button(this).apply {
                this.text = "제목 맞음"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (viewId.isEmpty()) {
                        toast("이 앱은 자리 정보가 없어 고정할 수 없어요. 표지는 검색해서 골라 주세요.")
                    } else {
                        Prefs.markTitle(this@MainActivity, pkg, viewId)
                        toast("이 앱은 앞으로 이 자리만 책 제목으로 봐요")
                    }
                    Prefs.setAttempted(this@MainActivity, "")
                    editQuery.setText(text)
                    search(text)
                    showRecent()
                }
            })
            buttons.addView(Button(this).apply {
                this.text = "아님"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    Prefs.markNotTitle(this@MainActivity, pkg, viewId, text)
                    toast("‘$text’ 자리는 이제 제목으로 안 봐요")
                    showRecent()
                }
            })
            row.addView(buttons)
            list.addView(row)
        }
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

    /** 새 버전이 있으면 물어보고 설치한다. manual=false(앱 열 때)면 새 버전이 없거나 실패해도 조용히 넘어간다. */
    private fun checkUpdate(manual: Boolean) {
        if (manual) toast("업데이트 확인 중…")
        val ctx = applicationContext
        Thread {
            val r = runCatching { Updater.newerVersion(ctx) }
            main.post {
                if (isFinishing) return@post
                r.onSuccess { v ->
                    if (v == null) {
                        if (manual) toast("최신 버전이에요 (#${Updater.currentVersion(ctx)})")
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("새 버전 #$v")
                            .setMessage("지금 업데이트할까요? 설정은 그대로 남아요.")
                            .setPositiveButton("업데이트") { _, _ -> installUpdate() }
                            .setNegativeButton("나중에", null)
                            .show()
                    }
                }.onFailure { if (manual) toast("확인 실패: ${it.message}") }
            }
        }.start()
    }

    private fun installUpdate() {
        if (!Updater.canInstall(this)) {
            toast("‘이 출처 허용’을 켜고 돌아와서 다시 눌러 주세요")
            Updater.openInstallPermission(this)
            return
        }
        toast("새 버전 받는 중…")
        val ctx = applicationContext
        Thread {
            val r = runCatching { Updater.downloadAndInstall(ctx) }
            r.exceptionOrNull()?.let { e -> main.post { toast("업데이트 실패: ${e.message}") } }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
