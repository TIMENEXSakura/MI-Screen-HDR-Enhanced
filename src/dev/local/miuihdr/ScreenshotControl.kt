package dev.local.miuihdr

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** A user-requested stop of Xiaomi's screenshot package, independent of mounting. */
internal object ScreenshotControl {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var busy by mutableStateOf(false); private set
    fun stop(context: Context) {
        if (busy) return
        val app = context.applicationContext
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val process = ProcessBuilder("su", "-c", "/system/bin/am force-stop --user all com.miui.screenshot")
                        .redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader().use { it.readText().takeLast(1500) }
                    if (process.waitFor() == 0) "系统截屏已停止，下次截图时重新启动。"
                    else "停止失败：${output.ifBlank { "请检查 Root 授权" }}"
                } catch (e: Exception) { "停止失败：${e.message ?: "无法获取 Root 权限"}" }
            }
            busy = false
            Toast.makeText(app, result, Toast.LENGTH_LONG).show()
        }
    }
}
