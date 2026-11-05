package dev.dsh.mobile.toolbridge.phone

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.media.MediaRecorder
import android.os.Build
import android.view.Display
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException


internal suspend fun PhoneBridge.doAudioRecord(obj: JSONObject, started: Long): PhoneResult {
    if (recording) return failResult(PhoneErrorCode.Busy, "Already recording; wait for the current take to finish")
    val durationMs = obj.optLong("durationMs", 3000L).coerceIn(500L, 15_000L)
    val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
    val out = File(dir, "rec-" + System.currentTimeMillis() + ".m4a")
    val recorder = MediaRecorder()
    recording = true
    try {
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setOutputFile(out.absolutePath)
        recorder.prepare()
        recorder.start()
        cancellableDelay(durationMs)
        recorder.stop()
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        runCatching { recorder.reset() }
        return failResult(PhoneErrorCode.ArtifactWrite, "Recording failed: " + (t.message ?: t.javaClass.simpleName))
    } finally {
        runCatching { recorder.release() }
        recording = false
    }
    if (!out.exists() || out.length() == 0L) return failResult(PhoneErrorCode.ArtifactWrite, "Recording file is empty")
    return okEnvelope(
        started,
        JSONObject().put("path", out.absolutePath).put("bytes", out.length()).put("durationMs", durationMs),
    )
}

internal fun PhoneBridge.doScreenshot(svc: AccessibilityService): PhoneResult {
    if (Build.VERSION.SDK_INT < 30) {
        return PhoneResult(false, "", "Screenshot requires Android 11+ (current " + Build.VERSION.SDK_INT + "); use readscreen to read the node tree instead")
    }
    val done = CountDownLatch(1)
    val result = AtomicReference<ScreenshotOutcome>(ScreenshotOutcome.Timeout)
    try {
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, a11yExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                result.set(ScreenshotOutcome.Success(screenshot))
                done.countDown()
            }

            override fun onFailure(errorCode: Int) {
                result.set(ScreenshotOutcome.Failure(errorCode))
                done.countDown()
            }
        })
        if (!done.await(5000, TimeUnit.MILLISECONDS)) return PhoneResult(false, "", "Screenshot timed out")
    } catch (t: Throwable) {
        return PhoneResult(false, "", "Screenshot failed: " + t.message)
    }
    return when (val r = result.get()) {
        is ScreenshotOutcome.Failure -> PhoneResult(false, "", "Screenshot failed: errorCode=" + r.code + "; fall back to readscreen to read the node tree")
        is ScreenshotOutcome.Success -> {
            val bitmap = Bitmap.wrapHardwareBuffer(r.shot.hardwareBuffer, r.shot.colorSpace)
                ?: return PhoneResult(false, "", "Cannot decode the screenshot (wrapHardwareBuffer returned null)")
            val dir = File(context.cacheDir, "screenshots").apply { mkdirs() }
            val f = File(dir, "shot_" + System.currentTimeMillis() + ".png")
            FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            // 回图像：role=tool 放不进 image_url，先登记，下一次模型请求把它并进 user 消息。
            val queued = PhoneImageQueue.enqueue(f.absolutePath, "image/png", f.length())
            val out = buildString {
                // 第一行逐字节保持旧文案（悬浮窗/动作日志只取第一行）。
                append("Saved ").append(bitmap.width).append("x").append(bitmap.height).append(" → ").append(f.absolutePath)
                append("\nimageQueued: ").append(queued.queued)
                if (!queued.queued) append(" (not queued: ").append(queued.reason ?: "unknown").append(")")
            }
            PhoneResult(true, out, imageQueued = queued.queued, imageQueueReason = queued.reason)
        }
        else -> PhoneResult(false, "", "Screenshot failed: no result")
    }
}

internal sealed class ScreenshotOutcome {
    data class Success(val shot: AccessibilityService.ScreenshotResult) : ScreenshotOutcome()
    data class Failure(val code: Int) : ScreenshotOutcome()
    data object Timeout : ScreenshotOutcome()
}
