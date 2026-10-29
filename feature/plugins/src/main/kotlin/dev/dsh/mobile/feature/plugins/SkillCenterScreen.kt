package dev.dsh.mobile.feature.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.res.AssetManager
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.design.EmptyState
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.design.SectionCard
import dev.dsh.mobile.design.SectionTitle
import dev.dsh.mobile.runtimehost.NodeEngine
import dev.dsh.mobile.runtimehost.NodeEngineException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 「技能中心」：桌面端 DSH 左侧那条技能列表在 App 里的对应页。
 *
 * 数据只走 Node 引擎的 `/skill-manager list`；开关键走 `set-enabled`（scope=global），
 * 先乐观更新、失败回滚并弹 snackbar —— 引擎才是唯一真相，界面不自己记状态。
 *
 * 引擎不内置（[engine] == null）时这一屏只解释原因，不去碰运行时的生命周期。
 */
@Composable
fun SkillCenterScreen(
    engine: NodeEngine?,
    pluginDirs: List<File>,
    modifier: Modifier = Modifier,
) {
    val p = LocalQq.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<SkillUiState>(SkillUiState.Loading) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installOpen by remember { mutableStateOf(false) }
    var installInput by remember { mutableStateOf("") }
    var installBusy by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<SkillEntry?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    var bundledNames by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 宿主给的目录来自「已安装插件」注册表，它只覆盖从界面装过的插件；随包投放进
    // filesDir/plugins 的技能插件只能靠磁盘发现补上。注册表非空（里面躺着 boot 这类普通插件）
    // 也不能跳过发现，所以两者取并集而不是二选一。
    val pluginsRoot = remember(context) { File(context.filesDir, "plugins") }
    val dirs = remember(pluginDirs, pluginsRoot) { skillPluginDirs(pluginDirs, pluginsRoot) }

    // 内置判定只读一次随包资产清单（assets 是只读的，运行期不会变）。
    LaunchedEffect(context) {
        bundledNames = withContext(Dispatchers.IO) { bundledSkillNames(context.assets) }
    }

    LaunchedEffect(engine, dirs, reload) {
        state = SkillUiState.Loading
        // 首启把随包技能铺进技能库（幂等：有 .builtin-bootstrapped 就直接返回空）
        val seeded = withContext(Dispatchers.IO) {
            runCatching { bootstrapBundledSkills(context.assets, skillsLibraryRoot(context.filesDir)) }
                .getOrDefault(emptyList())
        }
        state = loadSkills(engine, dirs)
        // 技能是直接铺进库的，引擎自己的「首启自动启用」已经被我们的标记挡掉了，这里显式点亮。
        // 失败不打扰用户：列表照旧显示，技能只是默认关闭，用户能自己打开开关。
        if (seeded.isNotEmpty() && engine != null) {
            withContext(Dispatchers.IO) {
                seeded.forEach { name ->
                    runCatching { engine.rpc("/skill-manager", "enable", JSONObject().put("name", name)) }
                }
            }
            state = loadSkills(engine, dirs)
        }
    }

    /** 乐观切换：先翻 UI，再问引擎；引擎拒绝就翻回去并说原因。 */
    fun toggle(entry: SkillEntry, target: Boolean) {
        val current = state as? SkillUiState.Ready ?: return
        if (engine == null || entry.name in busy || entry.effectiveEnabled == target) return
        val previous = entry.effectiveEnabled
        busy = busy + entry.name
        state = SkillUiState.Ready(
            current.entries.map {
                if (it.name == entry.name) it.copy(globalEnabled = target, effectiveEnabled = target) else it
            }
        )
        scope.launch {
            val outcome = runCatching {
                engine.rpc("/skill-manager", "set-enabled", setEnabledPayload(entry.name, "global", target))
            }
            busy = busy - entry.name
            val failure = outcome.exceptionOrNull()
            val reply = outcome.getOrNull()
            val refused = reply != null && !reply.optBoolean("ok", true)
            if (failure != null || refused) {
                val now = state as? SkillUiState.Ready
                if (now != null) {
                    state = SkillUiState.Ready(
                        now.entries.map {
                            if (it.name == entry.name) {
                                it.copy(globalEnabled = previous, effectiveEnabled = previous)
                            } else {
                                it
                            }
                        }
                    )
                }
                val reason = (failure as? NodeEngineException)?.message
                    ?: failure?.message
                    ?: reply?.optJSONObject("error")?.optString("message").orEmpty()
                snackbar.showSnackbar("切换失败：${reason.ifBlank { "引擎拒绝了这次改动" }}")
            }
        }
    }

    /**
     * 装一个技能：下载 zip → 解压到 cacheDir 的 staging → 交给技能管理器的 install endpoint。
     *
     * 管理器内部用 skills CLI 把 staging 复制进技能根，所以移动端不需要 guest 里的 git。
     * staging 无论成败都删（管理器已经把文件复制走了）；[installBusy] 挡住连点，
     * 协程挂在页面 scope 上，页面销毁即取消。
     */
    fun installSkill(input: String) {
        if (installBusy) return
        val node = engine
        if (node == null) {
            installError = "这个构建未内置 Node 运行时，装不了技能"
            return
        }
        installBusy = true
        installError = null
        scope.launch {
            var staged: File? = null
            val failure = runCatching {
                val root = withContext(Dispatchers.IO) { SkillInstaller.install(context.cacheDir, input) }
                staged = root
                val reply = withContext(Dispatchers.IO) {
                    node.rpc("/skill-manager", "install", JSONObject().put("path", root.absolutePath))
                }
                if (!reply.optBoolean("ok", true)) {
                    val reason = reply.optJSONObject("error")?.optString("message").orEmpty()
                    error(reason.ifBlank { "技能管理器拒绝了这次安装" })
                }
            }.exceptionOrNull()
            runCatching { staged?.deleteRecursively() }
            installBusy = false
            if (failure == null) {
                installOpen = false
                installInput = ""
                snackbar.showSnackbar("技能已安装")
                reload++
            } else {
                installError = (failure as? NodeEngineException)?.message ?: failure.message ?: failure.toString()
            }
        }
    }

    /**
     * 移除技能：确认后先让这一行进 busy（行内按钮换转圈、[deleteBusy] 挡连点），再调
     * `/skill-manager remove`。引擎把移除后的新目录随回包一起带回来
     * （skill-manager.js:433 `return this.catalog(...)`），所以成功路径直接吃回包、不再跑一趟
     * list；回包里要是没有 entries 才退回重载。失败一条都不动列表 —— 引擎才是唯一真相。
     */
    fun removeSkill(entry: SkillEntry) {
        val node = engine
        if (node == null || deleteBusy) return
        deleteBusy = true
        busy = busy + entry.name
        scope.launch {
            val outcome = runCatching {
                node.rpc("/skill-manager", "remove", removePayload(entry.name))
            }
            val failure = outcome.exceptionOrNull()
            val reply = outcome.getOrNull()
            val refused = reply != null && !reply.optBoolean("ok", true)
            deleteBusy = false
            deleteTarget = null
            busy = busy - entry.name
            if (failure != null || refused) {
                val reason = (failure as? NodeEngineException)?.message
                    ?: failure?.message
                    ?: reply?.optJSONObject("error")?.optString("message").orEmpty()
                snackbar.showSnackbar(removeFailureNotice(reason))
                return@launch
            }
            val refreshed = reply?.let { catalogState(it) }
            if (refreshed is SkillUiState.Ready) state = refreshed else reload++
            snackbar.showSnackbar(removedNotice(entry.name))
        }
    }

    Column(modifier.fillMaxSize().background(p.bg)) {
        QqTopBar(
            content = { Text("技能中心", color = p.t1, fontWeight = FontWeight.SemiBold) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.Filled.Add, "安装技能") {
                        installError = null
                        installOpen = true
                    }
                    IconBtn(Icons.Filled.Refresh, "刷新") { reload++ }
                }
            },
        )
        Box(Modifier.fillMaxSize()) {
            when (val s = state) {
                is SkillUiState.Loading -> LoadingBody()
                is SkillUiState.NoEngine -> EmptyState(
                    title = "未内置 Node 运行时",
                    subtitle = "这个构建里没有打包 libnode，技能列表读不到",
                )
                is SkillUiState.Failed -> FailureBody(stage = s.stage, message = s.message) { reload++ }
                is SkillUiState.Ready -> if (s.entries.isEmpty()) {
                    EmptyState(
                        title = "还没有安装任何技能",
                        subtitle = "点右上角「+」，贴一个含 SKILL.md 的 GitHub 仓库或 zip 直链就能直接装",
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        item { SectionTitle("技能", s.entries.size) }
                        item {
                            SectionCard {
                                s.entries.forEachIndexed { index, entry ->
                                    SkillRow(
                                        entry = entry,
                                        busy = entry.name in busy,
                                        onToggle = { toggle(entry, it) },
                                        onDelete = { deleteTarget = entry },
                                    )
                                    if (index != s.entries.lastIndex) QqCardDivider()
                                }
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        if (installOpen) {
            InstallSkillDialog(
                input = installInput,
                onInputChange = { installInput = it },
                busy = installBusy,
                error = installError,
                onConfirm = { installSkill(installInput.trim()) },
                onDismiss = { if (!installBusy) installOpen = false },
            )
        }
        deleteTarget?.let { target ->
            RemoveSkillDialog(
                name = target.name,
                bundled = target.name in bundledNames,
                busy = deleteBusy,
                onConfirm = { removeSkill(target) },
                onDismiss = { if (!deleteBusy) deleteTarget = null },
            )
        }
    }
}

/**
 * 「安装技能」弹窗：输入仓库/直链，确认后显示进度；失败原因（HTTP 码、缺 SKILL.md、
 * 管理器回包）留在弹窗里，不关窗也不崩。
 */
@Composable
private fun InstallSkillDialog(
    input: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalQq.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("安装技能", color = p.t1, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    singleLine = true,
                    enabled = !busy,
                    label = { Text("仓库 / 链接") },
                    placeholder = { Text("https://github.com/owner/repo") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "支持含 SKILL.md 的 GitHub 仓库、owner/repo，或 zip 直链；装完即时生效。",
                    color = p.t2,
                    fontSize = 12.5.sp,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = p.red, fontSize = 12.5.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && input.isNotBlank(), onClick = onConfirm) {
                if (busy) {
                    CircularProgressIndicator(
                        color = p.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text("安装", color = p.accent, fontSize = 14.sp)
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text("取消", color = p.t2, fontSize = 14.sp)
            }
        },
    )
}

/**
 * 「移除技能」确认框：标题固定「移除技能」，正文带技能名；内置技能的正文由
 * [removeConfirmBody] 补一句「下次启动会重新铺回」。确认后按钮转圈，转圈期间取消也点不动，
 * 免得删到一半改主意。
 */
@Composable
private fun RemoveSkillDialog(
    name: String,
    bundled: Boolean,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalQq.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移除技能", color = p.t1, fontWeight = FontWeight.SemiBold) },
        text = { Text(removeConfirmBody(name, bundled), color = p.t2, fontSize = 13.sp) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onConfirm) {
                if (busy) {
                    CircularProgressIndicator(
                        color = p.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text("移除", color = p.red, fontSize = 14.sp)
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text("取消", color = p.t2, fontSize = 14.sp)
            }
        },
    )
}

/** 一屏状态：引擎还没起来 / 起不来 / 起来了但没内置 / 有列表。 */
internal sealed interface SkillUiState {
    data object Loading : SkillUiState
    data object NoEngine : SkillUiState
    data class Failed(val stage: String, val message: String) : SkillUiState
    data class Ready(val entries: List<SkillEntry>) : SkillUiState
}

/**
 * 冷启动时序：宿主启动时 AppContainer 的 NodeBackedPlugin 会用**不带插件**的参数先把引擎拉起来
 * （NodeBackedPlugin.kt:129 `engine.ensureStarted()`），技能插件本身还在异步 `plugins.load`。
 * 这时页面的 [NodeEngine.ensureStarted] 只是幂等返回，直接 RPC 会撞上「未注册 RPC 路径」。
 * 所以这里撞到该错误就把技能插件目录显式 load 一次、再等路径注册 —— 而不是把「还没加载完」
 * 当成「引擎坏了」报给用户。ponytail: 固定 8×250ms 上限，够冷启动那一两秒；真要更慢就调大常量。
 */
private const val SKILL_READY_ATTEMPTS = 8
private const val SKILL_READY_DELAY_MS = 250L

/**
 * 预装技能放在客户端复制：assets 只有 APK 里能读，引擎拿不到；走引擎就得给
 * bin.js / NodeEngineHost 再加一个 bundledDir 入参，超出这次改动的归属范围。
 * 技能库根与引擎一致 —— NodeEngineHost.kt:53 的 homeDir = filesDir/runtime/home。
 */
private const val BUNDLED_SKILLS_ASSET = "bundled-skills"

/** 随包内置技能名（assets/bundled-skills/ 下的目录名）；拿不到资产就是空集，判定退化为「非内置」。 */
internal fun bundledSkillNames(assets: AssetManager): Set<String> =
    bundledSkillSet(runCatching { assets.list(BUNDLED_SKILLS_ASSET) }.getOrNull())

/**
 * 铺资产的标记，故意不和 skill-manager.js:133 的 .builtin-bootstrapped 同名。
 *
 * 引擎在 App 启动时就先跑了自己的首启逻辑，同一个标记会被它抢先写掉，
 * 客户端再检查就永远短路，资产一个都铺不出去。两个标记各管各的，互不阻塞。
 */
private const val SEED_MARKER = ".bundled-skills-seeded"

internal fun skillsLibraryRoot(filesDir: File): File = File(filesDir, "runtime/home/skill-manager")

/**
 * 首启把 assets/bundled-skills/<name>/ 复制进技能库，然后落 .bundled-skills-seeded。
 *
 * 标记写在最后：中途失败下次启动还能重来，不会留下「标记在、技能缺」的死状态。
 * 已有同名技能的目录跳过，不覆盖用户自己装的版本。返回本次落盘的技能名。
 */
internal fun bootstrapBundledSkills(assets: AssetManager, root: File): List<String> {
    if (File(root, SEED_MARKER).isFile) return emptyList()
    val library = File(root, ".agents/skills").apply { mkdirs() }
    val installed = mutableListOf<String>()
    for (name in assets.list(BUNDLED_SKILLS_ASSET).orEmpty().sorted()) {
        val target = File(library, name)
        if (File(target, "SKILL.md").isFile) continue
        copyAssetTree(assets, "$BUNDLED_SKILLS_ASSET/$name", target)
        if (File(target, "SKILL.md").isFile) installed += name
    }
    root.mkdirs()
    File(root, SEED_MARKER).writeText(System.currentTimeMillis().toString() + "\n")
    return installed
}

/** assets 没有「目录条目」概念：list 出来的子项既可能是文件也可能是目录，只能靠 list 结果判断。 */
private fun copyAssetTree(assets: AssetManager, from: String, to: File) {
    val children = assets.list(from).orEmpty()
    if (children.isEmpty()) {
        to.parentFile?.mkdirs()
        // open 失败说明它是个空目录（aapt 一般不打包空目录），退化成建目录即可
        runCatching { assets.open(from).use { input -> to.outputStream().use { input.copyTo(it) } } }
            .onFailure { to.mkdirs() }
        return
    }
    to.mkdirs()
    for (child in children) copyAssetTree(assets, "$from/$child", File(to, child))
}

/**
 * 在插件根目录里认出技能插件（package.json 的 name 含 skill）。
 *
 * 只看磁盘、不看注册表：注册表（[InstalledPlugins]）只在用户从界面装插件时才写，
 * 随包投放 / 手动 push 进 filesDir/plugins 的技能插件在它那里查不到。
 */
internal fun discoverSkillPluginDirs(root: File): List<File> =
    root.listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "package.json").isFile }
        .filter { dir ->
            val name = runCatching {
                JSONObject(File(dir, "package.json").readText()).optString("name")
            }.getOrDefault("")
            name.contains("skill", ignoreCase = true)
        }
        .sortedBy { it.name }

/**
 * 技能插件目录 = 宿主显式注册的目录 ∪ 磁盘发现到的技能插件目录，路径去重、注册项在前。
 *
 * 注册项原样保留（boot 等普通插件的加载路径与改动前完全一致），磁盘侧只补技能插件；
 * 同一个目录既注册过又扫到只留一份，避免重复 plugins.load。
 */
internal fun skillPluginDirs(registered: List<File>, diskRoot: File): List<File> {
    val seen = HashSet<String>()
    return (registered + discoverSkillPluginDirs(diskRoot)).filter { dir ->
        val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        seen.add(key)
    }
}

/** 引擎还没把插件注册上来时的固定错误（bin.js:130 / cordis-lite.js:98，stage=rpc）。 */
private fun rpcPathMissing(e: NodeEngineException): Boolean =
    e.stage == "rpc" && (e.message ?: "").contains("未注册 RPC 路径")

internal suspend fun loadSkills(engine: NodeEngine?, pluginDirs: List<File>): SkillUiState {
    if (engine == null) return SkillUiState.NoEngine
    val started = engine.ensureStarted(pluginDirs)
    val startError = started.exceptionOrNull()
    if (startError != null) {
        return SkillUiState.Failed(
            stage = (startError as? NodeEngineException)?.stage ?: "start",
            message = startError.message ?: startError.toString(),
        )
    }
    var lastMissing: NodeEngineException? = null
    repeat(SKILL_READY_ATTEMPTS) { attempt ->
        val outcome = runCatching { engine.rpc("/skill-manager", "list", JSONObject()) }
        val failure = outcome.exceptionOrNull()
        outcome.getOrNull()?.let { reply -> return catalogState(reply) }
        if (failure !is NodeEngineException || !rpcPathMissing(failure)) {
            return SkillUiState.Failed(
                stage = (failure as? NodeEngineException)?.stage ?: "rpc",
                message = failure?.message ?: failure?.toString() ?: "技能列表读取失败",
            )
        }
        lastMissing = failure
        if (attempt == 0) {
            pluginDirs.forEach { dir ->
                runCatching { engine.call("plugins.load", JSONObject().put("dir", dir.absolutePath)) }
            }
        }
        delay(SKILL_READY_DELAY_MS)
    }
    return SkillUiState.Failed(
        stage = lastMissing?.stage ?: "rpc",
        message = lastMissing?.message ?: "技能列表读取失败",
    )
}

private fun catalogState(reply: JSONObject): SkillUiState {
    if (!reply.optBoolean("ok", true)) {
        val error = reply.optJSONObject("error")
        return SkillUiState.Failed(
            stage = error?.optString("stage").orEmpty().ifBlank { "rpc" },
            message = error?.optString("message").orEmpty().ifBlank { "技能列表读取失败" },
        )
    }
    return SkillUiState.Ready(parseCatalog(catalogOf(reply)))
}

@Composable
private fun LoadingBody() {
    val p = LocalQq.current
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = p.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(14.dp))
        Text("正在启动 Node 引擎…", color = p.t2, fontSize = 13.sp)
    }
}

@Composable
private fun FailureBody(stage: String, message: String, onRetry: () -> Unit) {
    val p = LocalQq.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionCard {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text("技能列表读取失败", color = p.red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("stage: $stage", color = p.t3, fontSize = 11.5.sp)
                Spacer(Modifier.height(2.dp))
                Text(message, color = p.t2, fontSize = 12.5.sp)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                    Text("重试", color = p.accent, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SkillRow(
    entry: SkillEntry,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalQq.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = p.t1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (entry.description.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    entry.description,
                    color = p.t2,
                    fontSize = 12.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        // 删除入口与开关并排：busy 时换成同尺寸转圈，行宽不跳。
        if (busy) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = p.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            IconBtn(Icons.Filled.Delete, "移除 ${entry.name}") { onDelete() }
        }
        Spacer(Modifier.width(6.dp))
        Switch(checked = entry.effectiveEnabled, onCheckedChange = onToggle, enabled = !busy)
    }
}
