package com.mwvscript.app

import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    // ─── データクラス ────────────────────────────────────────────────

    data class AppInfo(
        val label: String,
        val packageName: String,
        val componentName: String,   // "pkg/cls" 形式
        val icon: Drawable
    )

    /** Xperia launcher.db の favorites テーブル 1 行分 */
    data class LauncherItem(
        val title: String,
        val intent: String,          // raw Intent URI
        val screen: Int,
        val cellX: Int,
        val cellY: Int,
        val spanX: Int,
        val spanY: Int,
        val container: Int           // -100=ホーム, -101=ドック
    )

    // ─── 定数 ────────────────────────────────────────────────────────

    companion object {
        // Xperia バックアップ db をコピーする先（外部ストレージ）
        private const val DB_SUBPATH  = "MWVLauncher/launcher.db"
        // グリッドサイズ（db に合わせて変更可）
        private const val GRID_COLS   = 4
        private const val GRID_ROWS   = 5
        private const val CELL_SIZE_DP = 80
    }

    // ─── 状態 ─────────────────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())
    private var permissionsRequested = false
    private var currentMode = Mode.ALL_APPS   // 表示モード

    private lateinit var contentFrame: FrameLayout

    enum class Mode { ALL_APPS, XPERIA_LAYOUT }

    // ─── ライフサイクル ───────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAllPermissions()

        startForegroundService(Intent(this, ScriptEngineService::class.java))
        startForegroundService(Intent(this, OverlayService::class.java))

        setContentView(buildRootLayout())
        showAllApps()
    }

    override fun onResume() {
        super.onResume()
        ScriptEngineService.activityRef = this
        if (!permissionsRequested) {
            permissionsRequested = true
            requestAllPermissions()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ScriptEngineService.activityRef = null
    }

    // ─── UI 構築 ──────────────────────────────────────────────────────

    private fun buildRootLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // ── ヘッダー ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(16, 16, 16, 16)
        }
        val title = TextView(this).apply {
            text = "MWV Launcher"
            setTextColor(Color.GREEN)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val xperiaBtn = Button(this).apply {
            text = "Xperia配置"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#003366"))
            setPadding(12, 6, 12, 6)
            setOnClickListener { toggleMode() }
        }
        val terminalBtn = Button(this).apply {
            text = "Terminal"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#003300"))
            setPadding(12, 6, 12, 6)
            setOnClickListener { OverlayService.toggle(this@MainActivity) }
        }
        header.addView(title)
        header.addView(xperiaBtn)
        header.addView(terminalBtn)

        // ── コンテンツ領域 ──
        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        root.addView(header)
        root.addView(contentFrame)
        return root
    }

    // ─── モード切替 ───────────────────────────────────────────────────

    private fun toggleMode() {
        currentMode = if (currentMode == Mode.ALL_APPS) Mode.XPERIA_LAYOUT else Mode.ALL_APPS
        contentFrame.removeAllViews()
        if (currentMode == Mode.ALL_APPS) showAllApps() else showXperiaLayout()
    }

    // ─── 全アプリ表示（GridView） ─────────────────────────────────────

    private fun showAllApps() {
        val grid = GridView(this).apply {
            numColumns = GRID_COLS
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = 8
            verticalSpacing = 8
            setPadding(8, 8, 8, 8)
        }
        contentFrame.addView(grid)

        Thread {
            val apps = getLaunchableApps()
            mainHandler.post {
                grid.adapter = AppGridAdapter(apps)
                grid.setOnItemClickListener { _, _, pos, _ ->
                    launchApp(apps[pos].packageName)
                }
            }
        }.start()
    }

    private fun getLaunchableApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        return packageManager.queryIntentActivities(intent, 0)
            .map { ri ->
                val comp = "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
                AppInfo(
                    label = ri.loadLabel(packageManager).toString(),
                    packageName = ri.activityInfo.packageName,
                    componentName = comp,
                    icon = ri.loadIcon(packageManager)
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    // ─── Xperia 配置表示（ScrollView + AbsoluteLayout 風グリッド） ────

    private fun showXperiaLayout() {
        val dbFile = getXperiaDbFile()
        if (dbFile == null || !dbFile.exists()) {
            showDbMissingHint()
            return
        }

        Thread {
            val items = readLauncherDb(dbFile)
            val apps  = getLaunchableApps()
            val compMap = apps.associateBy { it.componentName.substringBefore("/") }  // pkg → AppInfo

            mainHandler.post {
                buildXperiaGridView(items, compMap)
            }
        }.start()
    }

    /**
     * db ファイルの場所を解決する。
     * 優先順: 外部ストレージ共有領域 → MWV CD (/storage/.../MWVLauncher/launcher.db)
     */
    private fun getXperiaDbFile(): File? {
        // 1. MWV スクリプトの CD（外部ストレージ）
        val cdBase = getExternalFilesDir(null)?.parentFile?.parentFile
            ?: return null
        return File(cdBase, DB_SUBPATH).takeIf { it.exists() }
            ?: File(Environment.getExternalStorageDirectory(), DB_SUBPATH)
    }

    /** favorites テーブルを読み込んで LauncherItem リストを返す */
    private fun readLauncherDb(file: File): List<LauncherItem> {
        val items = mutableListOf<LauncherItem>()
        try {
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery(
                "SELECT title, intent, container, screen, cellX, cellY, spanX, spanY FROM favorites", null)
            while (cursor.moveToNext()) {
                items += LauncherItem(
                    title     = cursor.getString(0) ?: "",
                    intent    = cursor.getString(1) ?: "",
                    screen    = cursor.getInt(3),
                    cellX     = cursor.getInt(4),
                    cellY     = cursor.getInt(5),
                    spanX     = cursor.getInt(6),
                    spanY     = cursor.getInt(7),
                    container = cursor.getInt(2)
                )
            }
            cursor.close()
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    /** Xperia グリッドを ViewPager 風のページ分割 ScrollView で表示 */
    private fun buildXperiaGridView(items: List<LauncherItem>, compMap: Map<String, AppInfo>) {
        val density = resources.displayMetrics.density
        val cellPx  = (CELL_SIZE_DP * density).toInt()

        // screen 番号でグループ化（ホーム画面のみ）
        val pages = items
            .filter { it.container == -100 }
            .groupBy { it.screen }
            .toSortedMap()

        // 水平スクロール可能なページ列
        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val pageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        for ((screenIdx, pageItems) in pages) {
            val pageWidth  = GRID_COLS * cellPx
            val pageHeight = GRID_ROWS * cellPx

            val pageLabel = TextView(this).apply {
                text = "  $screenIdx  "
                setTextColor(Color.parseColor("#888888"))
                textSize = 9f
                gravity = Gravity.CENTER
            }

            val pageContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 8, 8, 8)
            }

            // ページ境界線
            val divider = View(this).apply {
                setBackgroundColor(Color.parseColor("#222222"))
                layoutParams = LinearLayout.LayoutParams(2, pageHeight)
            }

            // アイコンを絶対座標で配置（RelativeLayout を疑似グリッドとして使用）
            val canvas = RelativeLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(pageWidth, pageHeight)
                setBackgroundColor(Color.parseColor("#0a0a0a"))
            }

            for (item in pageItems) {
                val pkg = extractPackage(item.intent)
                val app = compMap[pkg]

                val cellView = buildCellView(item, app, cellPx)
                val params = RelativeLayout.LayoutParams(
                    cellPx * item.spanX,
                    cellPx * item.spanY
                ).apply {
                    leftMargin = item.cellX * cellPx
                    topMargin  = item.cellY * cellPx
                }
                cellView.layoutParams = params
                canvas.addView(cellView)
            }

            pageContainer.addView(pageLabel)
            pageContainer.addView(canvas)
            pageRow.addView(pageContainer)
            if (screenIdx < pages.keys.last()) pageRow.addView(divider)
        }

        // db が見つかったが中身が空だった場合のフォールバック
        if (pages.isEmpty()) {
            showDbMissingHint("db は存在しますが HOME アイテムがありませんでした")
            return
        }

        hScroll.addView(pageRow)
        contentFrame.addView(hScroll)
    }

    /** アイコン+ラベルのセル View を生成 */
    private fun buildCellView(item: LauncherItem, app: AppInfo?, cellPx: Int): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener {
                app?.let { launchApp(it.packageName) }
                    ?: Toast.makeText(
                        this@MainActivity,
                        "${item.title}: アプリ未インストール",
                        Toast.LENGTH_SHORT
                    ).show()
            }
        }
        val iconView = ImageView(this).apply {
            if (app != null) {
                setImageDrawable(app.icon)
                alpha = 1f
            } else {
                setImageDrawable(
                    resources.getDrawable(android.R.drawable.ic_menu_help, theme))
                alpha = 0.4f
            }
            val iconSize = (cellPx * 0.55).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val labelView = TextView(this).apply {
            text = if (app != null) app.label else item.title.ifEmpty { "?" }
            setTextColor(if (app != null) Color.WHITE else Color.parseColor("#666666"))
            textSize = 9f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        cell.addView(iconView)
        cell.addView(labelView)
        return cell
    }

    /** db ファイルが見つからない場合のヒント表示 */
    private fun showDbMissingHint(msg: String? = null) {
        val tv = TextView(this).apply {
            text = msg ?: """
                Xperia 配置 db が見つかりません。
                
                以下のパスに launcher.db をコピーしてください：
                /sdcard/MWVLauncher/launcher.db
                
                Xperia の場合:
                  設定 → バックアップ → ランチャー
                  /sdcard/Android/data/com.sonymobile.launcher/files/assets/databases/
                  launcher_4_by_5.db  (→ launcher.db にリネーム)
                  
                MWV スクリプトで自動コピー:
                  var src = "/sdcard/Android/data/com.sonymobile.launcher/files/assets/databases/launcher_4_by_5.db";
                  var dst = "/sdcard/MWVLauncher/launcher.db";
                  // fs.copy(src, dst);  // Termux 連携で実行
            """.trimIndent()
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(32, 32, 32, 32)
        }
        val sv = ScrollView(this)
        sv.addView(tv)
        contentFrame.addView(sv)
    }

    // ─── ユーティリティ ───────────────────────────────────────────────

    /** Intent URI からパッケージ名を抽出 */
    private fun extractPackage(intentUri: String): String {
        // "component=com.example.app/.MainActivity" → "com.example.app"
        val m = Regex("component=([^/;]+)").find(intentUri)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun launchApp(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
    }

    // ─── AppGridAdapter（全アプリ表示用） ────────────────────────────

    inner class AppGridAdapter(
        private val apps: List<AppInfo>
    ) : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(pos: Int) = apps[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = apps[position]
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(8, 12, 8, 12)
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageDrawable(app.icon)
                layoutParams = LinearLayout.LayoutParams(96, 96)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val label = TextView(this@MainActivity).apply {
                text = app.label
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            cell.addView(icon)
            cell.addView(label)
            return cell
        }
    }

    // ─── 権限管理 ─────────────────────────────────────────────────────

    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions += android.Manifest.permission.POST_NOTIFICATIONS
            }
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 100)

        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "「他のアプリの上に重ねて表示」を許可してください",
                Toast.LENGTH_LONG).show()
            startActivity(Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "「全ファイルへのアクセス」を許可してください",
                    Toast.LENGTH_LONG).show()
                startActivity(Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
                return
            }
        }
        if (MWVAccessibilityService.instance == null) {
            Toast.makeText(this,
                "設定→ユーザー補助→MWV Script Accessibilityを有効にしてください",
                Toast.LENGTH_LONG).show()
        }
    }
}
