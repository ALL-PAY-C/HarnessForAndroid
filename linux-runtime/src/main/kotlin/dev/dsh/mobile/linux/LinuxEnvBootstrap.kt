package dev.dsh.mobile.linux

import dev.dsh.mobile.toolbridge.ShellChannel
import dev.dsh.mobile.toolbridge.ShellResult
import java.io.File

/**
 * T6：把「装完 Linux 之后还差什么」补齐——Termux / proot 两套包、python 依赖（全量→轻量回退）、
 * git 镜像、以及一条能自证环境的验收自检。
 *
 * 约定沿用 [LinuxScripts]：纯函数生成脚本，机器行带 \`#T6-\` 前缀。
 *   #T6-STEP id=... rc=N detail=...
 *   #T6-CHECK name=... ok=0|1 detail=...
 *   #T6-SUMMARY OK=a b FAIL=c d
 *
 * 每个脚本末尾都写 \`exit 0\`：单项失败不该让整条链路停下（「失败继续下一项」），
 * 成败看 STEP 行里的 rc，不看进程退出码。
 */
object LinuxEnvBootstrap {

    const val STEP = "#T6-STEP"
    const val CHECK = "#T6-CHECK"
    const val SUMMARY = "#T6-SUMMARY"

    /** 用户原话的两条安装命令；改包名时两边一起改。 */
    const val TERMUX_PACKAGES = "nodejs zip clang make python git jq ffmpeg sqlite unzip termux-api"
    const val ALPINE_PACKAGES =
        "nodejs npm zip gcc make musl-dev py3-pip python3-dev libxml2-dev libxslt-dev jpeg-dev zlib-dev git curl"

    /** 国内源：pypi 官方在大陆经常拖到超时，超时会让全量集永远装不完。 */
    const val PIP_INDEX = "https://pypi.tuna.tsinghua.edu.cn/simple"
    const val PIP_FULL = "requests beautifulsoup4 lxml pillow numpy pandas pyyaml opencv-python-headless"

    /** 回退集：代码真正 import 的那几个，体积 < 60MB。 */
    const val PIP_LIGHT = "requests beautifulsoup4 pillow pyyaml"

    /** 全量集体积上限（KB）。超了就认为不值得为「可能有用的包」付存储与时长。 */
    const val PIP_BULK_LIMIT_KB = 300 * 1024

    const val GIT_MIRROR = "https://gh-proxy.com/https://github.com/"
    const val GIT_UPSTREAM = "https://github.com/"
    const val GIT_MIRROR_KEY = "url." + GIT_MIRROR + ".insteadOf"

    /** 验收 clone 用的仓库：只有一个 README，镜像通不通立刻见分晓。 */
    const val CLONE_REPO = "https://github.com/octocat/Hello-World.git"
    const val CLONE_DIR = SDCARD + "/dsh-env-check"

    const val STEP_TERMUX = "termux-packages"
    const val STEP_ALPINE = "alpine-packages"
    const val STEP_PYTHON = "python"
    const val STEP_PYTHON_LIGHT = "python-light"
    const val STEP_GIT = "git-mirror"
    const val STEP_VERIFY = "verify"

    /** shell 里的美元符：Kotlin 模板里要写成 "\\$" 才会原样进脚本。 */
    private const val D = "\$"

    /** 两组通道：Termux 广播 vs proot guest。装包命令和网络栈都不是一回事。 */
    enum class Group { Termux, Proot }

    data class Step(
        val id: String,
        val title: String,
        val group: Group,
        val timeoutMs: Long,
        val script: String,
    )

    /** 一键执行的全部步骤（顺序即执行顺序）。python 的轻量回退由 runner 按需追加。 */
    fun steps(cloneDir: String = CLONE_DIR): List<Step> = listOf(
        Step(STEP_TERMUX, "Termux 基础包", Group.Termux, 900_000L, termuxScript()),
        Step(STEP_ALPINE, "Linux 基础包（apk）", Group.Proot, 900_000L, alpineScript()),
        Step(STEP_PYTHON, "Python 依赖", Group.Proot, 600_000L, pythonScript()),
        Step(STEP_GIT, "git 镜像", Group.Proot, 60_000L, gitScript()),
        Step(STEP_VERIFY, "验收自检", Group.Proot, 300_000L, verifyScript(cloneDir)),
    )

    fun lightStep(): Step =
        Step(STEP_PYTHON_LIGHT, "Python 轻量依赖（回退）", Group.Proot, 300_000L, pythonScript(light = true))

    /**
     * Termux 侧：pkg update 后 pkg install -y nodejs zip clang make python git jq ffmpeg sqlite unzip termux-api。
     *
     * update 失败（镜像抽风）不拦 install——索引已同步过的机器照样能装，所以两条各自取 rc 都报出来。
     */
    fun termuxScript(): String = """
        set -u
        echo "[termux] pkg update && pkg install -y $TERMUX_PACKAGES"
        upd=0
        pkg update -y </dev/null 2>&1 || upd=${D}?
        rc=0
        pkg install -y $TERMUX_PACKAGES 2>&1 || rc=${D}?
        echo "$STEP id=$STEP_TERMUX rc=${D}rc update=${D}upd"
        exit 0
    """.trimIndent()

    /**
     * proot 侧：apk add nodejs npm zip gcc make musl-dev py3-pip python3-dev libxml2-dev
     * libxslt-dev jpeg-dev zlib-dev git curl。
     *
     * 套 [LinuxToolchain.lockedScript]：proot 下并发 apk 会互相踩数据库。
     */
    fun alpineScript(): String = LinuxToolchain.lockedScript(
        """
        set -u
        apk update 2>&1 || true
        rc=0
        apk add --no-cache $ALPINE_PACKAGES 2>&1 || rc=${D}?
        echo "$STEP id=$STEP_ALPINE rc=${D}rc"
        exit 0
        """.trimIndent(),
    )

    /**
     * python 依赖：先全量；失败或体积超 [PIP_BULK_LIMIT_KB] 就退回轻量集。
     *
     * 「体积超了也退」的原因：全量集在 proot 根分区能吃掉 300MB+，之后连日志都写不下，
     * 而代码真正 import 的只有轻量集那几个。light=true = 超时/失败后的第二次尝试，直接只装轻量集。
     */
    fun pythonScript(light: Boolean = false): String {
        val first = if (light) PIP_LIGHT else PIP_FULL
        val id = if (light) STEP_PYTHON_LIGHT else STEP_PYTHON
        val setName = if (light) "light" else "full"
        return """
            set -u
            PY=${D}(command -v python3 2>/dev/null) || true
            if [ -z "${D}PY" ]; then echo "$STEP id=$id rc=127 detail=no-python3"; exit 0; fi
            PIP=${D}(command -v pip3 2>/dev/null || command -v pip 2>/dev/null) || true
            if [ -z "${D}PIP" ]; then echo "$STEP id=$id rc=127 detail=no-pip"; exit 0; fi
            # 老 pip 不认 --break-system-packages：先问它支不支持，别把 option 错误当成装包失败。
            EXTRA=''
            if "${D}PIP" --help 2>&1 | grep -q -- '--break-system-packages'; then EXTRA=--break-system-packages; fi
            LOG=/tmp/dsh-t6-pip.log
            install_set() {
              # shellcheck disable=SC2086
              "${D}PIP" install --no-input --disable-pip-version-check -i $PIP_INDEX ${D}EXTRA ${D}1 > "${D}LOG" 2>&1
              r=${D}?
              tail -n 12 "${D}LOG"
              return ${D}r
            }
            set_name=$setName
            install_set "$first"
            rc=${D}?
            SITE=${D}("${D}PY" -c 'import sysconfig;print(sysconfig.get_paths().get("purelib",""))' 2>/dev/null) || true
            size=${D}(du -sk "${D}SITE" 2>/dev/null | awk '{print ${D}1+0}')
            case "${D}size" in ''|*[!0-9]*) size=0 ;; esac
            if [ "${D}rc" -eq 0 ] && [ "${D}set_name" = full ] && [ "${D}size" -gt $PIP_BULK_LIMIT_KB ]; then
              echo "[python] 全量集 ${D}size KB 超过上限 $PIP_BULK_LIMIT_KB KB，卸载大件后退回轻量集"
              "${D}PIP" uninstall -y numpy pandas opencv-python-headless lxml >/dev/null 2>&1 || true
              install_set "$PIP_LIGHT"
              rc=${D}?
              set_name=light
            fi
            if [ "${D}rc" -ne 0 ] && [ "${D}set_name" = full ]; then
              echo "[python] 全量集失败 rc=${D}rc，退回轻量集"
              install_set "$PIP_LIGHT"
              rc=${D}?
              set_name=light
            fi
            echo "$STEP id=$id rc=${D}rc set=${D}set_name size_kb=${D}size"
            exit 0
        """.trimIndent()
    }

    /**
     * git 镜像：只改 github.com 的前缀，api.github.com 必须仍是直连
     * （否则 Release / API 查询会绕到镜像上并 404）。
     */
    fun gitScript(): String = """
        set -u
        KEY='$GIT_MIRROR_KEY'
        git config --global --unset-all "${D}KEY" 2>/dev/null || true
        git config --global "${D}KEY" "$GIT_UPSTREAM"
        # 以前填过更宽的规则会盖掉刚设的那条，一律清掉。
        git config --global --get-regexp '^url\..*\.insteadof${D}' 2>/dev/null | while read -r k v; do
          case "${D}v" in
            *api.github.com*) git config --global --unset-all "${D}k" 2>/dev/null || true ;;
          esac
        done
        MIRROR=${D}(git config --global --get-all "${D}KEY" 2>/dev/null | grep -c '^https') || true
        API=${D}(git config --global --get-regexp '^url\..*\.insteadof${D}' 2>/dev/null | grep -c 'api\.github\.com') || true
        case "${D}MIRROR" in ''|*[!0-9]*) MIRROR=0 ;; esac
        case "${D}API" in ''|*[!0-9]*) API=0 ;; esac
        rc=0
        [ "${D}MIRROR" -ge 1 ] || rc=1
        [ "${D}API" -eq 0 ] || rc=1
        echo "$STEP id=$STEP_GIT rc=${D}rc mirror=${D}MIRROR api_rules=${D}API"
        exit 0
    """.trimIndent()

    /**
     * 验收自检：node -v、zip -v、python 各包 import、git clone 到共享存储临时目录。
     * 逐条打印 CHECK，最后一行是唯一汇总行（OK / FAIL 两个列表）。
     *
     * clone 走第四步配好的镜像，所以这条同时验证「镜像能用」。
     */
    fun verifyScript(cloneDir: String = CLONE_DIR): String = """
        set -u
        BASE='$cloneDir'
        if ! mkdir -p "${D}BASE" 2>/dev/null; then BASE=/workspace/.dsh-env-check; mkdir -p "${D}BASE" 2>/dev/null || true; fi
        ok=''
        bad=''
        chk() {
          name=${D}1; shift
          out=${D}("${D}@" 2>&1); rc=${D}?
          first=${D}(printf '%s' "${D}out" | head -n 1 | tr -d '\r')
          if [ "${D}rc" -eq 0 ]; then
            ok="${D}ok ${D}name"
            echo "$CHECK name=${D}name ok=1 detail=${D}first"
          else
            bad="${D}bad ${D}name"
            echo "$CHECK name=${D}name ok=0 detail=rc=${D}rc ${D}first"
          fi
        }
        echo "[verify] node / zip / python 包 / git clone"
        chk node node -v
        chk zip zip -v
        chk py-core python3 -c 'import requests, bs4, PIL, yaml'
        chk py-numpy python3 -c 'import numpy'
        chk py-pandas python3 -c 'import pandas'
        chk py-cv2 python3 -c 'import cv2'
        rm -rf "${D}BASE/Hello-World" 2>/dev/null || true
        chk git-clone git clone --depth 1 $CLONE_REPO "${D}BASE/Hello-World"
        echo "[verify] clone 目录：${D}BASE/Hello-World"
        echo "$SUMMARY OK=${D}ok FAIL=${D}bad"
        exit 0
    """.trimIndent()

    /** STEP 行的解析结果。 */
    data class ParsedStep(val id: String, val rc: Int, val detail: String)

    /** 从一次 exec 的输出里挑出本步的 STEP 行；挑不到返回 null（进程被杀 / 通道没起来）。 */
    fun parseStep(output: String, id: String): ParsedStep? {
        val line = output.lineSequence()
            .firstOrNull { it.startsWith(STEP + " id=" + id + " ") || it.trim() == STEP + " id=" + id }
            ?: return null
        val parts = line.removePrefix(STEP).trim().split(' ')
        return ParsedStep(
            id = id,
            rc = parts.firstOrNull { it.startsWith("rc=") }?.substringAfter("rc=")?.toIntOrNull() ?: -1,
            // rc 之后剩下的键值对全是细节（每步不一样：update=0 / set=light size_kb=...）。
            detail = parts.filterNot { it.startsWith("id=") || it.startsWith("rc=") }.joinToString(" "),
        )
    }

    /** CHECK 行的 detail 在最后、可以带空格，所以只按前缀切 name/ok，余下全是 detail。 */
    fun parseChecks(output: String): List<LinuxCheck> = output.lineSequence()
        .filter { it.startsWith(CHECK + " ") }
        .mapNotNull { line ->
            val body = line.removePrefix(CHECK).trim()
            val name = body.substringAfter("name=", "").substringBefore(' ')
            val ok = body.substringAfter(" ok=", "").substringBefore(' ')
            if (name.isEmpty() || ok.isEmpty()) return@mapNotNull null
            LinuxCheck(name, ok == "1", body.substringAfter("detail=", "").trim())
        }
        .toList()

    fun parseSummary(output: String): String? =
        output.lineSequence().firstOrNull { it.startsWith(SUMMARY + " ") }
}

/** 一步的结果：rc 与耗时都留着，报表直接给「哪一步、几秒、退出码」。 */
data class EnvStepResult(
    val id: String,
    val title: String,
    val ok: Boolean,
    val rc: Int,
    val elapsedMs: Long,
    val detail: String,
    val output: String,
)

data class EnvBootstrapReport(
    val steps: List<EnvStepResult>,
    val checks: List<LinuxCheck>,
    val summary: String?,
    val raw: String,
) {
    /** 轻量集兜住了就算过：全量集失败只是「少了几个可选包」，环境本身是好的。 */
    private val pythonRecovered: Boolean
        get() = steps.any { it.id == LinuxEnvBootstrap.STEP_PYTHON_LIGHT && it.ok }

    val ok: Boolean
        get() = steps.isNotEmpty() && steps.all { it.ok || (pythonRecovered && it.id == LinuxEnvBootstrap.STEP_PYTHON) }

    fun failed(): List<EnvStepResult> =
        steps.filter { !it.ok && !(pythonRecovered && it.id == LinuxEnvBootstrap.STEP_PYTHON) }

    /** 一行汇报：每步 id=rc(秒)，末尾接验收汇总行。 */
    fun line(): String = steps.joinToString(" ") { it.id + "=" + it.rc + "(" + (it.elapsedMs / 1000) + "s)" } +
        (summary?.let { " · " + it } ?: "")
}

/**
 * 按 [LinuxEnvBootstrap.steps] 顺序跑，每步独立判定、失败继续。
 *
 * 通道由调用方按 [LinuxEnvBootstrap.Group] 解析（Termux 走广播，proot 走 guest），
 * 解析不出来就记一步失败，不抛异常——UI 要的是「哪一步没就位」。
 */
class LinuxEnvBootstrapRunner(
    private val channelFor: (LinuxEnvBootstrap.Group) -> ShellChannel?,
    private val logFile: File? = null,
) {

    suspend fun run(
        cloneDir: String = LinuxEnvBootstrap.CLONE_DIR,
        progress: (String) -> Unit = {},
    ): EnvBootstrapReport {
        val raw = StringBuilder()
        val results = mutableListOf<EnvStepResult>()
        val queue = LinuxEnvBootstrap.steps(cloneDir).toMutableList()
        var total = queue.size
        var index = 0
        while (index < queue.size) {
            val step = queue[index]
            index++
            progress("(" + index + "/" + total + ") " + step.title)
            val channel = channelFor(step.group)
            if (channel == null) {
                results += EnvStepResult(step.id, step.title, false, -1, 0L, "通道不可用：" + step.group, "")
                continue
            }
            val started = System.currentTimeMillis()
            val res = runCatching { channel.exec(step.script, step.timeoutMs) }
                .getOrElse { ShellResult.unavailable(it.message ?: "exec 失败") }
            val elapsed = System.currentTimeMillis() - started
            val captured = res.stdout + res.stderr
            val parsed = LinuxEnvBootstrap.parseStep(captured, step.id)
            var rc = parsed?.rc ?: res.code
            // 这一步可能是 apk add：落盘与回报的文本统一过 LinuxApkLog，两条已知无害的 rename 行不进日志。
            val out = LinuxApkLog.filterBenignApkErrors(captured, allPackagesInstalled = rc == 0)
            raw.append("## ").append(step.id).append(" (").append(elapsed).append("ms)\n").append(out).append('\n')
            var detail = parsed?.detail.orEmpty()
            // 验收不写 STEP 行（它写 CHECK）：rc 由检查项自己算，否则脚本 exit 0 会把失败吞掉。
            if (step.id == LinuxEnvBootstrap.STEP_VERIFY) {
                val checks = LinuxEnvBootstrap.parseChecks(out)
                rc = if (checks.isNotEmpty() && checks.all { it.ok }) 0 else 1
                detail = checks.count { it.ok }.toString() + "/" + checks.size
            }
            val result = EnvStepResult(step.id, step.title, rc == 0, rc, elapsed, detail, out)
            results += result
            // 全量集超时/硬失败：补一发轻量集，别让整条链路卡在 300MB 依赖上。
            if (step.id == LinuxEnvBootstrap.STEP_PYTHON && !result.ok) {
                queue += LinuxEnvBootstrap.lightStep()
                total = queue.size
            }
        }
        val text = raw.toString()
        logFile?.let { f -> runCatching { f.parentFile?.mkdirs(); f.appendText(text) } }
        return EnvBootstrapReport(
            steps = results,
            checks = LinuxEnvBootstrap.parseChecks(text),
            summary = LinuxEnvBootstrap.parseSummary(text),
            raw = text,
        )
    }
}
