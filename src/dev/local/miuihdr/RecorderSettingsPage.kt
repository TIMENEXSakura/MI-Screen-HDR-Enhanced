package dev.local.miuihdr

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*

@Composable
internal fun RecorderSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    var resumed by remember { mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { owner, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    var state by remember { mutableStateOf<Bundle?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(resumed, saving) {
        while (resumed && !saving) {
            try {
                withContext(Dispatchers.IO) { RecorderCommunication.settings(context, true) }
                state = RecorderCommunication.current()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { error = e.message ?: "录屏设置读取失败" }
            delay(2000)
        }
    }
    fun save(option: String, selected: String) {
        saving = true; error = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (option == "system") RecorderCommunication.saveSystem(context, selected)
                    else RecorderCommunication.save(context, Bundle().apply {
                        when (option) {
                            "timeout_ms" -> putInt(option, selected.toInt())
                            "timeout_fatal" -> putBoolean(option, selected == "stop")
                            else -> putString(option, selected)
                        }
                    })
                }
                state = RecorderCommunication.current()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { error = e.message ?: "设置保存失败，请重试" }
            finally { saving = false }
        }
    }
    val s = state
    val enabled = s != null && !saving && !s.getBoolean("recording")
    val systemEnabled = enabled && s?.getBoolean("system_saving") == false &&
        s.getBoolean("root_fresh") && s.getString("root_stage") == "ready"
    val vulkan = s?.getString("backend") == "vulkan"
    fun open(option: String, current: String) { choice = current; dialog = option }
    ManagerScaffold("录屏设置", onBack) {
        if (s == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在读取录屏设置…", style = MaterialTheme.typography.bodyMedium)
        } else {
            RecorderSection("录制") {
                RecorderChoice("录屏后端", RecorderOptions.label(s.getString("backend", "gles")), enabled) {
                    open("backend", s.getString("backend", "gles"))
                }
                Text("下次录屏生效，与小米录屏设置同步。Vulkan 为实验功能。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RecorderSection("系统合成") {
                RecorderChoice("系统合成后端", if (s.getBoolean("system_saving")) "正在保存…" else
                    "已选择：${RecorderOptions.label(s.getString("selected", "default"))}", systemEnabled) {
                    open("system", s.getString("selected", "default"))
                }
                Text("实际运行：${RecorderOptions.label(s.getString("actual", "unknown"))}", style = MaterialTheme.typography.bodyMedium)
                Text("影响整个系统，更改后需手动重启手机。需先连接底层模块。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val message = s.getString("message", "")
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                val failure = s.getString("selection_error", "")
                if (failure.isNotBlank()) Text(failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            RecorderSection("特殊设置 · Vulkan") {
                RecorderChoice("输入帧等待时间", RecorderOptions.waitLabel(s.getInt("timeout_ms", 2000)), enabled && vulkan) {
                    open("timeout_ms", s.getInt("timeout_ms", 2000).toString())
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("超时继续录屏", style = MaterialTheme.typography.titleMedium)
                        Text("跳过等待超时的输入帧，继续当前录制。默认开启。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = !s.getBoolean("timeout_fatal"), enabled = enabled && vulkan,
                        onCheckedChange = { save("timeout_fatal", if (it) "continue" else "stop") })
                }
                Text("仅对 Vulkan 生效，下次录屏应用。关闭后，输入帧等待超时会结束录制。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RecorderSection("运行状态") {
                Text(RecorderCommunication.recordingLabel(s), style = MaterialTheme.typography.bodyMedium)
                Text(s.getString("root_status", "等待底层模块连接"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val timeouts = RecorderCommunication.timeoutSummary(s)
                if (timeouts.isNotEmpty()) Text(timeouts, style = MaterialTheme.typography.bodySmall)
                if (s.getBoolean("recording")) Text("录制期间暂时无法修改设置。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    if (dialog.isNotEmpty()) {
        val option = dialog
        val options = when (option) {
            "backend" -> listOf("gles" to "OpenGL ES（小米原生）", "vulkan" to "Vulkan（实验）")
            "system" -> listOf("default" to "系统默认", "gles" to "OpenGL ES", "vulkan" to "Vulkan")
            else -> listOf("500" to "0.5 秒", "1000" to "1 秒", "2000" to "2 秒（默认）", "5000" to "5 秒", "10000" to "10 秒")
        }
        WindowBlurDialog(title = when (option) { "backend" -> "录屏后端"; "system" -> "系统合成后端"; else -> "输入帧等待时间" },
            text = when (option) { "system" -> "保存后需手动重启手机生效。"; "backend" -> "下次录屏生效。"; else -> "等待画面就绪的最长时间。超过此时间后按超时设置处理。" },
            confirmText = "保存", dismissText = "取消", confirmEnabled = if (option == "system") systemEnabled else enabled,
            content = {
                Column(Modifier.selectableGroup()) {
                    options.forEach { (code, label) ->
                        Row(Modifier.fillMaxWidth().selectable(selected = choice == code, role = Role.RadioButton,
                            onClick = { choice = code }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = choice == code, onClick = null)
                            Text(label, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }) { confirmed ->
                dialog = ""
                if (confirmed) save(option, choice)
            }
    }
}

@Composable
private fun RecorderSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceBright) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun RecorderChoice(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surfaceBright) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}
