import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku
import com.mwvscript.app.IUserService

object ShizukuBridge {
    private var userService: IUserService? = null

    // HubService内のスコープ初期化箇所
ScriptableObject.putProperty(scope, "shizuku", Context.javaToJS(MainActivity.ShizukuBridge, scope))


    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userService = IUserService.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.mwvscript.app", UserService::class.java.name)
    ).daemon(false).processNameSuffix("shizuku_service").debuggable(true)

    // サービスのバインドを開始
    fun bind() {
        if (Shizuku.pingBinder()) {
            Shizuku.bindUserService(userServiceArgs, connection)
        }
    }

    // コマンド実行用関数 (rjsから叩く受口)
    fun runShell(command: String): String {
        return userService?.exec(command) ?: "Service not connected"
    }
}
