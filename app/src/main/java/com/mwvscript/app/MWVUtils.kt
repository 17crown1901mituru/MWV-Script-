package com.mwvscript.app

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * pkg.Utils に相当するユーティリティクラス
 * JAScriptの pkg.Utils を参考に実装
 */
class MWVUtils(private val activity: Activity) {

    fun setActionBarBackgroundColor(ctx: Activity, color: Int) {
        activity.runOnUiThread {
            ctx.actionBar?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(color)
            )
        }
    }

    fun setStatusBarBackgroundColor(ctx: Activity, color: Int) {
        activity.runOnUiThread {
            ctx.window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            ctx.window.statusBarColor = color
        }
    }

    fun setNavigationBarBackgroundColor(ctx: Activity, color: Int) {
        activity.runOnUiThread {
            ctx.window.navigationBarColor = color
        }
    }

    fun getDisplayWidth(ctx: Activity): Int {
        return ctx.windowManager.currentWindowMetrics.bounds.width()
    }

    fun getDisplayHeight(ctx: Activity): Int {
        return ctx.windowManager.currentWindowMetrics.bounds.height()
    }

    fun getApiLevel(): Int = Build.VERSION.SDK_INT

    private var progressDialog: android.app.ProgressDialog? = null

    fun showProgress(ctx: Activity, message: String) {
        activity.runOnUiThread {
            progressDialog = android.app.ProgressDialog(ctx).apply {
                setMessage(message)
                setCancelable(false)
                show()
            }
        }
    }

    fun hideProgress() {
        activity.runOnUiThread {
            progressDialog?.dismiss()
            progressDialog = null
        }
    }
}
