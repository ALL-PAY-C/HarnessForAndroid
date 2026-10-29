package dev.dsh.mobile.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.app.nav.AppNav
import dev.dsh.mobile.app.notify.NotificationCenter
import dev.dsh.mobile.app.service.RuntimeService
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.data.settings.AppSettings
import dev.dsh.mobile.core.data.settings.ThemeMode
import dev.dsh.mobile.design.DshTheme
import dev.dsh.mobile.design.startup.StartupAnimationGate
import dev.dsh.mobile.design.ThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private const val TERMUX_PACKAGE = "com.termux"
private const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"
private const val REQ_TERMUX = 1001
private const val REQ_NOTIFY = 1002

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as DshApplication).container
        RuntimeService.start(this)
        requestTermuxPermission()
        requestNotifyPermission()
        // 通知点击进会话：冷启动走 onCreate，热启动走 onNewIntent。
        container.openSessionFromNotification(intent?.getStringExtra(NotificationCenter.EXTRA_SESSION_ID))

        // 仅 debug：进程内 Node 引擎自检（am start --ez dsh_engine_selftest true）。
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("dsh_engine_selftest", false) == true) {
            container.scope.launch(Dispatchers.IO) {
                // 插件根目录：引擎按 <root>/<id>/versions/<ver> 扫，离线预置包也在这里。
                val pluginDir = File(filesDir, "plugins")
                container.nodeEngine.ensureStarted(listOf(pluginDir))
                    .onSuccess { port ->
                        AppLog.i("ENGINE_SELFTEST start ok port=$port")
                        runCatching { container.nodeEngine.rpc("/skill-manager", "list") }
                            .onSuccess { AppLog.i("ENGINE_SELFTEST " + it) }
                            .onFailure { AppLog.e("ENGINE_SELFTEST rpc failed: ${it.message}", it) }
                        // --ez dsh_engine_llm true：验 /llm 路由（插件 provider 是否注册 + 真发一次 chat）。
                        if (intent?.getBooleanExtra("dsh_engine_llm", false) == true) {
                            // 等宿主那批 plugins.load 落完（NodeBackedPlugin 在 Application 里异步跑）。
                            kotlinx.coroutines.delay(8_000)
                            runCatching { container.nodeEngine.rpc("/llm", "models", org.json.JSONObject()) }
                                .onSuccess { AppLog.i("ENGINE_LLM models=" + it) }
                                .onFailure { AppLog.e("ENGINE_LLM models failed: ${it.message}", it) }
                            val req = org.json.JSONObject()
                                .put("model", intent?.getStringExtra("dsh_engine_llm_model") ?: "deepseek-v4.1-flash")
                                .put(
                                    "messages",
                                    org.json.JSONArray().put(
                                        org.json.JSONObject().put("role", "user").put("content", "只回两个字：收到"),
                                    ),
                                )
                            runCatching { container.nodeEngine.rpc("/llm", "chat", req) }
                                .onSuccess { AppLog.i("ENGINE_LLM chat=" + it) }
                                .onFailure { AppLog.e("ENGINE_LLM chat failed: ${it.message}", it) }
                        }
                        // 加 --ez dsh_engine_stop true 时顺手验 stop()：发 shutdown 后 node 是否自己退出。
                        if (intent?.getBooleanExtra("dsh_engine_stop", false) == true) {
                            container.nodeEngine.stop()
                            AppLog.i("ENGINE_SELFTEST stopped state=${container.nodeEngine.state.value}")
                        }
                    }
                    .onFailure { AppLog.e("ENGINE_SELFTEST start failed: ${it.message}", it) }
            }
        }

        setContent {
            val settings by container.settings.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings.DEFAULT)

            val dark = when (settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            val tokens = ThemeTokens(
                accent = Color(settings.accent),
                bubbleMine = Color(settings.bubbleMine),
                bubbleOther = Color(settings.bubbleOther),
                cornerRadius = settings.cornerRadiusDp.dp,
                fontScale = settings.fontScale,
                density = settings.density,
                compact = settings.compactBubbles,
                // 外观：图标 / 壁纸 / 压暗 / 主色相 / 描边 / 材质。settings 来自 collectAsStateWithLifecycle，
                // 改设置即换一个 AppSettings 实例 → recomposition → 整棵树按新外观重绘。
                // 材质走 settings.material（字符串 backMaterial 的类型化读法），一处解析、全树消费。
                appIconStyle = settings.appIconStyle,
                customIconUri = settings.customIconUri,
                backgroundUri = settings.backgroundUri,
                backgroundDim = settings.backgroundDim,
                accentHue = settings.accentHue,
                borderWidth = settings.borderWidthDp.dp,
                backMaterial = settings.material,
                glassAlpha = settings.glassAlpha,
                glassBlur = settings.glassBlurDp.dp,
            )
            DshTheme(tokens = tokens, dark = dark) {
                // 启动动画：spec 为空（默认）时 StartupAnimationGate 直接铺 content，不叠加任何节点。
                StartupAnimationGate(
                    spec = settings.startupAnimation,
                    durationMs = settings.startupAnimationMs,
                ) {
                    AppNav(container = container)
                }
            }
        }
    }

    /**
     * Termux 通道用的是 com.termux.permission.RUN_COMMAND 这个第三方自定义权限：
     * 系统不会自动弹，装了 Termux 也只会在清单里声明而已，必须显式申请一次。
     * 没装 Termux 就不打扰用户（应用内 Shell / Shizuku 仍然可用）。
     */
    private fun requestTermuxPermission() {
        if (checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED) return
        val installed = runCatching {
            packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        }.isSuccess
        if (!installed) return
        runCatching { requestPermissions(arrayOf(TERMUX_PERMISSION), REQ_TERMUX) }
            .onFailure { AppLog.w("Termux RUN_COMMAND 授权申请失败：${it.message}") }
    }

    /**
     * Android 13+ 的通知权限是运行时权限，不申请的话 [NotificationCenter] 与
     * 常驻聚合通知的所有 notify 都会被系统静默丢弃（用户只看到「通知不出现」）。
     * 首次进入就问一次，之后由系统记住。
     */
    private fun requestNotifyPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY) }
            .onFailure { AppLog.w("通知权限申请失败：${it.message}") }
    }

    /**
     * 按 Home 或切到别处时把「应用在前台」这个标志放下：否则 NotificationCenter 会
     * 一直以为用户还在看着那个会话，完成通知与待确认通知被统统静默掉——用户切出去
     * 等结果，结果什么都没响。
     */
    override fun onStart() {
        super.onStart()
        NotificationCenter.appInForeground = true
        (application as DshApplication).container.ongoingCenter.refresh()
    }

    override fun onStop() {
        super.onStop()
        NotificationCenter.appInForeground = false
        (application as DshApplication).container.ongoingCenter.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (application as DshApplication).container
            .openSessionFromNotification(intent.getStringExtra(NotificationCenter.EXTRA_SESSION_ID))
    }
}
