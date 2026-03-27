package com.mwvscript.state

import android.content.Context

interface StateRepository {
    fun load(): SessionState
    fun save(state: SessionState)
}

class JsonStateRepository(
    private val context: Context
) : StateRepository {

    override fun load(): SessionState {
        // TODO: 実装（今はデフォルト状態を返すだけ）
        return SessionState()
    }

    override fun save(state: SessionState) {
        // TODO: 実装（今は何もしない）
    }
}