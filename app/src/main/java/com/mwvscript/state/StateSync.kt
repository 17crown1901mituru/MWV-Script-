package com.mwvscript.state

class StateSync(
    private val repository: StateRepository
) {
    @Volatile
    var current: SessionState = repository.load()
        private set

    fun update(transform: (SessionState) -> SessionState) {
        val newState = transform(current)
        current = newState
        repository.save(newState)
    }

    // 例：画面状態更新用のヘルパー（中身はまだ空でOK）
    fun onScreenStateChanged(state: String) {
        update { s ->
            s.copy(device = s.device.copy(screen = s.device.screen.copy(state = state)))
        }
    }

    // 他にも必要になったらここに追加していく
}