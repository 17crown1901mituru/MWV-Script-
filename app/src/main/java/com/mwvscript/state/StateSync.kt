package com.mwvscript.state

import android.util.Log

/**
 * MWV の「状態モデルの唯一の入口」。
 * すべての Service / Activity / rjs ブリッジはここを通して状態を更新する。
 *
 * - 内部状態を持たない
 * - immutable な SessionState を毎回コピーして更新
 * - repository に確実に保存
 */
class StateSync(
    private val repository: StateRepository
) {

    @Volatile
    var current: SessionState = repository.load()
        private set

    private val TAG = "StateSync"

    /** 汎用更新関数（全更新は必ずこれを通す） */
    fun update(transform: (SessionState) -> SessionState) {
        val newState = try {
            transform(current)
        } catch (e: Exception) {
            Log.e(TAG, "State transform error", e)
            current // 失敗時は現状維持
        }

        current = newState
        repository.save(newState)
    }

    /* ===========================
       DEVICE: SCREEN
       =========================== */

    fun onScreenStateChanged(state: String, brightness: Float? = null) {
        update { s ->
            s.copy(
                device = s.device.copy(
                    screen = s.device.screen.copy(
                        state = state,
                        brightness = brightness ?: s.device.screen.brightness,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            )
        }
    }

    /* ===========================
       DEVICE: NETWORK
       =========================== */

    fun onNetworkChanged(
        state: String,
        wifi: Boolean,
        mobile: Boolean,
        vpn: Boolean
    ) {
        update { s ->
            s.copy(
                device = s.device.copy(
                    network = s.device.network.copy(
                        state = state,
                        wifi = wifi,
                        mobile = mobile,
                        vpn = vpn,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            )
        }
    }

    /* ===========================
       DEVICE: A11Y
       =========================== */

    fun onA11yStateChanged(
        state: String,
        lastEvent: String? = null,
        suspicious: Boolean? = null
    ) {
        update { s ->
            s.copy(
                device = s.device.copy(
                    a11y = s.device.a11y.copy(
                        state = state,
                        lastEvent = lastEvent ?: s.device.a11y.lastEvent,
                        suspicious = suspicious ?: s.device.a11y.suspicious,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            )
        }
    }

    /* ===========================
       APPS: FOREGROUND
       =========================== */

    fun onForegroundAppChanged(pkg: String?, activity: String?, state: String) {
        update { s ->
            s.copy(
                apps = s.apps.copy(
                    foreground = ForegroundAppState(
                        state = state,
                        pkg = pkg,
                        activity = activity,
                        timestamp = System.currentTimeMillis()
                    )
                )
            )
        }
    }

    /* ===========================
       APPS: UI TREE
       =========================== */

    fun onUiTreeUpdated(pkg: String?, rootJson: String?) {
        update { s ->
            s.copy(
                apps = s.apps.copy(
                    uiTree = UiTreeState(
                        pkg = pkg,
                        root = rootJson,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            )
        }
    }

    /* ===========================
       FIREWALL
       =========================== */

    fun onFirewallConnectionsUpdated(list: List<FirewallConnection>) {
        update { s ->
            s.copy(
                firewall = s.firewall.copy(
                    connections = list
                )
            )
        }
    }

    fun onFirewallRuleAdded(rule: FirewallRule) {
        update { s ->
            s.copy(
                firewall = s.firewall.copy(
                    rules = s.firewall.rules + rule
                )
            )
        }
    }

    fun onFirewallBlocked(conn: BlockedConnection) {
        update { s ->
            s.copy(
                firewall = s.firewall.copy(
                    blocked = s.firewall.blocked + conn
                )
            )
        }
    }

    /* ===========================
       TASKS
       =========================== */

    fun addTask(task: TaskState) {
        update { s ->
            s.copy(
                tasks = s.tasks.copy(
                    queue = s.tasks.queue + task
                )
            )
        }
    }

    fun updateTask(taskId: String, transform: (TaskState) -> TaskState) {
        update { s ->
            val running = s.tasks.running.map { if (it.id == taskId) transform(it) else it }
            val queue = s.tasks.queue.map { if (it.id == taskId) transform(it) else it }
            val history = s.tasks.history.map { if (it.id == taskId) transform(it) else it }

            s.copy(
                tasks = s.tasks.copy(
                    running = running,
                    queue = queue,
                    history = history
                )
            )
        }
    }

    /* ===========================
       ERRORS
       =========================== */

    fun pushError(error: ErrorState) {
        update { s ->
            val newList = s.errors.list + error
            val highest = newList.maxByOrNull { priorityOf(it.severity) }?.id

            s.copy(
                errors = ErrorsState(
                    list = newList,
                    highestPriorityId = highest
                )
            )
        }
    }

    private fun priorityOf(sev: String): Int = when (sev) {
        "CRITICAL" -> 4
        "ERROR" -> 3
        "WARN" -> 2
        "INFO" -> 1
        else -> 0
    }

    /* ===========================
       POLICY
       =========================== */

    fun setDangerEnabled(enabled: Boolean) {
        update { s ->
            s.copy(
                policy = s.policy.copy(
                    dangerEnabled = enabled,
                    lastChangedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun setPolicyAllowed(transform: (PolicyAllowed) -> PolicyAllowed) {
        update { s ->
            s.copy(
                policy = s.policy.copy(
                    allowed = transform(s.policy.allowed),
                    lastChangedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /* ===========================
       META
       =========================== */

    fun updateMeta(transform: (MetaState) -> MetaState) {
        update { s ->
            s.copy(meta = transform(s.meta))
        }
    }
}