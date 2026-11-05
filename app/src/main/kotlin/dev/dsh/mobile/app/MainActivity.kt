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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.app.nav.AppNav
import dev.dsh.mobile.app.notify.NotificationCenter
import dev.dsh.mobile.app.service.RuntimeService
import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.core.platform.UiLocale
import dev.dsh.mobile.core.data.settings.AppSettings
import dev.dsh.mobile.core.data.settings.ThemeMode
import dev.dsh.mobile.design.DshTheme
import dev.dsh.mobile.design.LocalUiLanguage
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

    /**
     * Activity 也要换语言上下文：Compose 的 AlertDialog 会为弹窗新开一个窗口，
     * 那个窗口的配置取自 Activity 的 base，而不是 Theme 里 CompositionLocalProvider 包过的那份
     * —— 只包 Compose 的话，主界面中文、弹窗英文（实测「添加模型」对话框就是这样）。
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(UiLocale.wrap(newBase))
    }

    /** 首帧耗时诊断用：onCreate 起点（uptimeMillis）。 */
    private var tCreate: Long = 0L

    /** 首帧是否已经过去（onStart 早于首帧，据此决定 refresh 放哪）。 */
    private var firstFrameDrawn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        tCreate = android.os.SystemClock.uptimeMillis()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as DshApplication).container
        // 这几件事都不影响第一帧长什么样，别压在第一帧的关键路径上：
        // 前台服务启动、两个权限弹窗、通知点进来的会话跳转，统一等首帧画完再做（T7.4 冷启动优化）。
        val pendingSessionId = intent?.getStringExtra(NotificationCenter.EXTRA_SESSION_ID)
        afterFirstFrame {
            RuntimeService.start(this)
            requestTermuxPermission()
            requestNotifyPermission()
            // 通知点击进会话：冷启动走 onCreate，热启动走 onNewIntent。
            container.openSessionFromNotification(pendingSessionId)
            // onStart 里那次 refresh（构造 OngoingCenter + render + notify）在首帧前被跳过，
            // 这里补上：面板状态照样刷新，只是不再挤进首绘窗口。
            container.ongoingCenter.refresh()
        }

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
            // 语言：DataStore 是唯一事实来源，启动镜像（prefs）与它不一致时补写——
            // 清过数据、或从没有 ui_language 的旧版本升级上来时都会走到这里。
            LaunchedEffect(settings.uiLanguage) {
                UiLocale.mirror(applicationContext, settings.uiLanguage)
            }

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
            // 语言只在这里提供一次：DshTheme 覆盖 LocalContext → 全站 stringResource 换语言，
            // 而 settings 变更是重组不是生命周期事件，ViewModel 与页面状态都不重建（T9.11 §2.3）。
            CompositionLocalProvider(LocalUiLanguage provides settings.uiLanguage) {
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
    }

    /** 首帧画完再跑：比 View.post 更准，动画/组合都已完成一次遍历。 */
    private fun afterFirstFrame(block: () -> Unit) {
        val choreographer = android.view.Choreographer.getInstance()
        choreographer.postFrameCallback(
            object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    AppLog.i("first frame since onCreate " + (android.os.SystemClock.uptimeMillis() - tCreate) + "ms")
                    firstFrameDrawn = true
                    // 再等一帧：这个回调还挂在「首帧」的 animation 阶段上，首次 composition/
                    // draw 与它的 EGL 初始化都在同一帧里。业务块（service 启动、权限弹窗、
                    // 会话跳转）留在这里会和首绘抢主线程，推到下一帧的空档里执行。
                    choreographer.postFrameCallback(
                        object : android.view.Choreographer.FrameCallback {
                            override fun doFrame(frameTimeNanos: Long) {
                                runCatching(block).onFailure {
                                    AppLog.w("afterFirstFrame failed: " + it.message)
                                }
                            }
                        },
                    )
                }
            },
        )
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
        // 冷启动的 onStart 跑在首帧之前：此刻 refresh() 会同步构造 OngoingCenter（lazy）
        // 并做一次 render + notify IPC，正好压进首帧窗口。首帧后由 afterFirstFrame 补一次。
        if (firstFrameDrawn) (application as DshApplication).container.ongoingCenter.refresh()
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
