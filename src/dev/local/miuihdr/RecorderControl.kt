package dev.local.miuihdr

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/** Xiaomi protects its native settings activity with READ_FRAME_BUFFER. */
internal object RecorderControl {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var busy by mutableStateOf(false); private set
    fun open(context: Context) {
        if (busy) return
        val component = "com.miui.screenrecorder.activity.ScreenRecorderSettingActivity"
        try {
            context.startActivity(Intent().setClassName("com.miui.screenrecorder", component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) { /* Use the manager's existing root authorization. */ }
        busy = true
        scope.launch {
            val opened = withContext(Dispatchers.IO) {
                try {
                    val user = android.os.Process.myUid() / 100000
                    val process = ProcessBuilder("su", "-c",
                        "/system/bin/am start --user $user -n com.miui.screenrecorder/$component")
                        .redirectErrorStream(true).start()
                    try {
                        if (!process.waitFor(15, TimeUnit.SECONDS)) false
                        else {
                            val output = process.inputStream.bufferedReader().use { it.readText() }
                            process.exitValue() == 0 && !output.contains("Error:") && !output.contains("Exception")
                        }
                    } finally { process.destroyForcibly() }
                } catch (_: Exception) { false }
            }
            busy = false
            if (!opened) Toast.makeText(context.applicationContext,
                "无法打开设置。请检查 Root 授权，或长按控制中心的屏幕录制按钮。", Toast.LENGTH_LONG).show()
        }
    }
}
