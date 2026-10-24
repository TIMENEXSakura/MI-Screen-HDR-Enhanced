@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.local.miuihdr

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.WindowInsets as PlatformInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import dev.local.miuihdr.theme.Shapes as HdrShapes
import dev.local.miuihdr.theme.Typography as HdrTypography
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.TimeUnit

/** UI framework: Google / AndroidX Jetpack Compose Material 3 Expressive. */
class SettingsActivity : ComponentActivity() {
    private var page by mutableIntStateOf(0)
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        page = saved?.getInt("page") ?: intent.getIntExtra("page", 0)
        setContent { HdrTheme { ModuleUi() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (intent.hasExtra("page")) page = intent.getIntExtra("page", 0) }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("page", page) }

    @Composable private fun HdrTheme(content: @Composable () -> Unit) {
        val dark = isSystemInDarkTheme()
        val system = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
        // SukiSU MaterialTheme.kt / ThemeExt.kt: dynamic seed, TonalSpot, 2025 spec,
        // expressive motion and the same Shape.kt / Type.kt definitions.
        val colors = rememberDynamicColorScheme(seedColor = system.primary, isDark = dark,
            style = PaletteStyle.TonalSpot, specVersion = ColorSpec.SpecVersion.SPEC_2025)
        SideEffect { WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
        } }
        MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(),
            shapes = HdrShapes, typography = HdrTypography, content = content)
    }

    @Composable private fun ModuleUi() {
        var full by rememberSaveable { mutableStateOf(false) }
        BackHandler(page != 0 || full) { if (full) full = false else page = 0 }
        Surface(Modifier.fillMaxSize(), color = if (full) Color.Black else MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                when (page) {
                    1 -> TestPage(full, { full = it })
                    2 -> SettingsPage()
                    else -> HomePage()
                }
                if (!full) FloatingNavigation(page, { page = it }, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
    @Composable private fun PageColumn(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(horizontal = 16.dp).padding(top = 34.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
    @Composable private fun HomePage() = PageColumn(getString(R.string.app_name)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(32.dp)) {
            Box(Modifier.fillMaxWidth().height(218.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = androidx.compose.ui.geometry.Offset(size.width * .91f, size.height * .22f)
                    for (r in listOf(40f, 77f, 115f)) drawCircle(Color.White.copy(alpha = .12f), r.dp.toPx(), center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Surface(shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.onPrimary) {
                        Text("ULTRA HDR", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                    Column {
                        Text("保留高光", fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("原生截屏 · HDR JPG", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeTile("HDR", "测试与对照", Icons.Default.Image, Modifier.weight(1f)) { page = 1 }
            HomeTile("光效", "预览与设置", Icons.Default.AutoAwesome, Modifier.weight(1f)) { page = 2 }
        }
        Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("从截屏到预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("识别有效 HDR 后，流动光带沿截图轮廓泛光，随图片缩小、移动和退出。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("带壳按钮循环切换 JPG、透明 PNG HDR 和关闭；内置 HDR 测试与截图检查。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { page = 1 }) { Text("打开 HDR 测试"); Icon(Icons.Default.ArrowForward, null, Modifier.padding(start = 8.dp).size(18.dp)) }
            }
        }
        Text("1.3.2  ·  小米原生截屏增强", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
    }
    @Composable private fun HomeTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, action: () -> Unit) {
        Card(onClick = action, modifier = modifier, shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(14.dp)); Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    @Composable private fun SettingsPage() {
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        val stored = getSharedPreferences("local_status", MODE_PRIVATE)
        var status by remember { mutableStateOf(stored.getString("result", null)?.takeUnless { it.contains("Cannot run program") }
            ?: "需要在 Root 管理器授予本应用权限，并在 LSPosed 中启用“小米截屏”作用域。") }
        var about by remember { mutableStateOf(false) }
        PageColumn("设置") {
            SectionTitle("预览效果")
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    EffectSwitch("辉光边框", "彩色光带沿轮廓流动，全程跟随截图动画。", "glow")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EffectSwitch("辉光 HDR 亮度", "关闭后保留普通亮度泛光，截图仍保存 HDR。", "hdr_glow")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EffectSwitch("HDR 标记", "在有效 HDR 截图上显示标记。", "badge")
                }
            }
            Text("下次截图立即生效。预览光效不会写入保存的图片。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            SectionTitle("HDR 捕获")
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("底层修复", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    fun generateMount(install: Boolean) { busy = true; scope.launch {
                        status = if (install) "正在生成并安装挂载模块…" else "正在读取本机库并生成挂载模块…"
                        val result = withContext(Dispatchers.IO) { enableRoot(install) }
                        status = result; stored.edit().putString("result", result).apply(); busy = false
                    } }
                    Button(onClick = { generateMount(false) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                        if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                        Text(if (busy) "正在处理" else "生成挂载模块")
                    }
                    OutlinedButton(onClick = { generateMount(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("生成并安装挂载模块") }
                    Text("按本机库生成 Magisk / KernelSU / APatch 模块，沿用已有挂载方式，保存到 Download/MI-Screen-HDR-Enhanced。包含原库备份和异常回退。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { try { startActivity(Intent().setClassName("org.lsposed.manager", "org.lsposed.manager.ui.activity.MainActivity")) }
                        catch (_: Exception) { status = "请从你的 LSPosed 管理器启用本模块，仅勾选“小米截屏”。" } }, modifier = Modifier.fillMaxWidth()) { Text("打开 LSPosed") }
                    Text("作用域：小米截屏 / com.miui.screenshot。通过 SO 注入增强捕获，系统截屏 APK 保持原样。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { about = true }) { Icon(Icons.Default.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("UI 框架与第三方许可") }
        }
        if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("框架与许可") },
            text = { Text("UI 框架\nGoogle / AndroidX Jetpack Compose Material 3\n设计规范：Google Material 3 Expressive\n组件库许可：Apache-2.0\ndeveloper.android.com/jetpack/androidx/releases/compose-material3\n\n第三方代码\n部分主题配置与浮动导航代码改编自 SukiSU-Ultra，遵循 GPL-3.0。这是所复用代码的出处，不是 UI 框架的来源。详细路径、版本和许可见随包源码。\n\nHDR 素材\nMishaal Rahman · CC BY 4.0\ngithub.com/MishaalRahmanGH/Ultra_HDR_Samples") },
            confirmButton = { TextButton(onClick = { about = false }) { Text("知道了") } })
    }
    @Composable private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp)) }
    @Composable private fun EffectSwitch(title: String, desc: String, key: String) {
        val prefs = getSharedPreferences("ui_options", MODE_PRIVATE)
        var checked by remember { mutableStateOf(prefs.getBoolean(key, true)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp)); Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(checked = checked, onCheckedChange = { if (prefs.edit().putBoolean(key, it).commit()) checked = it },
                thumbContent = if (checked) { { Icon(Icons.Default.Check, null, Modifier.size(SwitchDefaults.IconSize)) } } else null)
        }
    }
    private fun enableRoot(install: Boolean): String = try {
        fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val apk = applicationInfo.sourceDir
        val cmd = "export PATH=/system/bin:/system/xbin:/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk; CLASSPATH=${quote(apk)} /system/bin/app_process /system/bin dev.local.miuihdr.RootSetup ${quote(apk)} ${if (install) "install" else "generate"}"
        val log = java.io.File.createTempFile("mount-", ".log", cacheDir)
        // Installers can print more than a pipe buffer; never wait before draining a pipe.
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).redirectOutput(log).start()
        if (!p.waitFor(180, TimeUnit.SECONDS)) { "尚未确认操作完成，请先查看 Root 管理器状态，避免重复安装。" }
        else { val detail = java.io.RandomAccessFile(log, "r").use { file ->
                val bytes = ByteArray(minOf(file.length(), 64000L).toInt())
                file.seek(file.length() - bytes.size); file.readFully(bytes); String(bytes, Charsets.UTF_8)
            }
            log.delete()
            getSharedPreferences("local_status", MODE_PRIVATE).edit().putString("detail", detail).apply()
            if (p.exitValue() == 0) { if (install) "挂载模块已安装，原库已备份。下次启动验证，异常时回退。" else "挂载模块已生成：\n" + detail.lineSequence().firstOrNull { it.startsWith("GENERATED_ZIP=") }?.removePrefix("GENERATED_ZIP=") } else "启用未完成：${detail.takeLast(900)}"
        }
    } catch (_: java.io.IOException) { "请先在 Magisk / KernelSU / APatch 中授予“${getString(R.string.app_name)}”Root 权限，然后重试。" }
      catch (e: Exception) { "启用未完成：${e.message}" }

    @Composable private fun TestPage(full: Boolean, setFull: (Boolean) -> Unit) {
        var source by rememberSaveable { mutableIntStateOf(0) }
        var mode by rememberSaveable { mutableIntStateOf(1) }
        var report by remember { mutableStateOf<String?>(null) }
        var loadingError by remember { mutableStateOf<String?>(null) }
        var inspecting by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val images by produceState<Array<Bitmap>?>(null) {
            try { value = withContext(Dispatchers.IO) { HdrTestAssets.load(this@SettingsActivity) } }
            catch (e: Exception) { loadingError = e.message }
        }
        val hdr = images?.get(if (source == 0) 0 else 2)
        val sdr = images?.get(if (source == 0) 1 else 3)
        val ratio = hdr?.gainmap?.displayRatioForFullHdr ?: 1f
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
            inspecting = true
            report = withContext(Dispatchers.IO) { try { HdrTestAssets.inspect(this@SettingsActivity, uri) } catch (e: Exception) { "检查失败：${e.message}" } }
            inspecting = false
        } }
        DisposableEffect(mode, ratio) {
            window.colorMode = if (mode == 0) ActivityInfo.COLOR_MODE_DEFAULT else ActivityInfo.COLOR_MODE_HDR
            if (android.os.Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(if (mode == 0) 1f else ratio)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT; if (android.os.Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(1f); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        DisposableEffect(full) {
            if (full) { window.insetsController?.apply { systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; hide(PlatformInsets.Type.systemBars()) } }
            else window.insetsController?.show(PlatformInsets.Type.systemBars())
            onDispose { window.insetsController?.show(PlatformInsets.Type.systemBars()) }
        }
        Column(Modifier.fillMaxSize().then(if (full) Modifier.background(Color.Black) else Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 22.dp, bottom = 106.dp)), verticalArrangement = Arrangement.spacedBy(if (full) 0.dp else 10.dp)) {
            if (!full) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("HDR 测试", style = MaterialTheme.typography.headlineLarge)
                    IconButton(onClick = { report = "云层与阳光：Mishaal Rahman · CC BY 4.0\nhttps://github.com/MishaalRahmanGH/Ultra_HDR_Samples\nhttps://creativecommons.org/licenses/by/4.0/\n\n使用原始 Ultra HDR JPEG。亮度阶梯由模块生成，线性亮度比例为 1×、2×、4×。" }) { Icon(Icons.Default.Info, "素材来源") }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("云层与阳光", "亮度阶梯").forEachIndexed { i, text -> SegmentedButton(selected = source == i, onClick = { source = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(text) } }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("SDR", "HDR", "左右对照").forEachIndexed { i, text -> SegmentedButton(selected = mode == i, onClick = { mode = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(text) } }
                }
            }
            Box(Modifier.fillMaxWidth().then(if (full) Modifier.weight(1f) else Modifier), contentAlignment = Alignment.Center) {
                if (hdr == null) {
                    Surface(Modifier.fillMaxWidth().aspectRatio(4f / 3f), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Box(contentAlignment = Alignment.Center) { if (loadingError != null) Text("素材加载失败：$loadingError") else CircularProgressIndicator() }
                    }
                } else if (mode == 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PreviewUnit("SDR", sdr!!, source == 0, full, Modifier.weight(1f)) { setFull(!full) }
                        PreviewUnit("HDR", hdr, source == 0, full, Modifier.weight(1f)) { setFull(!full) }
                    }
                } else PreviewUnit(if (mode == 0) "SDR" else "HDR", if (mode == 0) sdr!! else hdr,
                    false, full, Modifier.fillMaxWidth()) { setFull(!full) }
            }
            if (!full) {
                Text(if (mode == 0) "当前显示 SDR 底图" else if (mode == 2) "同一区域对照 · 左 SDR / 右 HDR" else String.format(Locale.ROOT, "真实 HDR · 素材增益上限 %.2f×", ratio), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("点图全屏，用系统按键截图，再选取截图检查增益图。实际高光亮度受系统当前 HDR 余量影响。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { setFull(true) }, Modifier.weight(1f)) { Icon(Icons.Default.Fullscreen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("全屏测试") }
                    Button(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !inspecting, modifier = Modifier.weight(1f)) { Text(if (inspecting) "正在检查" else "检查截图") }
                }
            }
        }
        if (report != null) AlertDialog(onDismissRequest = { report = null }, title = { Text("HDR 测试") }, text = { Text(report!!) }, confirmButton = { TextButton(onClick = { report = null }) { Text("完成") } })
    }
}

/** An image-sized preview unit. Comparison units use identical highlight crops. */
@Composable private fun PreviewUnit(label: String, image: Bitmap, focus: Boolean, full: Boolean, modifier: Modifier, click: () -> Unit) {
    val crop = remember(image, focus) {
        if (focus) android.graphics.Rect((image.width * .28f).toInt(), (image.height * .06f).toInt(),
            (image.width * .48f).toInt(), (image.height * .54f).toInt())
        else android.graphics.Rect(0, 0, image.width, image.height)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = if (full) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp))
        AndroidView(factory = { HdrPreview(it) }, update = { v ->
            v.bitmap = image; v.sourceRect = crop; v.contentDescription = "$label 预览，点按切换全屏"
            v.setOnClickListener { click() }; v.invalidate()
        }, modifier = Modifier.fillMaxWidth().aspectRatio(crop.width().toFloat() / crop.height()).clip(RoundedCornerShape(24.dp)))
    }
}

private class HdrPreview(context: android.content.Context) : View(context) {
    var bitmap: Bitmap? = null
    var sourceRect: android.graphics.Rect? = null
    private val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
    override fun onDraw(c: AndroidCanvas) {
        val image = bitmap ?: return
        c.drawBitmap(image, sourceRect, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
    }
}

/** Adapted from SukiSU-Ultra BottomBarMaterial.kt, GPL-3.0, df84920b. */
@Composable private fun FloatingNavigation(selected: Int, select: (Int) -> Unit, modifier: Modifier) {
    val items = listOf("首页" to Icons.Default.Home, "测试" to Icons.Default.Image, "设置" to Icons.Default.Settings)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 480.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp).selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { index, item ->
                    val active = selected == index
                    val bg by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, label = "nav_bg")
                    val fg by animateColorAsState(if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "nav_fg")
                    Row(Modifier.height(48.dp).clip(CircleShape).background(bg).selectable(active, role = Role.Tab, onClick = { select(index) }).animateContentSize().padding(horizontal = if (active) 18.dp else 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(item.second, if (active) null else item.first, tint = fg)
                        AnimatedVisibility(active, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) { Text(item.first, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
