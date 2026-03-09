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

        // 最後に検知したイベント情報
        var lastEventPackage: String = ""
        var lastEventClass: String = ""
        var lastEventText: String = ""
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        android.util.Log.d("MWVScript", "AccessibilityService 接続完了")

        // Rhinoスコープにブリッジを登録
        injectAccessibilityBridge()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        lastEventPackage = event.packageName?.toString() ?: ""
        lastEventClass = event.className?.toString() ?: ""
        lastEventText = event.text.joinToString(" ")
    }

    override fun onInterrupt() {
        android.util.Log.d("MWVScript", "AccessibilityService 中断")
    }

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

        ScriptableObject.putProperty(scope, "a11y", a11y)
        android.util.Log.d("MWVScript", "a11yブリッジ注入完了")
    }
}
