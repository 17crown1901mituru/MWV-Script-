package com.mwvscript.state

interface StateRepository {
    fun load(): SessionState
    fun save(state: SessionState)
}