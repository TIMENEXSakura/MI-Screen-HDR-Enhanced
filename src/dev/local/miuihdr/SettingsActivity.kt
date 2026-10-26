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
    private var aboutReturn by mutableIntStateOf(0)
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        page = saved?.getInt("page") ?: intent.getIntExtra("page", 0)
        aboutReturn = saved?.getInt("about_return") ?: 0
        MountJob.restore(applicationContext)
        setContent { HdrTheme { ModuleUi() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (intent.hasExtra("page")) page = intent.getIntExtra("page", 0) }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("page", page); out.putInt("about_return", aboutReturn) }

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
        fun back() { if (full) full = false else page = when (page) { 5 -> aboutReturn; 6 -> 2; else -> 0 } }
        BackHandler(page != 0 || full) { back() }
        Surface(Modifier.fillMaxSize(), color = if (full) Color.Black else MaterialTheme.colorScheme.surfaceContainer) {
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(targetState = page, transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { direction * it / 5 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -direction * it / 5 } + fadeOut())
                }, label = "manager_page") { destination ->
                    when (destination) {
                        1 -> TestPage(full, { full = it })
                        2 -> SettingsPage()
                        3 -> ManagerInstall(onBack = { back() }, onInstall = { uri -> MountJob.start(applicationContext,uri); page = 4 })
                        4 -> ManagerFlash(onBack = { back() })
                        5 -> ManagerAbout(onBack = { back() })
                        6 -> RecorderSettingsPage(onBack = { back() })
                        else -> ManagerHome(onInstall = { page = if(MountJob.busy) 4 else 3 }, onTest = { page = 1 },
                            onSettings = { page = 2 }, onAbout = { aboutReturn = 0; page = 5 }, onLog = { page = 4 })
                    }
                }
                if (!full && page <= 2) FloatingNavigation(page, { page = it }, Modifier.align(Alignment.BottomCenter))
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
    @Composable private fun SettingsPage() {
        PageColumn("设置") {
            SectionTitle("预览效果")
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    EffectSwitch("辉光边框", "彩色光带沿轮廓流动，全程跟随截图动画。", "glow")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EffectSwitch("辉光 HDR 亮度", "关闭后保留普通亮度泛光，预览图和保存图片仍为 HDR。", "hdr_glow")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EffectSwitch("HDR 标记", "在有效 HDR 截图上显示标记。", "badge")
                }
            }
            Text("HDR 预览独立启用，关闭辉光不影响图片的 HDR 显示。预览光效不会写入保存的图片。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            CaptureRatioSettings()
            SectionTitle("HDR 图像质量")
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    EffectSwitch("像素增益优化", "逐像素重算增益；JPG 使用全尺寸增益图、质量 100 和 4:4:4 编码。实验功能，默认关闭。", "pixel_precision", false)
                }
            }
            Text("下次截图生效。SDR 观感会变化，保存耗时和 JPG 体积可能增加。JPG 仍有压缩误差；不支持的输入自动沿用原流程。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            SectionTitle("录屏")
            Card(onClick = { page = 6 }, shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Videocam, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text("录屏设置", style = MaterialTheme.typography.titleMedium)
                        Text("录制后端、系统合成与特殊设置", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, "进入录屏设置")
                }
            }
            TextButton(onClick = { aboutReturn = 2; page = 5 }) { Icon(Icons.Default.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("关于小米截屏HDR增强") }
        }
    }
    @Composable private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp)) }
    @Composable private fun EffectSwitch(title: String, desc: String, key: String, defaultValue: Boolean = true) {
        val prefs = getSharedPreferences("ui_options", MODE_PRIVATE)
        var checked by remember { mutableStateOf(prefs.getBoolean(key, defaultValue)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp)); Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(checked = checked, onCheckedChange = { if (prefs.edit().putBoolean(key, it).commit()) checked = it },
                thumbContent = if (checked) { { Icon(Icons.Default.Check, null, Modifier.size(SwitchDefaults.IconSize)) } } else null)
        }
    }
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
        if (report != null) WindowBlurDialog("HDR 测试", report!!, "完成") { report = null }
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
