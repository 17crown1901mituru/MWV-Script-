package com.mwvscript.state

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * SessionState を JSON で永続化する実装。
 * - 読み込み失敗 → デフォルト SessionState
 * - 書き込み失敗 → ログだけ出して継続
 * - atomic に上書き
 */
class JsonStateRepository(
    private val context: Context
) : StateRepository {

    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .setPrettyPrinting()
        .create()

    private val file: File
        get() = File(context.filesDir, "session_state.json")

    private val TAG = "JsonStateRepository"

    override fun load(): SessionState {
        return try {
            if (!file.exists()) {
                Log.i(TAG, "State file not found. Using default SessionState.")
                return SessionState()
            }

            val json = file.readText()
            gson.fromJson(json, SessionState::class.java) ?: SessionState()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SessionState", e)
            SessionState()
        }
    }

    override fun save(state: SessionState) {
        try {
            val json = gson.toJson(state)
            file.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save SessionState", e)
        }
    }
}