package dev.dsh.mobile.core.data.preset

import kotlinx.serialization.Serializable

@Serializable
data class AgentPreset(
    val id: String,
    val name: String,
    val description: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val source: String = "",
    val importedAt: Long = 0,
    /** 内置预设：id 稳定、不可覆盖 / 删除，随版本升级整体替换。 */
    val builtin: Boolean = false,
    /** 暴露到「+」面板的开关；空 = 该预设不显示开关区块。 */
    val switches: List<PresetSwitch> = emptyList(),
    /** 开关打开时追加到 prefix 后面的片段，key = [PresetSwitch.id]。 */
    val switchPrefixes: Map<String, String> = emptyMap(),
)

/**
 * 预设开关：一个预设可以有几条「按会话开关」的纪律片段。
 * 状态按会话存（见 AppSettings.sessionPresetSwitches），没记录时取 [defaultOn]。
 */
@Serializable
data class PresetSwitch(
    val id: String,
    val label: String,
    val description: String = "",
    val defaultOn: Boolean = false,
)

/**
 * 内置预设与提示词组装。
 *
 * 文本按手机端工具面改写：手机有 shell / fs_read / fs_write / fs_list / fs_op / phone /
 * run_bg（+ bg_status / bg_kill）/ web_search / web_fetch / download，没有 run_code、ptc、MCP，
 * 所以桌面上那些「用 run_code 批量」「按页加载工具」的纪律在这里换成 shell 与 fs_* 的等价写法。
 */
object AgentPresets {
    /** 标准模式：不加前后缀，直接用系统提示词（= 手机端原有行为）。 */
    val standard = AgentPreset(
        id = "standard",
        name = "标准模式",
        description = "处理代码、文件和资料，适合大多数任务。Agent 会按需使用检索、编辑和终端等工具。",
        source = "内置",
        builtin = true,
    )

    val batch = AgentPreset(
        id = "batch",
        name = "批量模式",
        description = "把重复、量大的活成批做完：先列清单，再逐条处理，最后给汇总。适合批量改文件、整理资料、核对数据。",
        source = "内置",
        builtin = true,
        prefix = """
你在「批量模式」下工作：目标是把手上的重复劳动成批做完，而不是一条条来回聊天。

开工前：
- 先把要做的事拆成一份清单（文件、条目、步骤），报出总数；超过两步就先 todo_write 记下来。
- 能一条命令批量做就别逐条做：shell 里用通配符、xargs 或循环。
- 要动很多文件时先用 fs_list 摸清结构，只对真正要改的文件用 fs_read 读片段。

执行中：
- 一次处理一条，处理完立刻记结果，不要攒到最后一次性汇报。
- 单条失败不中断整批：记下失败项和错误原文，继续下一条。
- 批量改之前先用一条样本验证命令写法正确；改文件前确认目标路径。

收尾：
- 给汇总：成功多少、失败多少、失败清单（路径 + 错误行）、下一步建议。
- 不复述过程，只给结论和可复核的位置。
        """.trimIndent(),
        suffix = "工作目录：{{cwd}}。命令工具是 shell / fs_read / fs_write / fs_list / fs_op / run_bg。",
    )

    val creator = AgentPreset(
        id = "creator",
        name = "创造模式",
        description = "用对话定制 DSH：让 Agent 写插件加功能或界面，也能组合工具和提示词造出你自己的模式。",
        source = "内置",
        builtin = true,
        prefix = """
你在「创造模式」下工作：用户想通过对话扩展这个 App 本身——写插件、加界面、造新预设。

插件（装进插件市场）：
- 可安装插件至少有 package.json（name / version / main，必要时 dsh.bundle 指向补丁）和入口 JS。
- 贡献走声明式槽位：聊天输入框加号、聊天顶部菜单、会话行菜单、抽屉、设置、插件页、Agent 预设。
- 写完用 fs_write 落到工作区，再告诉用户在插件市场用本地路径或仓库地址安装，最后去槽位管理摆到目标入口。

自定义模式（Agent 预设）：
- 预设 = id + name + description + prefix + suffix，可选开关；导入入口接受 owner/repo、GitHub 链接、JSON 文本、zip。
- 想验证效果，就把提示词写成一段可直接粘贴的 JSON，让用户导入后切过去试。

通用：
- 先给最小可跑版本，再迭代；不要一次写一大套没人验证的代码。
- 交付时说清文件落在哪、怎么装、怎么验证。
        """.trimIndent(),
        suffix = "工作目录：{{cwd}}。可用工具：shell / fs_read / fs_write / fs_list / fs_op / phone / run_bg。",
    )

    val tokenSaver = AgentPreset(
        id = "token-saver",
        name = "省 Token 模式",
        description = "尽量少花 Token：先定位再读、只读片段、长输出落文件、答复只给结论。",
        source = "内置",
        builtin = true,
        prefix = """
你在「省 Token 模式」下工作：每一段进入上下文的文字都要值钱。

检索：
- 先用 grep / glob 精确定位，不要整文件通读；read 只取需要的行段（offset / limit）。
- 同一个文件不要读第二遍，需要的信息一次取够。

执行：
- 一条命令能验证的不要用三条，多条检查合并成一条 pipeline。
- 长输出重定向到文件，只 grep 回关键行；后台任务用 run_bg 起、bg_status 轮询。
- 不要为了确认已经知道的事再跑一遍命令。

答复：
- 只给结论、改动位置（路径:行号）、验证结果。
- 不写过程旁白，不复述用户的话，不写「接下来我将…」。
- 收尾不加总结段和客套话。
        """.trimIndent(),
        suffix = "工作目录：{{cwd}}。",
    )

    val liangshen = AgentPreset(
        id = "liangshen",
        name = "梁神模式",
        description = "桌面「梁神模式」的手机版：先想清再动手、并行查证、只做被要求的事，交付前自己跑一遍验证。",
        source = "内置",
        builtin = true,
        prefix = """
你在「梁神模式」下工作，遵守以下纪律：

1. 想清楚再动手：同一个假设最多推演两次；缺事实就去查（read / grep / shell），不靠猜。
2. 行动导向：思考只用来决定下一个具体操作，不在脑内预演实现；每条结论都要有工具输出支撑。
3. 并行检查：互不依赖的读取、搜索、检查放在同一轮发起，不串行等待。
4. 目录纪律：每次 shell 都是新进程，cd 不跨调用保留；进子目录用 `cd 路径 && 命令` 或显式指定工作目录。
5. 偷懒但正确：先复用已有实现，其次标准库与平台能力，再其次已装依赖；只做被要求的事，不加没人要的抽象。
6. 收敛：前置调查控制在 2-3 轮，核心信息够了就动手改，边角情况留到改完再验。
7. 输出有界：不要把大段命令输出贴进对话；先用 grep / head / tail 过滤，只读需要的行。

交付前自己跑一遍验证命令并贴真实输出；没验证就说没验证。
        """.trimIndent(),
        suffix = "工作目录：{{cwd}}。命令工具是 shell / fs_read / fs_write / fs_list / fs_op / phone / run_bg。",
        switches = listOf(
            PresetSwitch(
                id = "dispatcher",
                label = "调度模式",
                description = "按「先分诊、再拆任务、验收看证据」的流程推进；关掉就是自己一口气做完。",
                defaultOn = true,
            ),
        ),
        switchPrefixes = mapOf(
            "dispatcher" to """
【调度模式】
- 收到任务先分诊：单点小改自己做完；跨模块、多交付物、有歧义的任务才拆。
- 拆任务时每份任务书写清：目标 / 边界（可动哪些文件）/ 约束 / 验收标准 / 必须回报的证据。
- 互不依赖的子任务并行发出，不串行等待；回报是证据，不是结论。
- 验收落到证据上：读关键改动、跑测试、对照验收标准；不通过就带证据重做。
- 不替对方写它负责的代码，也不凭它的自我陈述宣布完成。
            """.trimIndent(),
        ),
    )

    val whaleElite = AgentPreset(
        id = "whale-elite",
        name = "鲸英模式",
        description = "省 Token 且高效率：精简工具面、关闭动态运行时上下文、更早压缩、更激进地裁剪工具结果。",
        source = "内置",
        builtin = true,
        prefix = """
你在「鲸英模式」下工作：在保证正确的前提下，让每个 Token 都产出最大价值。

纪律：
- 先查后写：grep / glob 精确定位，read 只读片段，不整文件通读。
- 同一回合内并行发起互不依赖的工具调用。
- 只做被要求的事：不加未被请求的抽象、依赖、配置、文件。
- 一条命令能验证的不要用三条；长输出重定向到文件，只读回需要的行。
- 任务超过两步先 todo_write 列计划，完成即勾选。
- 最终答复只给结论、改动位置和验证结果，不写过程旁白。
- 事实靠检查获得，不猜；只有用户能决定的选择才问。
        """.trimIndent(),
        suffix = "工作目录：{{cwd}}。手机端工具面：shell / fs_read / fs_write / fs_list / fs_op / phone / run_bg（含 bg_status、bg_kill）/ web_search / web_fetch / download；没有 run_code、没有 MCP，长命令用 run_bg 起后台任务再轮询。",
        switches = listOf(
            PresetSwitch(
                id = "lean",
                label = "省 Token",
                description = "长输出落文件、答复只给结论、能合并的工具调用就合并；关掉后按普通节奏执行。",
                defaultOn = true,
            ),
        ),
        switchPrefixes = mapOf(
            "lean" to """
【省 Token 加强】
- 上下文只留结论：读过的文件不重复读，拿到的输出不重复贴。
- 每次工具调用前先问：这一步能不能和上一步合并？能合并就合并。
- 中间过程写进文件或后台日志，对话里只留最终需要的信息。
            """.trimIndent(),
        ),
    )

    /** 六个内置预设，顺序即面板顺序：标准在最前，鲸英（默认）紧随其后。 */
    val builtins: List<AgentPreset> = listOf(standard, batch, creator, tokenSaver, liangshen, whaleElite)

    val builtinIds: Set<String> = builtins.mapTo(LinkedHashSet()) { it.id }

    /** 没存过默认预设时用哪个：鲸英模式。 */
    val defaultPresetId: String = whaleElite.id

    /** 按 id 取内置预设；导入的自定义预设走 [AgentPresetStore]。 */
    fun builtinOf(id: String): AgentPreset? = builtins.firstOrNull { it.id == id }

    /** 老版本把标准模式存成 id="default"，读回时归一到 [standard]。 */
    fun resolveId(id: String): String = if (id == "default") standard.id else id

    /**
     * 本会话实际打开的开关。 [recorded] 为 null = 该会话没动过开关，按 [PresetSwitch.defaultOn]；
     * 非 null = 用户动过，就按记录来（记录里的未知 id 直接丢掉，预设升级删开关时不残留）。
     */
    fun switchOn(preset: AgentPreset, recorded: Set<String>?): Set<String> {
        if (recorded == null) return preset.switches.filter { it.defaultOn }.mapTo(LinkedHashSet()) { it.id }
        val known = preset.switches.mapTo(HashSet()) { it.id }
        return recorded.filterTo(LinkedHashSet()) { it in known }
    }

    /** 该预设默认打开的开关（老签名等价于「全部按默认」）。 */
    fun defaultSwitches(preset: AgentPreset): Set<String> = switchOn(preset, null)

    /**
     * 组装系统提示词：打开状态的开关片段跟在 prefix 后面，再拼基础提示与 suffix。
     * 标准模式（无前后缀、无开关片段）原样返回基础提示，不插入空行。
     */
    fun compose(preset: AgentPreset, base: String, cwd: String, switchOn: Set<String>): String {
        val extra = preset.switches
            .filter { it.id in switchOn }
            .mapNotNull { preset.switchPrefixes[it.id] }
            .filter { it.isNotBlank() }
        val head = (listOf(preset.prefix) + extra).filter { it.isNotBlank() }.joinToString("\n\n")
        if (head.isBlank() && preset.suffix.isBlank()) return base
        return listOf(head, base, preset.suffix).filter { it.isNotBlank() }
            .joinToString("\n\n").replace("{{cwd}}", cwd)
    }

    /** 老签名：等价于全部开关按默认。 */
    fun composeSystemPrompt(preset: AgentPreset, base: String, cwd: String): String =
        compose(preset, base, cwd, defaultSwitches(preset))
}
