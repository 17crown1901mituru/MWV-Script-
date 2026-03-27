package com.mwvscript.state

/**
 * MWV コントロールプレーンの「唯一の真実」。
 * すべてのドメイン状態をここに集約する。
 */
data class SessionState(
    val web: WebState = WebState(),
    val device: DeviceState = DeviceState(),
    val apps: AppsState = AppsState(),
    val firewall: FirewallState = FirewallState(),
    val tasks: TasksState = TasksState(),
    val errors: ErrorsState = ErrorsState(),
    val policy: PolicyState = PolicyState(),
    val meta: MetaState = MetaState()
)

/* ===========================
   WEB DOMAIN
   =========================== */

data class WebState(
    val sessions: List<WebSession> = emptyList(),
    val activeSessionId: String? = null
)

data class WebSession(
    val id: String,
    val url: String? = null,
    val title: String? = null,
    val state: String = "CREATED", // CREATED / LOADING / READY / BUSY / ERROR / CLOSED
    val lastEval: EvalInfo? = null
)

data class EvalInfo(
    val code: String,
    val status: String, // SUCCESS / ERROR
    val result: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

/* ===========================
   DEVICE DOMAIN
   =========================== */

data class DeviceState(
    val screen: ScreenState = ScreenState(),
    val network: NetworkState = NetworkState(),
    val a11y: A11yState = A11yState(),
    val notification: NotificationState = NotificationState(),
    val tile: TileState = TileState(),
    val alarm: AlarmState = AlarmState()
)

data class ScreenState(
    val state: String = "UNLOCKED", // OFF / ON / LOCKED / UNLOCKED
    val brightness: Float = 1.0f,
    val updatedAt: Long = System.currentTimeMillis()
)

data class NetworkState(
    val state: String = "DISCONNECTED", // DISCONNECTED / WIFI / MOBILE / VPN
    val wifi: Boolean = false,
    val mobile: Boolean = false,
    val vpn: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

data class A11yState(
    val state: String = "DISABLED", // DISABLED / ENABLING / READY / BUSY / SUSPICIOUS
    val lastEvent: String? = null,
    val suspicious: Boolean = false,
    val trustedPackages: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class NotificationState(
    val state: String = "IDLE", // IDLE / RECEIVED / ACTION_REQUIRED
    val last: NotificationInfo? = null
)

data class NotificationInfo(
    val pkg: String,
    val title: String?,
    val text: String?,
    val timestamp: Long = System.currentTimeMillis()
)

data class TileState(
    val state: String = "INACTIVE"
)

data class AlarmState(
    val state: String = "NONE",
    val nextTrigger: Long? = null
)

/* ===========================
   APPS DOMAIN
   =========================== */

data class AppsState(
    val installed: List<InstalledApp> = emptyList(),
    val foreground: ForegroundAppState = ForegroundAppState(),
    val uiTree: UiTreeState = UiTreeState()
)

data class InstalledApp(
    val pkg: String,
    val label: String? = null
)

data class ForegroundAppState(
    val state: String = "NONE", // NONE / SYSTEM / APP
    val pkg: String? = null,
    val activity: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class UiTreeState(
    val pkg: String? = null,
    val root: String? = null, // JSON 文字列で保持
    val updatedAt: Long = System.currentTimeMillis()
)

/* ===========================
   FIREWALL DOMAIN
   =========================== */

data class FirewallState(
    val rules: List<FirewallRule> = emptyList(),
    val connections: List<FirewallConnection> = emptyList(),
    val blocked: List<BlockedConnection> = emptyList(),
    val log: List<FirewallLog> = emptyList()
)

data class FirewallRule(
    val id: String,
    val pkg: String? = null,
    val ip: String? = null,
    val port: Int? = null,
    val action: String = "ALLOW", // ALLOW / BLOCK / MONITOR
    val reason: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class FirewallConnection(
    val pkg: String,
    val ip: String,
    val port: Int,
    val protocol: String = "TCP",
    val state: String = "ESTABLISHED",
    val timestamp: Long = System.currentTimeMillis()
)

data class BlockedConnection(
    val pkg: String,
    val ip: String,
    val port: Int,
    val protocol: String = "TCP",
    val timestamp: Long = System.currentTimeMillis()
)

data class FirewallLog(
    val time: Long = System.currentTimeMillis(),
    val level: String,
    val pkg: String?,
    val ip: String?,
    val event: String
)

/* ===========================
   TASKS DOMAIN
   =========================== */

data class TasksState(
    val running: List<TaskState> = emptyList(),
    val queue: List<TaskState> = emptyList(),
    val history: List<TaskState> = emptyList()
)

data class TaskState(
    val id: String,
    val type: String,
    val target: String? = null,
    val state: String = "WAITING_READY", // WAITING_READY / RUNNING / BLOCKED / ERROR / DONE
    val step: Int = 0,
    val totalSteps: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/* ===========================
   ERRORS DOMAIN
   =========================== */

data class ErrorsState(
    val list: List<ErrorState> = emptyList(),
    val highestPriorityId: String? = null
)

data class ErrorState(
    val id: String,
    val domain: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val severity: String = "ERROR" // INFO / WARN / ERROR / CRITICAL
)

/* ===========================
   POLICY DOMAIN
   =========================== */

data class PolicyState(
    val dangerEnabled: Boolean = false,
    val allowed: PolicyAllowed = PolicyAllowed(),
    val lastChangedAt: Long = System.currentTimeMillis()
)

data class PolicyAllowed(
    val shred: Boolean = false,
    val firewall: Boolean = false,
    val deviceOwner: Boolean = false,
    val shell: Boolean = false,
    val a11y: Boolean = true,
    val webEval: Boolean = true
)

/* ===========================
   META DOMAIN
   =========================== */

data class MetaState(
    val version: Int = 1,
    val schema: String = "1.0.0",
    val lastUpdated: Long = System.currentTimeMillis(),
    val bootCount: Int = 0,
    val device: MetaDeviceInfo = MetaDeviceInfo()
)

data class MetaDeviceInfo(
    val model: String? = null,
    val android: Int? = null
)