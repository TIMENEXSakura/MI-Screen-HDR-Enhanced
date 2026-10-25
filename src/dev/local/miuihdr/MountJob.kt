package dev.local.miuihdr

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** One application-lifetime installer, with a full on-disk log and bounded UI tail. */
internal object MountJob {
    private const val LOG_LIMIT = 96000
    private const val LOG_FILE = "last-install.log"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var busy by mutableStateOf(false); private set
    var success by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null); private set
    var phase by mutableStateOf("开始阶段"); private set
    var progress by mutableIntStateOf(0); private set
    var text by mutableStateOf(""); private set
    var truncated by mutableStateOf(false); private set
    private var incompleteLog = false
    /** A diagnostic write failure must never abandon a still-running root installer. */
    private class InstallLog(file: File) : Closeable {
        private val writer = file.bufferedWriter()
        var failure: String? = null; private set
        fun append(value: String) {
            if (failure != null) return
            try { writer.appendLine(value); writer.flush() }
            catch (e: Exception) { failure = e.message ?: "无法写入日志文件" }
        }
        override fun close() {
            try { writer.close() } catch (e: Exception) { failure = failure ?: e.message ?: "无法关闭日志文件" }
        }
    }
    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun stageName(value: String): String? = when(value) {
        "STAGE=prepare" -> "开始阶段"; "STAGE=discover" -> "扫描阶段"; "STAGE=library" -> "获取到库"
        "STAGE=offset" -> "获取到偏移量"; "STAGE=patch" -> "开始修补"
        "STAGE=install" -> "修补完成，正在刷入"; "STAGE=complete" -> "刷入完成"; else -> null
    }
    private fun line(value: String, displayed: String) {
        val combined = text + displayed + "\n"
        truncated = truncated || combined.length > LOG_LIMIT
        text = combined.takeLast(LOG_LIMIT)
        InstallProgress.parse(value)?.let {
            if (it.percent >= progress) {
                progress = InstallProgress.advance(progress, it.percent)
                phase = it.phase
            }
        }
        stageName(value)?.let { phase = it }
    }
    private fun timestamp(started: Long): String {
        val elapsed = SystemClock.elapsedRealtime() - started
        return String.format(Locale.ROOT, "[%02d:%02d.%03d] ", elapsed / 60000, elapsed / 1000 % 60, elapsed % 1000)
    }
    private suspend fun emit(writer: InstallLog, prefs: SharedPreferences, started: Long, value: String) {
        val event = InstallProgress.parse(value)
        val body = event?.let { "[${it.percent}%] ${it.phase}" } ?: stageName(value)?.let { "阶段：$it" } ?: value
        val displayed = timestamp(started) + body
        val wasFailed = writer.failure != null
        writer.append(displayed)
        withContext(Dispatchers.Main) {
            val previous = progress
            val previousPhase = phase
            line(value, displayed)
            if (!wasFailed && writer.failure != null) {
                incompleteLog = true
                line("", timestamp(started) + "日志文件写入失败：${writer.failure}。继续显示界面日志并等待安装进程结束，文件记录可能不完整。")
                prefs.edit().putBoolean("last_install_log_incomplete", true).apply()
            }
            if (progress != previous || phase != previousPhase) prefs.edit().putInt("last_install_progress", progress).putString("last_install_phase", phase).apply()
        }
    }
    /** Called on an IO dispatcher by the export action. */
    fun exportLog(context: Context): String = File(context.filesDir, LOG_FILE).let {
        if (incompleteLog) "注意：日志文件写入曾失败，以下为界面保留的日志末尾，并非完整记录。\n\n$text"
        else if (it.isFile) it.readText() else text
    }
    fun start(context: Context, uri: Uri?) {
        if (busy) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("local_status", Context.MODE_PRIVATE)
        val log = File(app.filesDir, LOG_FILE)
        val started = SystemClock.elapsedRealtime()
        busy = true; success = false; text = ""; truncated = false; incompleteLog = false; status = null; phase = "开始阶段"; progress = 0
        prefs.edit().putBoolean("install_in_progress", true).putBoolean("last_install_success", false)
            .putInt("last_install_progress", 0).putString("last_install_phase", phase).putString("detail", "")
            .putBoolean("last_install_log_incomplete", false).putString("result", "安装尚未完成").apply()
        scope.launch {
            var imported: File? = null
            var installLog: InstallLog? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    InstallLog(log).also { installLog = it }.use { writer ->
                        emit(writer, prefs, started, "开始阶段：准备安装，检查输入文件并请求 Root 权限。")
                        if (uri != null) {
                            val length = try { app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { n -> n > 0 } else null
                            } } catch (_: Exception) { null }
                            emit(writer, prefs, started, "读取选定的原始 SO：${length?.let { "文件大小 $it 字节" } ?: "文件提供方未返回总大小，将按实际读取字节检查"}。")
                            emit(writer, prefs, started, "PROGRESS=1|开始阶段")
                            imported = File.createTempFile("original-", ".so", app.cacheDir)
                            app.contentResolver.openInputStream(uri).use { input ->
                                requireNotNull(input) { "无法读取所选文件" }
                                imported!!.outputStream().use { output ->
                                    val buffer = ByteArray(32768); var total = 0L; var reported = 1
                                    while (true) { val n = input.read(buffer); if (n < 0) break
                                        total += n; require(total <= 128L * 1024 * 1024) { "SO 文件超过 128 MB" }; output.write(buffer, 0, n)
                                        val percent = length?.let { (total * 4 / it).coerceIn(1, 4).toInt() } ?: 1
                                        if (percent > reported) {
                                            emit(writer, prefs, started, "已读取原始 SO：$total / $length 字节。")
                                            emit(writer, prefs, started, "PROGRESS=$percent|开始阶段"); reported = percent
                                        }
                                    }
                                    emit(writer, prefs, started, "原始 SO 读取完成：$total 字节。")
                                }
                            }
                        } else emit(writer, prefs, started, "输入方式：自动扫描本机显示运行库。")
                        emit(writer, prefs, started, "PROGRESS=4|开始阶段")
                        emit(writer, prefs, started, "请求 Root 权限，准备读取运行库与生成挂载模块。")
                        val apk = app.applicationInfo.sourceDir
                        val command = "export PATH=/system/bin:/system/xbin:/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk; CLASSPATH=${quote(apk)} /system/bin/app_process /system/bin dev.local.miuihdr.RootSetup ${quote(apk)} install${imported?.let { " " + quote(it.absolutePath) } ?: ""}"
                        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                        process.inputStream.bufferedReader().use { reader ->
                            while (true) { val next = reader.readLine() ?: break; emit(writer, prefs, started, next) }
                        }
                        process.waitFor().also { emit(writer, prefs, started, "安装进程退出码：$it（0 表示成功）。") }
                    }
                }
                success = result == 0
                progress = InstallProgress.finish(progress, success)
                if (success) phase = "刷入完成"
                status = if (success) "挂载模块已刷入，重启设备后加载。" else "安装未完成，停留在“$phase”（$progress%）。请查看下方失败原因。"
            } catch (e: Exception) {
                success = false; progress = InstallProgress.finish(progress, false)
                status = "安装未完成，停留在“$phase”（$progress%）：${e.message ?: "无法启动安装器"}"
            } finally {
                incompleteLog = incompleteLog || installLog?.failure != null
                if (incompleteLog) status = "$status 日志文件保存不完整，请查看当前界面记录。"
                val summary = timestamp(started) + (status ?: "安装结果未确认")
                withContext(Dispatchers.IO) {
                    try { log.appendText(summary + "\n") } catch (_: Exception) { }
                    imported?.delete()
                }
                line("", summary); busy = false
                prefs.edit().putString("result", status).putString("detail", text)
                    .putBoolean("last_install_success", success).putBoolean("install_in_progress", false)
                    .putBoolean("last_install_log_incomplete", incompleteLog)
                    .putInt("last_install_progress", progress).putString("last_install_phase", phase)
                    .putString("installed_version", if (success) "1.5.1" else "").apply()
            }
        }
    }
    fun restore(context: Context) {
        if (busy || text.isNotEmpty()) return
        val p = context.getSharedPreferences("local_status", Context.MODE_PRIVATE)
        text = p.getString("detail", "") ?: ""; status = p.getString("result", null)
        incompleteLog = p.getBoolean("last_install_log_incomplete", false)
        val log = File(context.filesDir, LOG_FILE)
        if (log.isFile && !incompleteLog) try {
            RandomAccessFile(log, "r").use {
                val count = minOf(it.length(), LOG_LIMIT.toLong()).toInt()
                truncated = it.length() > count
                it.seek(it.length() - count); val bytes = ByteArray(count); it.readFully(bytes)
                val tail = bytes.toString(Charsets.UTF_8)
                text = if (truncated) tail.substringAfter('\n', tail) else tail
            }
        } catch (_: Exception) { }
        val interrupted = p.getBoolean("install_in_progress", false)
        success = p.getBoolean("last_install_success", false) && !interrupted
        progress = if (success) 100 else p.getInt("last_install_progress", 0).coerceIn(0, 99)
        phase = if (success) "刷入完成" else p.getString("last_install_phase", "开始阶段") ?: "开始阶段"
        if (interrupted) status = "上次安装记录中断，结果尚未确认（$progress%）。请查看日志。"
    }
}
