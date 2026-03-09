package com.mwvscript.app

import android.content.*
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestAllPermissions()

        startForegroundService(Intent(this, ScriptEngineService::class.java))
        startForegroundService(Intent(this, OverlayService::class.java))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        // ヘッダー
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
            setPadding(16, 16, 16, 16)
        }
        val headerTitle = TextView(this).apply {
            text = "MWV Launcher"
            setTextColor(android.graphics.Color.GREEN)
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val terminalBtn = Button(this).apply {
            text = "Terminal"
            textSize = 11f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#003300"))
            setPadding(16, 8, 16, 8)
            setOnClickListener { OverlayService.toggle(this@MainActivity) }
        }
        header.addView(headerTitle)
        header.addView(terminalBtn)

        // アプリグリッド
        val grid = GridView(this).apply {
            numColumns = 4
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = 8
            verticalSpacing = 8
            setPadding(8, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        root.addView(header)
        root.addView(grid)
        setContentView(root)

        // アプリ一覧を非同期で読み込み
        Thread {
            val apps = getLaunchableApps()
            mainHandler.post {
                grid.adapter = AppGridAdapter(apps)
                grid.setOnItemClickListener { _, _, position, _ ->
                    val app = apps[position]
                    val intent = packageManager.getLaunchIntentForPackage(app.packageName)
                    intent?.let { startActivity(it) }
                }
            }
        }.start()
    }

    private fun getLaunchableApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos: List<ResolveInfo> = packageManager.queryIntentActivities(intent, 0)
        return resolveInfos
            .map { ri ->
                AppInfo(
                    label = ri.loadLabel(packageManager).toString(),
                    packageName = ri.activityInfo.packageName,
                    icon = ri.loadIcon(packageManager)
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    data class AppInfo(
        val label: String,
        val packageName: String,
        val icon: Drawable
    )

    inner class AppGridAdapter(
        private val apps: List<AppInfo>
    ) : BaseAdapter() {

        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = apps[position]
            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(8, 12, 8, 12)
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageDrawable(app.icon)
                layoutParams = LinearLayout.LayoutParams(96, 96)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val label = TextView(this@MainActivity).apply {
                text = app.label
                setTextColor(android.graphics.Color.WHITE)
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            container.addView(icon)
            container.addView(label)
            return container
        }
    }

    private fun requestAllPermissions() {
        // 通常権限（requestPermissionsで要求可能）
        val permissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 100)
        }

        // SYSTEM_ALERT_WINDOW（設定画面への遷移が必要）
        if (!android.provider.Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(this,
                "「他のアプリの上に重ねて表示」を許可してください", android.widget.Toast.LENGTH_LONG).show()
            startActivity(Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            ))
            return
        }

        // MANAGE_EXTERNAL_STORAGE（設定画面への遷移が必要）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                android.widget.Toast.makeText(this,
                    "「全ファイルへのアクセス」を許可してください", android.widget.Toast.LENGTH_LONG).show()
                startActivity(Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                ))
                return
            }
        }

        // アクセシビリティサービスの案内（コードからは要求不可）
        if (MWVAccessibilityService.instance == null) {
            android.widget.Toast.makeText(this,
                "設定→ユーザー補助→MWV Script Accessibilityを有効にしてください",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private var permissionsRequested = false

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
}
