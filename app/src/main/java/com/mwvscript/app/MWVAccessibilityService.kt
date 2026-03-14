package com.mwvscript.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.mozilla.javascript.ScriptableObject

class MWVAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MWVAccessibilityService? = null

        var lastEventPackage: String = ""
        var lastEventClass: String = ""
        var lastEventText: String = ""

        // 不審イベントのコールバック（security_monitor.rjsから登録）
        var suspiciousEventCallback: org.mozilla.javascript.Function? = null

        // 監視対象外パッケージ（MWV自身）
        val TRUSTED_PACKAGES = setOf(
            "com.mwvscript.app",
            "com.android.systemui",
            "com.android.launcher3",
            "com.samsung.android.launcher"
        )

        // 不審イベントログ
        val suspiciousLog = mutableListOf<Map<String, Any>>()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        android.util.Log.d("MWVScript", "AccessibilityService 接続完了")
        injectAccessibilityBridge()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: ""
        val cls = event.className?.toString() ?: ""
        val txt = event.text.joinToString(" ")

        lastEventPackage = pkg
        lastEventClass = cls
        lastEventText = txt

        // 不審操作の検知
        detectSuspiciousEvent(event, pkg, cls, txt)
    }

    private fun detectSuspiciousEvent(
        event: AccessibilityEvent,
        pkg: String,
        cls: String,
        txt: String
    ) {
        // MWV自身と信頼済みパッケージは除外
        if (pkg in TRUSTED_PACKAGES) return

        val eventType = event.eventType
        val isSuspicious = when {
            // ソフトウェア生成入力（deviceId = -1相当）
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && pkg.isNotEmpty() -> true
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && pkg.isNotEmpty() -> true
            // 不審なウィンドウ変化
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                pkg.isNotEmpty() && pkg !in TRUSTED_PACKAGES -> false // 通常操作も含むので記録のみ
            else -> false
        }

        // TYPE_VIEW_CLICKEDかTYPE_VIEW_TEXT_CHANGEDはソース不明な自動操作の可能性
        val isAutoOperation = eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                              eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED

        if (!isAutoOperation) return

        val entry = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "package" to pkg,
            "class" to cls,
            "text" to txt,
            "eventType" to eventType,
            "isSuspicious" to isSuspicious
        )

        suspiciousLog.add(entry)
        if (suspiciousLog.size > 500) suspiciousLog.removeAt(0)

        // コールバックがあれば通知
        suspiciousEventCallback?.let { fn ->
            val scope = HubService.rhinoScope ?: return
            try {
                val cx = org.mozilla.javascript.Context.enter()
                cx.optimizationLevel = -1
                val obj = cx.newObject(scope) as org.mozilla.javascript.NativeObject
                ScriptableObject.putProperty(obj, "timestamp", System.currentTimeMillis().toDouble())
                ScriptableObject.putProperty(obj, "package", pkg)
                ScriptableObject.putProperty(obj, "class", cls)
                ScriptableObject.putProperty(obj, "text", txt)
                ScriptableObject.putProperty(obj, "eventType", eventType)
                ScriptableObject.putProperty(obj, "isSuspicious", isSuspicious)
                fn.call(cx, scope, scope, arrayOf(obj))
                org.mozilla.javascript.Context.exit()
            } catch (e: Exception) {
                try { org.mozilla.javascript.Context.exit() } catch (_: Exception) {}
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ノードをJS向けのMapに変換
    private fun nodeToMap(node: AccessibilityNodeInfo?): org.mozilla.javascript.NativeObject? {
        node ?: return null
        val cx = org.mozilla.javascript.Context.enter()
        cx.optimizationLevel = -1
        val obj = cx.newObject(HubService.rhinoScope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        ScriptableObject.putProperty(obj, "text", node.text?.toString() ?: "")
        ScriptableObject.putProperty(obj, "contentDescription", node.contentDescription?.toString() ?: "")
        ScriptableObject.putProperty(obj, "className", node.className?.toString() ?: "")
        ScriptableObject.putProperty(obj, "packageName", node.packageName?.toString() ?: "")
        ScriptableObject.putProperty(obj, "viewId", node.viewIdResourceName ?: "")
        ScriptableObject.putProperty(obj, "clickable", node.isClickable)
        ScriptableObject.putProperty(obj, "enabled", node.isEnabled)
        ScriptableObject.putProperty(obj, "focused", node.isFocused)
        ScriptableObject.putProperty(obj, "scrollable", node.isScrollable)
        ScriptableObject.putProperty(obj, "x", bounds.left)
        ScriptableObject.putProperty(obj, "y", bounds.top)
        ScriptableObject.putProperty(obj, "width", bounds.width())
        ScriptableObject.putProperty(obj, "height", bounds.height())

        return obj
    }

    // テキストでノードを検索
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        return root.findAccessibilityNodeInfosByText(text) ?: emptyList()
    }

    // IDでノードを検索
    fun findNodeById(id: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
    }

    // ノードをクリック
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ノードにテキスト入力
    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // 座標タップ
    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // スワイプ
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // Rhinoスコープにブリッジを注入
    internal fun injectAccessibilityBridge() {
        val scope = HubService.rhinoScope ?: return
        val service = this

        // a11y オブジェクトとしてJS側に公開
        val cx = org.mozilla.javascript.Context.enter()
        cx.optimizationLevel = -1
        val a11y = cx.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

        // a11y.findByText(text) → ノード配列
        ScriptableObject.putProperty(a11y, "findByText", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val text = org.mozilla.javascript.Context.toString(args.getOrNull(0))
                val nodes = service.findNodesByText(text)
                val cx2 = org.mozilla.javascript.Context.enter()
                cx2.optimizationLevel = -1
                val arr = cx2.newArray(scope, nodes.map { service.nodeToMap(it) }.toTypedArray())
                org.mozilla.javascript.Context.exit()
                return arr
            }
        })

        // a11y.findById(id) → ノード
        ScriptableObject.putProperty(a11y, "findById", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val id = org.mozilla.javascript.Context.toString(args.getOrNull(0))
                val node = service.findNodeById(id) ?: return org.mozilla.javascript.Context.getUndefinedValue()
                return service.nodeToMap(node)
            }
        })

        // a11y.tap(x, y)
        ScriptableObject.putProperty(a11y, "tap", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val x = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
                val y = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f
                service.tap(x, y)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // a11y.swipe(x1, y1, x2, y2, duration?)
        ScriptableObject.putProperty(a11y, "swipe", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val x1 = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
                val y1 = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f
                val x2 = (args.getOrNull(2) as? Number)?.toFloat() ?: 0f
                val y2 = (args.getOrNull(3) as? Number)?.toFloat() ?: 0f
                val duration = (args.getOrNull(4) as? Number)?.toLong() ?: 300L
                service.swipe(x1, y1, x2, y2, duration)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // a11y.click(node) → nodeToMapで得たオブジェクトのx,y中心をタップ
        ScriptableObject.putProperty(a11y, "click", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val node = args.getOrNull(0) as? org.mozilla.javascript.NativeObject ?: return false
                val x = (ScriptableObject.getProperty(node, "x") as? Number)?.toFloat() ?: 0f
                val y = (ScriptableObject.getProperty(node, "y") as? Number)?.toFloat() ?: 0f
                val w = (ScriptableObject.getProperty(node, "width") as? Number)?.toFloat() ?: 0f
                val h = (ScriptableObject.getProperty(node, "height") as? Number)?.toFloat() ?: 0f
                service.tap(x + w / 2, y + h / 2)
                return true
            }
        })

        // a11y.back() → 戻るボタン
        ScriptableObject.putProperty(a11y, "back", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                service.performGlobalAction(GLOBAL_ACTION_BACK)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // a11y.home() → ホームボタン
        ScriptableObject.putProperty(a11y, "home", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                service.performGlobalAction(GLOBAL_ACTION_HOME)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // a11y.lastEvent → 最後のイベント情報
        ScriptableObject.putProperty(a11y, "getLastEvent", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val cx2 = org.mozilla.javascript.Context.enter()
                cx2.optimizationLevel = -1
                val obj = cx2.newObject(scope) as org.mozilla.javascript.NativeObject
                org.mozilla.javascript.Context.exit()
                ScriptableObject.putProperty(obj, "package", lastEventPackage)
                ScriptableObject.putProperty(obj, "class", lastEventClass)
                ScriptableObject.putProperty(obj, "text", lastEventText)
                return obj
            }
        })

        // a11y.isConnected → サービスが有効かチェック
        ScriptableObject.putProperty(a11y, "isConnected", instance != null)

        // a11y.onSuspicious(callback) → 不審操作検知コールバック登録
        ScriptableObject.putProperty(a11y, "onSuspicious", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                suspiciousEventCallback = args.getOrNull(0) as? org.mozilla.javascript.Function
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // a11y.clearSuspicious() → コールバック解除
        ScriptableObject.putProperty(a11y, "clearSuspicious", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                suspiciousEventCallback = null
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // a11y.getSuspiciousLog() → 蓄積した不審ログを返す
        ScriptableObject.putProperty(a11y, "getSuspiciousLog", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val cx2 = org.mozilla.javascript.Context.enter()
                cx2.optimizationLevel = -1
                val entries = suspiciousLog.map { entry ->
                    val obj = cx2.newObject(scope) as org.mozilla.javascript.NativeObject
                    entry.forEach { (k, v) ->
                        ScriptableObject.putProperty(obj, k, when (v) {
                            is Long -> v.toDouble()
                            is Int -> v.toDouble()
                            is Boolean -> v
                            else -> v.toString()
                        })
                    }
                    obj
                }
                val arr = cx2.newArray(scope, entries.toTypedArray())
                org.mozilla.javascript.Context.exit()
                return arr
            }
        })

        // a11y.addTrustedPackage(pkg) → 信頼済みパッケージを追加
        ScriptableObject.putProperty(a11y, "addTrustedPackage", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val pkg = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                (TRUSTED_PACKAGES as? MutableSet)?.add(pkg)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "a11y", a11y)
        android.util.Log.d("MWVScript", "a11yブリッジ注入完了")
    }
}
