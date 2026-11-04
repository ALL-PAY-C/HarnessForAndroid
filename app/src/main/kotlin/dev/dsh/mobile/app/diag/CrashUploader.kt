package dev.dsh.mobile.app.diag

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.common.net.HttpJson
import dev.dsh.mobile.core.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 崩溃文件上传（T9.12 §2.4）。
 *
 * 只在启动期串行跑一次，绝不在崩溃现场联网：崩溃时进程随时会被杀，发请求还可能把
 * UI 线程回收拖住；下次启动补传才是可靠时机。
 *
 * 语义固定为「单次 POST、10s 超时、不重试、2xx 才删文件」：非 2xx 或超时保留文件，
 * 交给 CrashStore.prune 按 20 条 / 14 天老化。
 */
class CrashUploader(
    private val settings: SettingsStore,
    private val timeoutMs: Int = TIMEOUT_MS,
) {

    /** 返回成功上传（并已删除）的条数。 */
    suspend fun uploadPending(store: CrashStore): Int {
        val (enabled, endpointRaw) = settings.crashUpload()
        val endpoint = endpointRaw.trim()
        if (!enabled || endpoint.isEmpty()) {
            AppLog.i("CRASH_UPLOAD skip enabled=" + enabled + " endpoint=" + if (endpoint.isEmpty()) "-" else "set")
            return 0
        }
        val files = withContext(Dispatchers.IO) { store.list() }
        if (files.isEmpty()) return 0
        var uploaded = 0
        for (file in files) {
            val record = withContext(Dispatchers.IO) { store.read(file) }
            if (record == null) {
                // 半截文件不删：留着是「写盘失败」的证据，删了下次没人知道曾经坏过。
                AppLog.w("CRASH_UPLOAD unreadable file=" + file.name)
                continue
            }
            val result = runCatching { HttpJson.postJson(endpoint, record.toJson(), timeoutMs = timeoutMs) }
            if (result.isFailure) {
                // 不重试，也不再往后试：端点不可达时继续循环只会把启动拖成 N x 10s。
                AppLog.w("CRASH_UPLOAD fail file=" + file.name + " err=" + result.exceptionOrNull()?.message)
                break
            }
            withContext(Dispatchers.IO) { store.delete(file) }
            uploaded++
        }
        AppLog.i("CRASH_UPLOAD done uploaded=" + uploaded + " total=" + files.size)
        return uploaded
    }

    companion object {
        const val TIMEOUT_MS = 10_000
    }
}
