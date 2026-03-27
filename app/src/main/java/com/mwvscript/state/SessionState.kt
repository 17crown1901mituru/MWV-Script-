package com.mwvscript.state

data class WebState(
    val sessions: List<WebSession> = emptyList(),
    val activeSessionId: String? = null
)

data class WebSession(
    val id: String,
    val url: String? = null,
    val title: String? = null,
    val state: String = "IDLE"
)

data class ScreenState(
    val state: String = "UNLOCKED",
    val brightness: Float = 1.0f
)

data class NetworkState(
    val state: String = "WIFI",
    val wifi: Boolean = true,
    val mobile: Boolean = false,
    val vpn: Boolean = false
)

data class A11yState(
    val state: String = "READY",
    val lastEvent: String? = null,
    val suspicious: Boolean = false,
    val trustedPackages: List<String> = emptyList()
)

data class NotificationState(
    val state: String = "IDLE",
    val last: String? = null
)

data class TileState(
    val state: String = "INACTIVE"
)

data class AlarmState(
    val state: String = "NONE",
    val nextTrigger: Long? = null
)

data class DeviceState(
    val screen: ScreenState = ScreenState(),
    val network: NetworkState = NetworkState(),
    val a11y: A11yState = A11yState(),
    val notification: NotificationState = NotificationState(),
    val tile: TileState = TileState(),
    val alarm: AlarmState = AlarmState()
)

data class InstalledApp(
    val pkg: String,
    val label: String? = null
)

data class ForegroundAppState(
    val state: String = "NONE",
    val pkg: String? = null,
    val activity: String? = null
)

data class UiTreeState(
    val pkg: String? = null,
    val root: String? = null // JSON 文字列などで持つ想定
)

data class AppsState(
    val installed: List<InstalledApp> = emptyList(),
    val foreground: ForegroundAppState = ForegroundAppState(),
    val uiTree: UiTreeState = UiTreeState()
)

data class FirewallRule(
    val id: String,
    val pkg: String? = null,
    val host: String? = null,
    val action: String = "ALLOW"
)

data class FirewallConnection(
    val id: String,
    val pkg: String? = null,
    val host: String? = null,
    val blocked: Boolean = false
)

data class FirewallState(
    val rules: List<FirewallRule> = emptyList(),
    val connections: List<FirewallConnection> = emptyList(),
    val blocked: List<String> = emptyList(),
    val log: List<String> = emptyList()
)

data class TaskState(
    val id: String,
    val type: String,
    val target: String? = null,
    val state: String = "WAITING_READY",
    val step: Int = 0,
    val totalSteps: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class TasksState(
    val running: List<TaskState> = emptyList(),
    val queue: List<TaskState> = emptyList(),
    val history: List<TaskState> = emptyList()
)

data class ErrorState(
    val id: String,
    val domain: String,
    val message: String,
    val timestamp: Long,
    val severity: String = "ERROR"
)

data class ErrorsState(
    val list: List<ErrorState> = emptyList(),
    val highestPriorityId: String? = null
)

data class PolicyAllowed(
    val shred: Boolean = false,
    val firewall: Boolean = false,
    val deviceOwner: Boolean = false,
    val shell: Boolean = false,
    val a11y: Boolean = true,
    val webEval: Boolean = true
)

data class PolicyState(
    val dangerEnabled: Boolean = false,
    val allowed: PolicyAllowed = PolicyAllowed()
)

data class MetaDeviceInfo(
    val model: String? = null,
    val android: Int? = null
)

data class MetaState(
    val version: Int = 1,
    val schema: String = "1.0.0",
    val lastUpdated: Long = 0L,
    val bootCount: Int = 0,
    val device: MetaDeviceInfo = MetaDeviceInfo()
)

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
