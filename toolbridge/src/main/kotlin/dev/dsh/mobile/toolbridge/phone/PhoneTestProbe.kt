package dev.dsh.mobile.toolbridge.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 调试探针：无 UI 验证手机控制工具。触发方式：
 * adb shell am broadcast -n dev.dsh.mobile.debug/dev.dsh.mobile.toolbridge.phone.PhoneTestProbe --es json '{"action":"tap","fx":0.5,"fy":0.5}'
 * 结果打到 logcat（tag=PhoneProbe）。仅测试用；上线前可移除或加 debuggable 守卫。
 */
class PhoneTestProbe : BroadcastReceiver() {

    companion object {
        const val TAG = "PhoneProbe"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            Log.w(TAG, "PhoneTestProbe 仅在 debuggable 构建可用，release 包忽略")
            return
        }
        val json = intent.getStringExtra("json")
            ?: JSONObject().put("action", "readscreen").toString()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val r = PhoneBridge(context.applicationContext).exec(json)
                Log.d(TAG, "EXEC json=" + json)
                Log.d(TAG, "RESULT ok=" + r.ok + " error=" + r.error)
                Log.d(TAG, "OUTPUT>>> " + r.output)
            } catch (t: Throwable) {
                Log.d(TAG, "CRASH " + t.javaClass.name + ": " + t.message, t)
            } finally {
                pending.finish()
            }
        }
    }
}
