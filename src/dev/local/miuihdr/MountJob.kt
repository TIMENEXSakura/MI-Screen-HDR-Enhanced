package dev.local.miuihdr

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.File

/** One application-lifetime installer, observed by the home and flashing pages. */
internal object MountJob {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var busy by mutableStateOf(false); private set
    var success by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null); private set
    var phase by mutableStateOf("准备安装"); private set
    var text by mutableStateOf(""); private set
    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun line(value: String) {
        text = (text + value + "\n").takeLast(96000)
        if (value.startsWith("STAGE=")) phase = when(value.removePrefix("STAGE=")) {
            "discover" -> "识别运行库"; "patch" -> "修补运行库"; "package" -> "生成挂载模块"; "install" -> "安装挂载模块"; else -> phase
        }
    }
    fun start(context: Context, uri: Uri?) {
        if (busy) return
        val app = context.applicationContext
        busy = true; success = false; text = ""; status = null; phase = "准备安装"
        scope.launch {
            var imported: File? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    if (uri != null) {
                        withContext(Dispatchers.Main) { phase = "读取原始 SO"; line("正在读取选定的原始库…") }
                        imported = File.createTempFile("original-", ".so", app.cacheDir)
                        app.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "无法读取所选文件" }
                            imported!!.outputStream().use { output ->
                                val buffer = ByteArray(32768); var total = 0L
                                while (true) { val n = input.read(buffer); if (n < 0) break
                                    total += n; require(total <= 128L * 1024 * 1024) { "SO 文件超过 128 MB" }; output.write(buffer,0,n)
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { line("请求 Root 权限，准备修补并安装。") }
                    val apk = app.applicationInfo.sourceDir
                    val command = "export PATH=/system/bin:/system/xbin:/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk; CLASSPATH=${quote(apk)} /system/bin/app_process /system/bin dev.local.miuihdr.RootSetup ${quote(apk)} install${imported?.let { " " + quote(it.absolutePath) } ?: ""}"
                    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) { val next = reader.readLine() ?: break; withContext(Dispatchers.Main) { line(next) } }
                    }
                    process.waitFor()
                }
                success = result == 0
                phase = if (success) "安装完成" else "安装失败"
                status = if (success) "挂载模块已安装，重启设备后加载。" else "安装未完成，请查看日志中的具体原因。"
            } catch (e: Exception) {
                success = false; phase = "安装失败"; status = e.message ?: "无法启动安装器"
                line("ERROR: ${status}")
            } finally {
                imported?.delete(); busy = false
                app.getSharedPreferences("local_status", Context.MODE_PRIVATE).edit()
                    .putString("result",status).putString("detail",text).putBoolean("last_install_success",success).apply()
                app.getSharedPreferences("local_status", Context.MODE_PRIVATE).edit().putString("installed_version",if(success) "1.5.1" else "").apply()
            }
        }
    }
    fun restore(context: Context) {
        if(busy || text.isNotEmpty()) return
        val p=context.getSharedPreferences("local_status",Context.MODE_PRIVATE)
        text=p.getString("detail","")?:"";status=p.getString("result",null)
        success=p.getBoolean("last_install_success",false)
        phase=if(success) "安装完成" else if(text.isNotEmpty()) "上次安装记录" else "准备安装"
    }
}
