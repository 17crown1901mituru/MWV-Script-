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

        // SYSTEM_ALERT_WINDOW権限チェック
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

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

    override fun onResume() {
        super.onResume()
        ScriptEngineService.activityRef = this
    }

    override fun onDestroy() {
        super.onDestroy()
        ScriptEngineService.activityRef = null
    }
}
