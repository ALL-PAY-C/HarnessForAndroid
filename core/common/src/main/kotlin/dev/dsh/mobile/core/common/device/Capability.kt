package dev.dsh.mobile.core.common.device

/**
 * 六类系统能力。词表只此一份：设置页、授权裁决、手机动作执行三处都读这里，
 * 加一类能力不需要在三个模块里各改一次字符串。
 */
enum class DeviceCapability(val id: String, val label: String, val hint: String) {
    Camera("camera", "相机", "拍照 / 扫码"),
    Microphone("microphone", "麦克风", "录音"),
    Contacts("contacts", "联系人", "读联系人"),
    Sms("sms", "短信", "读短信 / 发短信"),
    Dial("dial", "拨号", "拉起拨号盘"),
    Location("location", "定位", "读位置"),
    ;

    companion object {
        fun of(id: String): DeviceCapability? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 授权三档。
 *
 * [Always] 常驻放行；[Session] 本会话内放行（进程内记住，重启失效）；[Ask] 每次都问。
 * 默认 [Ask] —— 没有设置过的能力一律要问，静默放行只能靠用户显式选。
 */
enum class CapabilityTier(val id: String, val label: String) {
    Always("always", "始终允许"),
    Session("session", "本会话"),
    Ask("ask", "每次询问"),
    ;

    companion object {
        fun of(id: String?): CapabilityTier? {
            val v = id?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.id == v }
        }
    }
}

/**
 * 三档判定的纯函数层，与 Android 无关，可单测。
 *
 * 解析失败/缺项一律回落 [CapabilityTier.Ask]：配置坏了只会多问一次，不会静默放行。
 * core:common 没有 org.json 依赖，且这份值只是一张定长小表，所以手写解析，不为一行解析引依赖。
 */
object CapabilityTiers {

    val DEFAULT: Map<DeviceCapability, CapabilityTier> = DeviceCapability.entries.associateWith { CapabilityTier.Ask }

    /** 读一个能力的档位；[raw] 是 SettingsStore 里的原始字符串。 */
    fun tierOf(raw: String?, capability: DeviceCapability): CapabilityTier =
        parse(raw)[capability] ?: CapabilityTier.Ask

    /** 解析成完整六项；缺项补 [CapabilityTier.Ask]。 */
    fun parse(raw: String?): Map<DeviceCapability, CapabilityTier> {
        if (raw.isNullOrBlank()) return DEFAULT
        val pairs = runCatching { parsePairs(raw) }.getOrNull()
        if (pairs.isNullOrEmpty()) return DEFAULT
        return DeviceCapability.entries.associateWith { pairs[it] ?: CapabilityTier.Ask }
    }

    /** 落盘格式：{"camera":"always","microphone":"ask",...}，六项齐全，读侧不用处理缺键。 */
    fun encode(map: Map<DeviceCapability, CapabilityTier>): String =
        DeviceCapability.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { cap ->
            "\"" + cap.id + "\":\"" + (map[cap] ?: CapabilityTier.Ask).id + "\""
        }

    /** 改一项，其余保持；返回新表（调用方直接 encode 落盘）。 */
    fun with(map: Map<DeviceCapability, CapabilityTier>, capability: DeviceCapability, tier: CapabilityTier): Map<DeviceCapability, CapabilityTier> =
        DeviceCapability.entries.associateWith { if (it == capability) tier else (map[it] ?: CapabilityTier.Ask) }

    private fun parsePairs(raw: String): Map<DeviceCapability, CapabilityTier> {
        val body = raw.trim().removePrefix("{").removeSuffix("}")
        val out = HashMap<DeviceCapability, CapabilityTier>()
        for (chunk in body.split(',')) {
            val sep = chunk.indexOf(':')
            if (sep <= 0) continue
            val key = chunk.substring(0, sep).trim().trim('"', '\'')
            val value = chunk.substring(sep + 1).trim().trim('"', '\'')
            val cap = DeviceCapability.of(key) ?: continue
            val tier = CapabilityTier.of(value) ?: continue
            out[cap] = tier
        }
        return out
    }
}

/**
 * 需要系统权限的 phone 动作词表。toolbridge 的 dispatch、PhoneActions 目录、ToolCatalog 声明
 * 三处都从这里取，不再各写一份（T4.5 的验收点之一就是这三处不能分叉）。
 */
object CapabilityActions {

    const val CONTACTS = "contacts"
    const val SMS_LIST = "sms_list"
    const val SMS_SEND = "sms_send"
    const val DIAL = "dial"
    const val LOCATION = "location"
    const val AUDIO_RECORD = "audio_record"

    /** 顺序即声明顺序：ToolCatalog 的 enum 与设置页列表都按这个顺序摊开。 */
    val PHONE_ACTIONS: List<String> = listOf(CONTACTS, SMS_LIST, SMS_SEND, DIAL, LOCATION, AUDIO_RECORD)

    fun capabilityOf(action: String?): DeviceCapability? = when (action) {
        CONTACTS -> DeviceCapability.Contacts
        SMS_LIST, SMS_SEND -> DeviceCapability.Sms
        DIAL -> DeviceCapability.Dial
        LOCATION -> DeviceCapability.Location
        AUDIO_RECORD -> DeviceCapability.Microphone
        else -> null
    }

    fun isCapabilityAction(action: String?): Boolean = capabilityOf(action) != null
}
