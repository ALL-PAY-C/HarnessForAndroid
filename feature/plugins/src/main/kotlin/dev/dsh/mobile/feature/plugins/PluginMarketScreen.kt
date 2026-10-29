package dev.dsh.mobile.feature.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import android.provider.OpenableColumns
import dev.dsh.mobile.core.common.TimeFmt
import dev.dsh.mobile.core.data.backup.BackupManager
import dev.dsh.mobile.pluginhost.ExternalPluginStore
import dev.dsh.mobile.pluginhost.FileMarketCache
import dev.dsh.mobile.pluginhost.MarketEntry
import dev.dsh.mobile.pluginhost.MarketLoad
import dev.dsh.mobile.pluginhost.MarketLoader
import dev.dsh.mobile.pluginhost.MarketSnapshot
import dev.dsh.mobile.pluginhost.PluginInstaller
import dev.dsh.mobile.pluginhost.PluginMarket
import dev.dsh.mobile.design.CollapsibleSection
import dev.dsh.mobile.design.SectionChevron
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Restore
import dev.dsh.mobile.design.LocalQq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 从文件安装时占住 [busy] 的哨兵值（和条目 id 不会撞）。 */
private const val PICK_BUSY = "file-pick"

/** GitHub 拉取占住 [busy] 的哨兵值。 */
private const val GITHUB_BUSY = "github-pull"

/**
 * Search + install, plus the backup shelf that makes installing reversible.
 *
 * Kept out of [PluginsScreen] because it talks to the installer and the backup
 * manager — both optional here, so the screen still renders in a preview or in
 * a build where those were never wired.
 *
 * 版式：整页一张连续列表（顶部标题行 + 同一块浅底区域里依次放安装入口、检索、排序、条目），
 * 条目之间只用 1dp 分隔线，不再一张卡一条描边 —— 分离式卡片看着碎。
 */
@Composable
fun PluginMarketScreen(
    installer: PluginInstaller?,
    backup: BackupManager?,
    installedIds: List<String>,
    onChanged: () -> Unit = {},
    /** 外部插件仓库：统一安装入口，zip 包与旧 .apk 都由它按内容分流。 */
    externalStore: ExternalPluginStore? = null,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var snapshots by remember { mutableStateOf<List<BackupManager.Entry>>(emptyList()) }
    val context = LocalContext.current
    val p = LocalQq.current
    // 「从文件安装」：插件包正常是 zip（里面放 package.json + 入口，或 plugin.json）；
    // 裸 plugin.json 就地包成单文件 zip 走同一条链路。旧 .apk 插件包仍收 —— 判据是内容不是后缀，
    // 分流交给 ExternalPluginStore.installPackage（SAF 常把文件名吃掉，后缀本来就不可信）。
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = PICK_BUSY
            scope.launch {
                val result = runCatching {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("读不到所选文件")
                    }
                    require(bytes.isNotEmpty()) { "文件是空的" }
                    val pick = File(context.cacheDir, "plugin-pick-" + System.currentTimeMillis() + ".zip")
                    withContext(Dispatchers.IO) {
                        // zip / npm tarball（gzip）原样落盘；裸清单就地包成单文件 zip。
                        if (looksLikeArchive(bytes)) {
                            pick.writeBytes(bytes)
                        } else {
                            ZipOutputStream(pick.outputStream()).use { out ->
                                out.putNextEntry(ZipEntry("plugin.json"))
                                out.write(bytes)
                                out.closeEntry()
                            }
                        }
                    }
                    val store = externalStore
                    if (store != null) {
                        val entry = store.installPackage(pick).getOrElse { pick.delete(); throw it }
                        val kind = if (entry.dirPath.isNotBlank()) "数据插件" else "APK 插件"
                        entry.name + " " + entry.version + "（" + kind + "）"
                    } else {
                        val target = installer ?: error("安装器未就绪")
                        val manifest = target.installArchive(pick).getOrElse { pick.delete(); throw it }
                        manifest.name + " " + manifest.version + "（数据插件）"
                    }
                }
                busy = null
                notice = result.fold(
                    onSuccess = { "已安装 $it，已热加载" },
                    onFailure = { e -> "安装失败：" + (e.message ?: e::class.java.simpleName) },
                )
                if (result.isSuccess) {
                    snapshots = backup?.list().orEmpty()
                    onChanged()
                }
            }
        }
    }
    var load by remember { mutableStateOf(MarketLoad()) }
    var loading by remember { mutableStateOf(false) }
    // 各区块默认收起（和 Agent 页一致），展开状态跨旋屏保留。
    var srcOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var bodyOpen by rememberSaveable { mutableStateOf(true) }
    var sort by remember { mutableStateOf(MarketSort.Downloads) }
    var ascending by remember { mutableStateOf(false) }
    // 备份与回档段默认收起，用户展开后保留状态。
    var backupOpen by rememberSaveable { mutableStateOf(false) }
    var gitHubOpen by remember { mutableStateOf(false) }
    var repoInput by remember { mutableStateOf("") }

    // 内置目录 = APK assets 里的 registry.json + 代码内置条目；加载器负责「缓存优先 →
    // 并发刷新 → 全挂回落内置」。缓存落在 files/market-cache.json（上限 1 MB）。
    val loader = remember(context) {
        MarketLoader(
            bundled = {
                PluginMarket.merge(
                    runCatching { PluginMarket.loadBundled(context) }.getOrDefault(emptyList()) +
                        PluginMarket.bundled,
                )
            },
            cache = FileMarketCache(File(context.filesDir, "market-cache.json")),
        )
    }

    // 多源并发拉取；任一成功即用，全挂只体现在 sources 与顶部提示里，不阻塞列表。
    val refresh: () -> Unit = {
        scope.launch {
            loading = true
            load = loader.refresh { partial -> load = partial }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        snapshots = backup?.list().orEmpty()
        // 缓存先上屏（不碰网络），再后台刷新：断网启动时看到的是上次的列表。
        load = loader.initial()
        refresh()
    }

    // 预置离线插件：首启把 APK 里 assets/plugins 的示例包装进插件目录（不联网、幂等）。
    // 装成即热加载并刷新列表；失败只落顶部提示，不挡列表。
    LaunchedEffect(installer) {
        val target = installer ?: return@LaunchedEffect
        val report = withContext(Dispatchers.IO) {
            runCatching { target.installBundled() }.getOrNull()
        } ?: return@LaunchedEffect
        if (report.changed) {
            snapshots = backup?.list().orEmpty()
            onChanged()
        }
        if (report.installed.isNotEmpty() || report.failed.isNotEmpty()) notice = report.summary()
    }

    val snapshot = load.snapshot

    val catalogue = sortEntries(
        PluginMarket.search(
            snapshot.plugins.filterNot { it.id in installedIds },
            query,
        ),
        sort,
        ascending,
    )

    Column(Modifier.fillMaxSize()) {
        // 主卡：草稿里整块内容都在一个描边圆角盒里 —— 搜索、排序、列表、两个安装入口都装在盒内。
        Column(
            // bodyOpen 关掉时不再占满高度，卡片自己缩成一行，否则收起来只留一大片空白。
            Modifier
                .then(if (bodyOpen) Modifier.weight(1f) else Modifier)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(p.card)
                .border(1.dp, p.line, RoundedCornerShape(20.dp)),
        ) {
        // 红框第一行：左标题 + 搜索框 + 右端箭头（箭头管整块收起）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp),
        ) {
            Text("插件市场", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索插件名 / 描述 / npm 包") },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { bodyOpen = !bodyOpen }) {
                Icon(
                    if (bodyOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (bodyOpen) "收起" else "展开",
                )
            }
        }
        if (bodyOpen) {
            Text(
                when {
                    snapshot.plugins.isEmpty() && loading -> "拉取中…"
                    loading -> countsText(snapshot) + " · 补源中…"
                    else -> countsText(snapshot)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp),
            )
        // 排序一行：左「字段」，右「升降序」，和草稿一致。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var sortMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { sortMenu = true }) {
                    Text("排序：" + sort.label, fontWeight = FontWeight.SemiBold)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    MarketSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = { sort = option; sortMenu = false },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { ascending = !ascending }) {
                Text(if (ascending) "升序 ↑" else "降序 ↓")
            }
        }
        LazyColumn(Modifier.weight(1f)) {
        // 刷新失败只在这里说一句，不弹对话框、不挡列表：列表照常可滚可装，
        // 右侧「重试」再拉一次。有缓存时说明当前是缓存，没缓存时说明已回落内置。
        load.notice()?.let { line ->
            item(key = "market-notice") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                ) {
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { refresh() }, enabled = !loading) { Text("重试") }
                }
                Hairline(Modifier.padding(horizontal = 16.dp))
            }
        }
        if (gitHubOpen) {
            item(key = "github-import") {
                AlertDialog(
                    onDismissRequest = { if (busy == null) gitHubOpen = false },
                    title = { Text("从 GitHub 导入插件") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = repoInput,
                                onValueChange = { repoInput = it },
                                singleLine = true,
                                label = { Text("仓库链接") },
                                placeholder = { Text("https://github.com/owner/repo") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "仓库里有 plugin.json、package.json 或现成的 .zip 插件包即可（旧的 .apk 插件包也收）；先试 main，再试 master。",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = busy == null && repoInput.isNotBlank(),
                            onClick = {
                                val input = repoInput.trim()
                                gitHubOpen = false
                                busy = GITHUB_BUSY
                                notice = null
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            GitHubPlugins.install(installer, externalStore, context.cacheDir, input)
                                        }
                                    }
                                    busy = null
                                    notice = result.fold(
                                        onSuccess = { "已安装 $it，已热加载" },
                                        onFailure = { "导入失败：${it.message}" },
                                    )
                                    if (result.isSuccess) {
                                        snapshots = backup?.list().orEmpty()
                                        onChanged()
                                    }
                                }
                            },
                        ) { Text("拉取安装") }
                    },
                    dismissButton = {
                        TextButton(onClick = { gitHubOpen = false }) { Text("取消") }
                    },
                )
            }
        }
        // 安装结果/错误常驻在入口下面：成功与失败都必须看得见，失败时把安装器给的完整原因照抄出来。
        notice?.let { text ->
            item {
                Column(Modifier.fillMaxWidth().background(p.card)) {
                    Hairline(Modifier.padding(horizontal = 16.dp))
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (text.contains("失败")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
        itemsIndexed(catalogue, key = { _, entry -> entry.id }) { _, entry ->
            Column(Modifier.fillMaxWidth()) {
                MarketRow(
                    entry = entry,
                    busy = busy == entry.id,
                    expanded = expandedId == entry.id,
                    onToggle = { expandedId = if (expandedId == entry.id) null else entry.id },
                    onInstall = {
                        busy = entry.id
                        scope.launch {
                            // 三条路，按可用性依次退：仓库直链 → GitHub 仓库（下载 zip 自己安装）
                            // → 都没有才提示「无下载地址」。不再默认甩一条命令行给用户。
                            val target = installer
                            // 直链（.zip / .tar.gz）先走安装器；GitHub 网页链接才去拉仓库。
                            // 以前不看链接形态一律先 installUrl，条目填的是 github.com/o/r 时必然 404，
                            // 再退到 GitHub 分支又报「链接看不懂」。
                            val url = entry.packageUrl
                            val directArchive = url.isNotBlank() && (
                                url.substringBefore('?').endsWith(".zip", true) ||
                                    url.endsWith(".tar.gz", true) ||
                                    url.endsWith(".tgz", true)
                                )
                            // 索引里的 repo 字段形态很杂（带 /tree/分支/子目录、带查询串、只有 owner/repo/xxx），
                            // 用 guess 归一成 owner/repo；拿不到才算「没仓库」。
                            val repoLink = listOf(entry.repo, entry.homepage, entry.packageUrl)
                                .firstNotNullOfOrNull { raw ->
                                    GitHubPlugins.guess(raw)?.let { it.owner + "/" + it.name }
                                }
                            // 逐条试，失败就退下一条：npm → 直链归档 → GitHub 仓库 → 直链兜底。
                            val steps = buildList<Pair<String, suspend () -> Result<String>>> {
                                if (entry.npm.isNotBlank() && target != null) {
                                    add("npm" to { target.installNpm(entry.npm).map { it.name + "（npm 包）" } })
                                }
                                if (directArchive && target != null) {
                                    add("直链" to { target.installUrl(url).map { it.name + "（数据插件）" } })
                                }
                                if (repoLink != null) {
                                    add("GitHub" to {
                                        runCatching {
                                            GitHubPlugins.install(installer, externalStore, context.cacheDir, repoLink)
                                        }
                                    })
                                }
                                if (url.isNotBlank() && target != null && !directArchive) {
                                    add("直链" to { target.installUrl(url).map { it.name + "（数据插件）" } })
                                }
                            }
                            val failures = ArrayList<String>()
                            var result: Result<String> = Result.failure(
                                IllegalStateException(
                                    buildString {
                                        append("这个条目没有登记任何可安装的来源")
                                        if (entry.installHint.isNotBlank()) {
                                            append("；作者给的安装命令是 ").append(entry.installHint)
                                        } else {
                                            append("；点「GitHub 详情」去项目主页看看它怎么装")
                                        }
                                    },
                                ),
                            )
                            for ((tag, step) in steps) {
                                val attempt = runCatching { step() }.getOrElse { Result.failure(it) }
                                if (attempt.isSuccess) {
                                    result = attempt
                                    break
                                }
                                failures += tag + "：" + (attempt.exceptionOrNull()?.message ?: "失败")
                            }
                            if (result.isFailure && failures.isNotEmpty()) {
                                result = Result.failure(IllegalStateException(failures.joinToString("\n")))
                            }
                            busy = null
                            notice = result.fold(
                                onSuccess = { "已安装 $it，已热加载" },
                                onFailure = { e -> "安装失败：" + (e.message ?: e::class.java.simpleName) },
                            )
                            if (result.isSuccess) {
                                snapshots = backup?.list().orEmpty()
                                onChanged()
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        item {
            CollapsibleSection(
                title = "备份与回档",
                icon = Icons.Filled.Restore,
                tint = MaterialTheme.colorScheme.primary,
                expanded = backupOpen,
                onToggle = { backupOpen = !backupOpen },
                count = snapshots.size,
                subtitle = "装插件前自动备份，可回档",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        "装插件前自动备份一次；连续安装只备份一次，回档就回到这批插件都还没装的那一刻。" +
                            "定时备份默认 05:00，没赶上会在下次启动时补做。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val manager = backup ?: return@TextButton
                            scope.launch {
                                val file = withContext(Dispatchers.IO) { manager.snapshot("manual") }
                                snapshots = manager.list()
                                notice = if (file != null) "已备份 ${file.name}" else "备份失败"
                            }
                        }) { Text("立即备份") }
                        TextButton(onClick = {
                            backup?.closeBatch()
                            notice = "已结束本批安装，下次安装会重新备份"
                        }) { Text("结束本批") }
                    }
                }
                BackupScheduleCard(backup) { snapshots = backup?.list().orEmpty() }
                snapshots.take(8).forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(TimeFmt.absolute(entry.createdAt), style = MaterialTheme.typography.bodySmall)
                            Text(
                                entry.reason + " · " + entry.file.length() / 1024 + " KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            val manager = backup ?: return@TextButton
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { manager.restore(entry.file).isSuccess }
                                notice = if (ok) "已回档，重启 App 生效" else "回档失败"
                            }
                        }) { Text("回档") }
                        TextButton(onClick = {
                            val manager = backup ?: return@TextButton
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { manager.delete(entry.file) }
                                snapshots = manager.list()
                                notice = if (ok) "已删除这个存档" else "删除失败"
                            }
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        // 源与备份都是折叠卡，和 Agent 页同一套 CollapsibleSection；默认收起，展开状态跨旋屏保留。
        item {
            CollapsibleSection(
                title = "插件源",
                icon = Icons.Filled.Cloud,
                tint = MaterialTheme.colorScheme.primary,
                expanded = srcOpen,
                onToggle = { srcOpen = !srcOpen },
                count = snapshot.sources.size,
                subtitle = countsText(snapshot),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    snapshot.sources.forEach { status ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (status.ok) "●" else "○",
                                color = if (status.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                buildString {
                                    append(status.name)
                                    append(if (status.ok) " · " + status.count + " 条" else " · 失败")
                                    status.error?.let { append("（" + it.take(60) + "）") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (snapshot.sources.isEmpty()) {
                        Text(
                            if (load.fromCache) "还没刷新，当前展示上次成功索引的缓存。"
                            else "离线或全部源不可达，当前展示内置目录。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (loading) "正在补源…" else "共 " + snapshot.plugins.size + " 个可装插件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { refresh() }, enabled = !loading) { Text("重新拉取") }
                    }
                }
            }
        }
        }
        }
        }
        // 两个安装入口在主卡下面，常驻页面底部，不随列表滚走。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MarketActionButton(
                label = if (busy == PICK_BUSY) "安装中…" else "文件安装",
                enabled = (installer != null || externalStore != null) && busy == null,
                modifier = Modifier.weight(1f),
            ) { filePicker.launch(arrayOf("*/*")) }
            MarketActionButton(
                label = if (busy == GITHUB_BUSY) "拉取中…" else "链接安装",
                enabled = busy == null,
                modifier = Modifier.weight(1f),
            ) { gitHubOpen = true }
        }
    }
}

/** 「N 个源 · M 个插件」——标题行右边那行小字。 */
private fun countsText(snapshot: MarketSnapshot): String =
    snapshot.sources.size.toString() + " 个源 · " + snapshot.plugins.size + " 个插件"

/** 条目之间的 1dp 分隔线：整页连续列表靠它分段，不再靠一张张卡片。 */
@Composable
private fun Hairline(modifier: Modifier = Modifier) {
    val p = LocalQq.current
    Box(modifier.fillMaxWidth().height(1.dp).background(p.line))
}

@Composable
private fun MarketActionButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalQq.current
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(p.card)
            .border(1.dp, p.line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) p.t1 else p.t3, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 一条插件：名称 + 「版本 · 来源」小字 + 描述 + 底部左「N 次下载」右「GitHub 详情」「下载安装」。
 * 自身不带描边/圆角/阴影 —— 容器由外层那一块浅底区域统一给，条目之间只有 1dp 分隔线。
 */
@Composable
private fun MarketRow(
    entry: MarketEntry,
    busy: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // 让用户一眼看出这条会走哪条安装链路：npm 包能直接装，只登记了仓库的要去项目主页。
    val via = when {
        entry.npm.isNotBlank() -> "npm:" + entry.npm
        entry.packageUrl.isNotBlank() -> "直链归档"
        entry.repo.isNotBlank() -> "GitHub 仓库"
        else -> "仅命令"
    }
    Column(modifier.fillMaxWidth()) {
        // 收起时只有一行：名字 + 「版本 · 来源 · 简介」，右端箭头点开看详情。
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append(listOf(entry.version, via).filter { it.isNotBlank() }.joinToString(" · "))
                        if (entry.description.isNotBlank()) {
                            if (isNotEmpty()) append("　")
                            append(entry.description)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            SectionChevron(expanded = expanded)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                if (entry.description.isNotBlank()) {
                    Text(entry.description, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val stats = buildList {
                        if (entry.downloads > 0) add(entry.downloads.toString() + " 次下载")
                        if (entry.stars > 0) add("★ " + entry.stars)
                    }.joinToString(" · ")
                    if (stats.isNotBlank()) {
                        Text(stats, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    val detail = entry.repo.ifBlank { entry.homepage }
                    if (detail.isNotBlank()) {
                        TextButton(onClick = { openUrl(context, detail) }) { Text("GitHub 详情") }
                    }
                    if (entry.npm.isNotBlank() || entry.packageUrl.isNotBlank() || entry.repo.isNotBlank()) {
                        MarketActionButton(
                            label = if (busy) "下载中…" else "安装插件",
                            enabled = !busy,
                            modifier = Modifier.width(112.dp),
                            onClick = onInstall,
                        )
                    } else {
                        // 真正没有任何下载地址的条目（多为 DSH 主机侧插件）才退化成命令。
                        TextButton(onClick = { clipboard.setText(AnnotatedString(entry.installHint)) }) {
                            Text("复制命令")
                        }
                    }
                }
            }
        }
        Hairline(Modifier.padding(horizontal = 12.dp))
    }
}


private enum class MarketSort(val label: String) {
    Downloads("下载量"),
    Stars("星标"),
    Name("名称"),
}

private fun sortEntries(list: List<MarketEntry>, sort: MarketSort, ascending: Boolean): List<MarketEntry> {
    val sorted = when (sort) {
        MarketSort.Downloads -> list.sortedBy { it.downloads }
        MarketSort.Stars -> list.sortedBy { it.stars }
        MarketSort.Name -> list.sortedBy { it.name.lowercase() }
    }
    return if (ascending) sorted else sorted.reversed()
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 是 zip 还是 gzip（npm tarball）—— 只作落盘提示，真判型在安装器里按内容做。 */
private fun looksLikeArchive(bytes: ByteArray): Boolean {
    if (bytes.size < 2) return false
    val zip = bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    val gz = bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    return zip || gz
}
