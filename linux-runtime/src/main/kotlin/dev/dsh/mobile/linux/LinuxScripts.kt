package dev.dsh.mobile.linux

/**
 * T5：guest 里跑的脚本（预置 / 自检）。纯函数生成，便于单测。
 *
 * 输出尾部带 `#T5-` 前缀的机器可读行，由 [LinuxBootstrap] 与 [LinuxSelfCheck] 解析：
 *   #T5-STATE installed=... / missing=...
 *   #T5-APK-BEGIN / #T5-APK-END rc=N   （中间是 apk 原始输出，必须原样保留）
 *   #T5-ROLLBACK begin|done
 *   #T5-STATUS ok=0|1 skipped=0|1 apk_rc=N
 *   #T5-ERROR missing=...
 *   #T5-CHECK name=... ok=0|1 detail=...
 */
object LinuxScripts {

    /** 必需工具：python3 自带，其余都得装。fetch-rootfs.sh 与 assets 清单里的同名列表不许漂移。 */
    const val REQUIRED_PACKAGES = "python3 py3-pip git curl wget ca-certificates"

    const val APK_BEGIN = "#T5-APK-BEGIN"
    const val APK_END = "#T5-APK-END"
    const val DNS_PROBE_HOST = "example.com"
    const val HTTPS_PROBE_URL = "https://example.com"

    /** shell 里的 `$`：Kotlin 模板里要写成 \$ 才能原样进脚本。 */
    private const val D = "\$"

    fun required(): List<String> = REQUIRED_PACKAGES.split(' ')

    /**
     * 幂等预置：缺什么装什么，全齐就跳过联网。
     * 成功判据不是 apk 退出码——proot 下 apk 会因个别文件 rename 失败返回非零，
     * 但必需包其实都进了数据库——所以逐个 `apk info -e` 校验；仍缺就删掉本次新装
     * 的包并恢复 /etc/apk/world 快照。
     */
    fun provision(required: List<String> = required()): String {
        val req = required.joinToString(" ")
        return """
            set -u
            REQ='$req'
            STATE=/root/.dsh-t5
            mkdir -p ${D}STATE
            installed=''
            missing=''
            for p in ${D}REQ; do
              if apk info -e "${D}p" >/dev/null 2>&1; then installed="${D}installed ${D}p"; else missing="${D}missing ${D}p"; fi
            done
            echo "#T5-STATE installed=${D}installed"
            echo "#T5-STATE missing=${D}missing"
            if [ -z "${D}missing" ]; then
              echo "#T5-STATUS ok=1 skipped=1 apk_rc=-"
              exit 0
            fi
            cp /etc/apk/world "${D}STATE/world.bak" 2>/dev/null
            # proot 下 apk 的 unlink+rename 会失败：装之前先清掉会撞车的目标与残留临时文件。
            # 只清「包还没注册」的那些，已装好的文件不碰。
            if ! apk info -e unzip >/dev/null 2>&1; then rm -f /usr/bin/zipinfo 2>/dev/null; fi
            if ! apk info -e ncurses-terminfo-base >/dev/null 2>&1; then rm -f /etc/terminfo/v/vt220 2>/dev/null; fi
            find /etc/terminfo/v /usr/bin -maxdepth 1 -name '.apk.*' -delete 2>/dev/null || true
            echo "#T5-APK-BEGIN"
            apk add --no-cache ${D}missing 2>&1
            apk_rc=${D}?
            echo ""
            echo "#T5-APK-END rc=${D}apk_rc"
            still=''
            for p in ${D}REQ; do
              apk info -e "${D}p" >/dev/null 2>&1 || still="${D}still ${D}p"
            done
            if [ -n "${D}still" ]; then
              echo "#T5-ROLLBACK begin missing=${D}still"
              for p in ${D}REQ; do
                case " ${D}missing " in
                  *" ${D}p "*) apk del "${D}p" >/dev/null 2>&1 ;;
                esac
              done
              if [ -f "${D}STATE/world.bak" ]; then cp "${D}STATE/world.bak" /etc/apk/world; fi
              echo "#T5-ROLLBACK done"
              echo "#T5-STATUS ok=0 skipped=0 apk_rc=${D}apk_rc"
              echo "#T5-ERROR missing=${D}still"
              exit 1
            fi
            echo "#T5-STATUS ok=1 skipped=0 apk_rc=${D}apk_rc"
            exit 0
        """.trimIndent()
    }

    /**
     * 自检：每项工具版本 + DNS 解析 + HTTPS + 证书 + pip 配置 + 退出码/流分离。
     * 只读，不改环境；供 T10 直接调用。
     */
    fun selfCheck(): String = """
        set -u
        line() { printf '#T5-CHECK name=%s ok=%s detail=%s\n' "${D}1" "${D}2" "${D}3"; }
        probe() {
          n=${D}1
          shift
          out=$("${D}@" 2>&1)
          rc=${D}?
          first=$(printf '%s' "${D}out" | head -n 1)
          if [ ${D}rc -eq 0 ] && [ -n "${D}first" ]; then
            line "${D}n" 1 "${D}first"
          else
            line "${D}n" 0 "rc=${D}rc ${D}{first:-no output}"
          fi
        }
        probe python3 python3 -c 'import sys;print(sys.version.split()[0])'
        probe pip3 pip3 --version
        probe git git --version
        probe curl curl --version
        probe wget wget --version
        probe apk apk --version
        if [ -s /etc/ssl/certs/ca-certificates.crt ]; then
          line ca-certificates 1 "$(wc -c < /etc/ssl/certs/ca-certificates.crt) bytes"
        else
          line ca-certificates 0 'missing /etc/ssl/certs/ca-certificates.crt'
        fi
        if [ -s /etc/pip.conf ]; then
          line pip-config 1 "$(tr '\n' ' ' < /etc/pip.conf)"
        else
          line pip-config 0 'missing /etc/pip.conf'
        fi
        if [ -s /etc/resolv.conf ]; then
          if grep -q '^# degraded:' /etc/resolv.conf; then
            line resolv 1 "降级 · 未探测到应答，已写入 $(grep '^nameserver ' /etc/resolv.conf | tr '\n' ' ')"
          else
            line resolv 1 "有内容 · $(tr '\n' ' ' < /etc/resolv.conf)"
          fi
        else
          line resolv 0 'empty or missing /etc/resolv.conf'
        fi
        dns=$(python3 -c 'import socket;print(socket.gethostbyname("$DNS_PROBE_HOST"))' 2>&1)
        dns_rc=${D}?
        if [ ${D}dns_rc -eq 0 ] && [ -n "${D}dns" ]; then
          line dns 1 "${D}dns"
        else
          line dns 0 "rc=${D}dns_rc $(printf '%s' "${D}dns" | head -n 1)"
        fi
        code=$(curl -sS -m 20 -o /dev/null -w '%{http_code}' $HTTPS_PROBE_URL 2>&1)
        code_rc=${D}?
        if [ ${D}code_rc -eq 0 ] && [ "${D}code" = "200" ]; then
          line https 1 "http_code=${D}code"
        else
          line https 0 "rc=${D}code_rc ${D}code"
        fi
        err=$(python3 -c 'import sys;sys.stderr.write("dsh-stderr")' 2>&1 >/dev/null)
        if [ "${D}err" = "dsh-stderr" ]; then
          line stderr 1 'captured dsh-stderr'
        else
          line stderr 0 "got: ${D}err"
        fi
        python3 -c 'import sys;sys.exit(7)' 2>/dev/null
        rc7=${D}?
        if [ ${D}rc7 -eq 7 ]; then
          line exitcode 1 'rc=7'
        else
          line exitcode 0 "rc=${D}rc7"
        fi
    """.trimIndent()
}
