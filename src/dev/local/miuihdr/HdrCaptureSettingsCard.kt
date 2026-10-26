package dev.local.miuihdr

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

@Composable
internal fun HdrCaptureSettingsCard() {
    val context = LocalContext.current
    val uri = remember { Uri.parse("content://dev.local.miuihdr.options") }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Bundle?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf("native") }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { state = withContext(Dispatchers.IO) { context.contentResolver.call(uri,"hdr_capture_settings",null,null) } }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { error = e.message ?: "HDR 设置读取失败" }
    }
    fun save(request: Bundle) {
        busy = true; error = ""
        scope.launch {
            try { state = withContext(Dispatchers.IO) { context.contentResolver.call(uri,"hdr_capture_configure",null,request) ?: error("HDR 设置未确认") } }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { error = e.message ?: "HDR 设置保存失败" }
            finally { busy = false }
        }
    }
    fun label(mode: String) = when(mode) { "display" -> "匹配屏幕颜色"; "compat" -> "兼容取帧"; else -> "系统默认" }
    val s = state
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Surface(onClick = { choice = s?.getString("hdr_capture_mode", "native") ?: "native"; dialog = true },
                enabled = s != null && !busy, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("特殊 HDR 取帧", style = MaterialTheme.typography.titleMedium)
                        Text(if(s == null) "正在读取…" else label(s.getString("hdr_capture_mode", "native")), style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("保留 RGB 增益图", style = MaterialTheme.typography.titleMedium)
                    Text("保留红、绿、蓝独立增益，JPG 使用质量 100 的 4:4:4 编码，并跳过像素重分配。受支持系统安装新版底层模块后，会在截图源头计算 RGB 增益；此开关不会改变取帧方式。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s?.getBoolean("hdr_capture_rgb") == true, enabled = s != null && !busy,
                    onCheckedChange = { save(Bundle().apply { putBoolean("hdr_capture_rgb", it) }) })
            }
            Text("取帧设置下次截图生效，底层算法更新需重启。可能增加截图耗时，仅保留系统实际提供的 HDR 信息。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(s != null) {
                val report = try { JSONObject(s.getString("hdr_capture_report", "")) } catch(_: Exception) { null }
                val status = when(report?.optString("status")) {
                    "rebuilt_hdr" -> "最近一次：已重建 HDR 增益图"
                    "captured_sdr" -> "最近一次：画面中未检测到有效 HDR 增益"
                    "native_fallback" -> "最近一次：实验路径未通过校验，已使用原有截图"
                    "unsupported_native_retained" -> "最近一次：实验路径暂不可用，保留原有截图"
                    "native_gainmap_retained" -> "最近一次：系统已返回增益图，保留原结果"
                    "neutral_gainmap_retained" -> "最近一次：已保留增益图，标准参数未体现增益；私有 HDR 尚未确认"
                    "failed" -> "最近一次：实验路径与回退均未成功"
                    "protected" -> "最近一次：受保护内容沿用系统处理"
                    "native" -> "最近一次：使用原有截图路径"
                    else -> "尚无本次版本的截屏诊断"
                }
                Text(status, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val latest = withContext(Dispatchers.IO) {
                            val data = context.contentResolver.call(uri,"hdr_capture_settings",null,null) ?: error("未收到诊断信息")
                            val body = JSONObject().put("version", "1.5.6-hdr-test").put("mode", data.getString("hdr_capture_mode"))
                                .put("rgb", data.getBoolean("hdr_capture_rgb")).put("captures", JSONArray(data.getString("hdr_capture_history", "[]"))).toString(2)
                            val name = "hdr-capture-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())}.json"
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MI-Screen-HDR-Enhanced/logs/"); put(MediaStore.MediaColumns.IS_PENDING, 1)
                            }
                            val file = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建诊断文件")
                            try {
                                context.contentResolver.openOutputStream(file)?.use { it.write(body.toByteArray()) } ?: error("无法写入诊断文件")
                                check(context.contentResolver.update(file, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0)
                            } catch(e: Exception) { context.contentResolver.delete(file, null, null); throw e }
                            data
                        }
                        state = latest
                        Toast.makeText(context,"已保存到 Download/MI-Screen-HDR-Enhanced/logs",Toast.LENGTH_LONG).show()
                    } catch(e: CancellationException) { throw e }
                      catch(e: Exception) { error = e.message ?: "诊断导出失败" }
                    finally { busy = false }
                }
            }) { Text("刷新并导出截屏诊断") }
            if(error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    if(dialog) WindowBlurDialog("特殊 HDR 取帧", "默认使用系统取帧。仅在需要测试时选择“匹配屏幕颜色”或“兼容取帧”；杜比等格式仍取决于系统能否提供完整画面。",
        "保存", "取消", confirmEnabled = !busy, content = {
            Column(Modifier.selectableGroup()) {
                listOf("native", "display", "compat").forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(selected = choice == mode, role = Role.RadioButton,
                        onClick = { choice = mode }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = choice == mode, onClick = null);Text(label(mode), Modifier.padding(start = 12.dp))
                    }
                }
            }
        }) { confirmed -> dialog = false; if(confirmed) save(Bundle().apply { putString("hdr_capture_mode", choice) }) }
}
