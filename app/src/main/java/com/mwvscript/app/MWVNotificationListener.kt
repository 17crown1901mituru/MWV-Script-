package com.mwvscript.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

class MWVNotificationListener : NotificationListenerService() {

    companion object {
        var instance: MWVNotificationListener? = null
        const val CHANNEL_ID  = "mwv_notify_out"
        const val CHANNEL_NAME = "MWV Script 通知"
        private var notifyIdCounter = 2000

        // rjsから登録されたリスナーコールバック
        val listeners = mutableListOf<org.mozilla.javascript.Function>()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createOutputChannel()
        // Rhinoが既に起動済みならブリッジ注入
        HubService.rhinoScope?.let { injectNotifyBridge() }
    }

    override fun onDestroy() {
        instance = null
        listeners.clear()
        super.onDestroy()
    }

    // 通知を受信したとき
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (listeners.isEmpty()) return
        val extras = sbn.notification.extras
        val title   = extras.getString("android.title") ?: ""
        val text    = extras.getCharSequence("android.text")?.toString() ?: ""
        val pkg     = sbn.packageName ?: ""
        val id      = sbn.id

        val scope = HubService.rhinoScope ?: return
        val cx = org.mozilla.javascript.Context.enter()
        cx.optimizationLevel = -1
        try {
            val obj = cx.newObject(scope) as NativeObject
            ScriptableObject.putProperty(obj, "title",   title)
            ScriptableObject.putProperty(obj, "text",    text)
            ScriptableObject.putProperty(obj, "package", pkg)
            ScriptableObject.putProperty(obj, "id",      id)
            listeners.toList().forEach { fn ->
                try { fn.call(cx, scope, scope, arrayOf(obj)) }
                catch (e: Exception) { android.util.Log.e("MWVNotify", "listener: ${e.message}") }
            }
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    // ======================================================
    // Rhinoブリッジ注入
    // ======================================================
    fun injectNotifyBridge() {
        val scope = HubService.rhinoScope ?: return
        val service = this

        val cx = org.mozilla.javascript.Context.enter()
        cx.optimizationLevel = -1
        val notify = cx.newObject(scope) as NativeObject
        org.mozilla.javascript.Context.exit()

        // notify.send(title, text, id?) → 通知を出す
        ScriptableObject.putProperty(notify, "send", object : BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val title = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                val text  = org.mozilla.javascript.Context.toString(args.getOrNull(1) ?: "")
                val id    = (args.getOrNull(2) as? Number)?.toInt() ?: notifyIdCounter++
                val nm    = service.getSystemService(NotificationManager::class.java)
                val intent = service.packageManager.getLaunchIntentForPackage(service.packageName) ?: Intent()
                val pi = PendingIntent.getActivity(service, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val notif = NotificationCompat.Builder(service, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                nm.notify(id, notif)
                return id
            }
        })

        // notify.cancel(id) → 通知を消す
        ScriptableObject.putProperty(notify, "cancel", object : BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val id = (args.getOrNull(0) as? Number)?.toInt() ?: return org.mozilla.javascript.Context.getUndefinedValue()
                service.getSystemService(NotificationManager::class.java).cancel(id)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // notify.cancelAll() → 全通知を消す
        ScriptableObject.putProperty(notify, "cancelAll", object : BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                service.cancelAllNotifications()
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // notify.listen(callback) → 通知受信時にコールバック
        ScriptableObject.putProperty(notify, "listen", object : BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val fn = args.getOrNull(0) as? org.mozilla.javascript.Function ?: return org.mozilla.javascript.Context.getUndefinedValue()
                listeners.add(fn)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // notify.clearListeners() → リスナー全解除
        ScriptableObject.putProperty(notify, "clearListeners", object : BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                listeners.clear()
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // notify.getActive() → 現在表示中の通知一覧
        ScriptableObject.putProperty(notify, "getActive", object : BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val sbns = try { service.activeNotifications } catch (e: Exception) { emptyArray() }
                val cx2 = org.mozilla.javascript.Context.enter()
                cx2.optimizationLevel = -1
                val arr = cx2.newArray(scope, sbns.map { sbn ->
                    val extras = sbn.notification.extras
                    val obj = cx2.newObject(scope) as NativeObject
                    ScriptableObject.putProperty(obj, "title",   extras.getString("android.title") ?: "")
                    ScriptableObject.putProperty(obj, "text",    extras.getCharSequence("android.text")?.toString() ?: "")
                    ScriptableObject.putProperty(obj, "package", sbn.packageName ?: "")
                    ScriptableObject.putProperty(obj, "id",      sbn.id)
                    obj
                }.toTypedArray())
                org.mozilla.javascript.Context.exit()
                return arr
            }
        })

        ScriptableObject.putProperty(scope, "notify", notify)
        android.util.Log.d("MWVNotify", "notifyブリッジ注入完了")
    }

    private fun createOutputChannel() {
        val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
