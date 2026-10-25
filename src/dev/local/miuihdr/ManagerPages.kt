/* SPDX-License-Identifier: GPL-3.0-only
 * Home, install, flash and about layouts adapted from SukiSU-Ultra (GPL-3.0),
 * commit df84920b20a925fd1135530f6e403361ad494d7b. See assets/UI-SOURCE.txt.
 * UI framework: Google / AndroidX Jetpack Compose Material 3 Expressive.
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.local.miuihdr

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val RELEASE = "1.5.2"
private const val PROJECT = "https://github.com/TIMENEXSakura/MI-Screen-HDR-Enhanced"

@Composable
private fun ManagerScaffold(title: String, onBack: (() -> Unit)? = null,
                            actions: @Composable RowScope.() -> Unit = {},
                            bottomSpace: Int = 24, collapse: Boolean = false,
                            content: @Composable ColumnScope.() -> Unit) {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    if (collapse) LaunchedEffect(behavior.state.heightOffsetLimit) {
        behavior.state.heightOffset = behavior.state.heightOffsetLimit
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(title = { Text(title) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                } }, actions = actions, scrollBehavior = behavior,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        }, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).nestedScroll(behavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            Spacer(Modifier.navigationBarsPadding().height(bottomSpace.dp))
        }
    }
}

@Composable
internal fun ManagerHome(onInstall: () -> Unit, onTest: () -> Unit, onSettings: () -> Unit,
                         onAbout: () -> Unit, onLog: () -> Unit) {
    val context = LocalContext.current
    var confirmReload by rememberSaveable { mutableStateOf(false) }
    val screenshotVersion = remember { try {
        context.packageManager.getPackageInfo("com.miui.screenshot", 0).versionName ?: "—"
    } catch (_: Exception) { "未检测到" } }
    val haptic = LocalHapticFeedback.current
    ManagerScaffold("小米截屏HDR增强", bottomSpace = 108, actions = {
        TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("重新加载系统截屏") } }, state = rememberTooltipState()) {
            IconButton(onClick = { if (!ScreenshotControl.busy) confirmReload = true },
                enabled = !ScreenshotControl.busy && !confirmReload) {
                if(ScreenshotControl.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.PowerSettingsNew, "重新加载系统截屏")
            }
        }
        IconButton(onClick = onAbout) { Icon(Icons.Default.Info, "关于") }
    }) {
        val foreground = MaterialTheme.colorScheme.onPrimary
        Surface(onClick = { haptic.performHapticFeedback(HapticFeedbackType.VirtualKey); onInstall() },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primary, contentColor = foreground) {
            Column(Modifier.fillMaxWidth().drawBehind {
                val color = foreground.copy(alpha = .18f)
                val center = Offset(size.width - 36.dp.toPx(), 32.dp.toPx())
                val stroke = 2.dp.toPx()
                drawCircle(color, 112.dp.toPx(), center, style = Stroke(stroke))
                drawCircle(color, 84.dp.toPx(), center, style = Stroke(stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 8.dp.toPx()))))
                drawCircle(color, 56.dp.toPx(), center, style = Stroke(stroke))
                drawCircle(color, 18.dp.toPx(), center)
            }.heightIn(min = 188.dp).padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Surface(color = foreground, contentColor = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(50)) {
                    Text("API 102", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (MountJob.busy) "正在${MountJob.actionName}" else if (MountJob.success) "${MountJob.actionName}完成" else "截屏增强",
                        style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (MountJob.busy) MountJob.phase else "点按安装或导出挂载模块",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeTile(Icons.Default.HdrOn, "HDR 测试", Modifier.weight(1f), onTest)
            HomeTile(Icons.Default.Tune, "增强设置", Modifier.weight(1f), onSettings)
        }
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceBright) {
            Column {
                InfoRow("模块版本", RELEASE)
                InfoRow("注入框架", "LSPosed · API 102")
                InfoRow("设备", Build.MODEL)
                InfoRow("系统版本", "Android ${Build.VERSION.RELEASE}")
                InfoRow("系统截屏", screenshotVersion, last = true)
            }
        }
        Segments {
            ManagerLink("任务记录", "查看安装与导出日志", Icons.Default.Terminal, true, onLog)
            ManagerLink("关于", "开源项目与许可", Icons.Default.Info, false, onAbout)
        }
    }
    if (confirmReload) WindowBlurDialog(
        title = "重新加载系统截屏",
        text = "将结束小米系统截屏进程，未保存的编辑会丢失。下次截图时会自动重新启动。",
        confirmText = "确认停止",
        dismissText = "取消"
    ) { confirmed ->
        confirmReload = false
        if (confirmed && !ScreenshotControl.busy) ScreenshotControl.stop(context)
    }
}

@Composable
private fun HomeTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceBright) {
        Column(Modifier.height(88.dp).padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!last) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}

@Composable
private fun Segments(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

@Composable
private fun ManagerLink(title: String, summary: String?, icon: ImageVector, first: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(topStart = if(first) 22.dp else 4.dp,
        topEnd = if(first) 22.dp else 4.dp, bottomStart = if(first) 4.dp else 22.dp,
        bottomEnd = if(first) 4.dp else 22.dp), color = MaterialTheme.colorScheme.surfaceBright) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(icon, null, Modifier.padding(10.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

@Composable
internal fun ManagerInstall(onBack: () -> Unit, onInstall: (Uri?) -> Unit) {
    val context = LocalContext.current
    var method by rememberSaveable { mutableIntStateOf(0) }
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var fileName by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) {
            selectedUri = uri.toString(); method = 1
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            fileName = try { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if(it.moveToFirst()) it.getString(0) else null
            } } catch (_: Exception) { null }
        }
    }
    ManagerScaffold("安装与导出", onBack, collapse = true) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InstallOption("自动识别并安装", "识别本机正在使用的运行库，生成并安装挂载模块。", method == 0) { method = 0 }
            InstallOption("选择 SO 文件并导出", "修补所选设备的原始库，导出 ZIP 包供对应设备安装。", method == 1) {
                method = 1; picker.launch(arrayOf("*/*"))
            }
        }
        if(method == 1) Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceBright,
            onClick = { picker.launch(arrayOf("*/*")) }) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(fileName ?: if(selectedUri != null) "已选择原始库" else "尚未选择文件", style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("点按重新选择", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceBright) {
            Column {
                if (method == 1) {
                    InfoRow("保存位置", "Download/MI-Screen-HDR-Enhanced")
                    InfoRow("使用范围", "原始库对应的设备及系统版本")
                    InfoRow("安装方式", "在目标设备的 Root 管理器中选择 ZIP", last = true)
                } else {
                    InfoRow("挂载方式", "自动适配当前 Root 环境")
                    InfoRow("原始运行库", "自动备份，异常时保留原库")
                    InfoRow("安装之后", "重启设备后加载", last = true)
                }
            }
        }
        Button(onClick = { onInstall(if(method == 1) Uri.parse(selectedUri!!) else null) },
            enabled = !MountJob.busy && (method == 0 || selectedUri != null), modifier = Modifier.fillMaxWidth()) {
            Text(if (method == 1) "修补并导出" else "安装")
        }
    }
}

@Composable
private fun InstallOption(title: String, summary: String, selected: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val border by animateColorAsState(if(selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "install_method_border")
    val background by animateColorAsState(if(selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceBright,
        label = "install_method_color")
    Surface(selected = selected, onClick = { haptic.performHapticFeedback(HapticFeedbackType.VirtualKey); onClick() },
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = background, border = BorderStroke(2.dp, border)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = null)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ManagerFlash(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val snapshot = MountJob.text
    var exportText by remember { mutableStateOf("") }
    var preparingExport by remember { mutableStateOf(false) }
    val displayedProgress by animateFloatAsState(MountJob.progress / 100f,
        animationSpec = tween(220), label = "installation_progress")
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if(uri != null) scope.launch {
            val error = withContext(Dispatchers.IO) { try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportText) }
                    ?: error("无法创建日志文件")
                null
            } catch(e: Exception) { e.message ?: "写入失败" } }
            Toast.makeText(context, error ?: "日志已保存", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(snapshot, scroll.maxValue) { if(MountJob.busy) scroll.animateScrollTo(scroll.maxValue) }
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { TopAppBar(title = { Text(if(MountJob.busy) "正在${MountJob.actionName}" else if(MountJob.success) "${MountJob.actionName}成功" else "${MountJob.actionName}记录") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = { IconButton(onClick = {
                preparingExport = true
                scope.launch {
                    try {
                        exportText = withContext(Dispatchers.IO) { MountJob.exportLog(context) }
                        save.launch("MI-Screen-HDR-Enhanced-$RELEASE-${MountJob.mode}.txt")
                    } catch (e: Exception) {
                        Toast.makeText(context, "读取完整日志失败：${e.message}", Toast.LENGTH_LONG).show()
                    } finally { preparingExport = false }
                }
            }, enabled = snapshot.isNotEmpty() && !preparingExport) { Icon(Icons.Default.Save, "保存完整日志") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) },
        floatingActionButton = { if(!MountJob.busy) ExtendedFloatingActionButton(onClick = onBack,
            icon = { Icon(Icons.Default.Check, null) }, text = { Text("完成") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(MountJob.busy || MountJob.status != null) {
                LinearProgressIndicator(progress = { displayedProgress }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(MountJob.phase, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text("${MountJob.progress}%", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if(!MountJob.busy && MountJob.status != null) Text(MountJob.status!!, style = MaterialTheme.typography.bodyMedium)
            if(!MountJob.busy && MountJob.success && MountJob.isExport && MountJob.generatedZip != null) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text("保存位置：${MountJob.generatedZip}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(MountJob.truncated) Text("当前显示日志末尾；右上角可保存完整记录。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceBright, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(if(snapshot.isEmpty()) "还没有任务记录。\n从主页状态卡进入安装与导出。" else snapshot,
                        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).padding(bottom = 80.dp),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun ManagerAbout(onBack: () -> Unit) {
    val context = LocalContext.current
    ManagerScaffold("关于", onBack) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary) {
            Column(Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.onPrimary,
                    contentColor = MaterialTheme.colorScheme.primary) {
                    Icon(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(64.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("小米截屏HDR增强", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                    Text("MI Screen HDR Enhanced", style = MaterialTheme.typography.bodyMedium)
                    Text("$RELEASE · LSPosed API 102", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Segments {
            ManagerLink("开源项目", "源代码、构建说明与更新", Icons.Default.Code, true) { openLink(context, PROJECT) }
            ManagerLink("反馈问题", "提交日志与设备信息", Icons.Default.BugReport, false) { openLink(context, "$PROJECT/issues") }
        }
        Text("开源许可与致谢", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceBright) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Credit("Google / AndroidX", "界面框架与设计规范：Jetpack Compose Material 3 Expressive（Apache 2.0）。")
                Credit("SukiSU-Ultra", "主页、安装流程、日志页、关于页及主题和导航代码的改编来源（GPL-3.0）。本项目独立维护。")
                Credit("LSPosed", "模块注入接口：API 102。接口依赖及许可见开源项目。")
                Credit("MaterialKolor · libjpeg-turbo", "动态主题配色与高质量 JPEG 编码；许可详见开源项目。")
                Credit("HDR 测试照片", "Mishaal Rahman · Ultra HDR Samples（CC BY 4.0）。")
            }
        }
        Segments {
            ManagerLink("GPL-3.0 开源许可", "本应用对应源码及第三方声明", Icons.Default.Description, true) {
                openLink(context, "$PROJECT/blob/v$RELEASE/LICENSE")
            }
            ManagerLink("界面代码来源", "SukiSU-Ultra · 已固定源代码版本", Icons.Default.OpenInNew, false) {
                openLink(context, "https://github.com/SukiSU-Ultra/SukiSU-Ultra/tree/df84920b20a925fd1135530f6e403361ad494d7b/manager/app/src/main/java/com/sukisu/ultra/ui")
            }
        }
    }
}

@Composable
private fun Credit(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openLink(context: Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    catch(_: Exception) { Toast.makeText(context, "没有可打开链接的浏览器", Toast.LENGTH_SHORT).show() }
}
