package dev.local.miuihdr

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal

@Composable
internal fun CaptureRatioSettings() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("ui_options", Context.MODE_PRIVATE) }
    var automatic by remember { mutableStateOf(prefs.getBoolean("capture_ratio_auto", true)) }
    var mode by remember { mutableStateOf(prefs.getString("capture_ratio_mode",
        if (!automatic || prefs.contains("capture_ratio")) "force" else "offset").let { if (it == "offset") "offset" else "force" }) }
    var offset by remember { mutableFloatStateOf(prefs.getFloat("capture_ratio_offset", 0f)) }
    var forced by remember { mutableFloatStateOf(prefs.getFloat("capture_ratio", 4f)) }
    var editing by rememberSaveable { mutableStateOf(false) }
    fun failed() { Toast.makeText(context, "保存失败，请重试。", Toast.LENGTH_SHORT).show() }
    Text("HDR 捕获比例", style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("调整 HDR 比例", style = MaterialTheme.typography.titleMedium)
                    Text(if (automatic) "自动：保留原始比例，异常时参考当前屏幕。"
                        else if (mode == "offset") "当前比例 ${signedRatio(offset)} 倍"
                        else "强制使用 ${formatRatio(forced)}×",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = !automatic, onCheckedChange = { enabled ->
                    if (enabled) editing = true
                    else if (prefs.edit().putBoolean("capture_ratio_auto", true).commit()) automatic = true
                    else failed()
                })
            }
            if (!automatic) TextButton(onClick = { editing = true }) { Text("调整方式与数值") }
            Text("下次截图生效。仅调整已有增益图的亮度范围；缺失的高光数据无法通过倍率恢复。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (editing) CaptureRatioDialog(mode, offset, forced) { selected, adjustment, fixed ->
        if (selected == null) editing = false
        else if (prefs.edit().putBoolean("capture_ratio_auto", false)
                .putString("capture_ratio_mode", selected).putFloat("capture_ratio_offset", adjustment)
                .putFloat("capture_ratio", fixed).commit()) {
            mode = selected; offset = adjustment; forced = fixed; automatic = false; editing = false
        } else { editing = false; failed() }
    }
}

@Composable
private fun CaptureRatioDialog(initialMode: String, initialOffset: Float, initialForced: Float,
                               onResult: (String?, Float, Float) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var offset by rememberSaveable { mutableStateOf(formatRatio(initialOffset)) }
    var forced by rememberSaveable { mutableStateOf(formatRatio(initialForced)) }
    val relative = mode == "offset"
    val input = if (relative) offset else forced
    val value = input.toFloatOrNull()
    val low = if (relative) -9999f else 1f
    val high = if (relative) 9999f else 10000f
    val valid = value != null && value.isFinite() && value in low..high
    // Immutable values let the dialog commit exactly what was confirmed before its exit animation.
    val selectedMode = mode
    val selectedOffset = if (relative && valid) value!! else initialOffset
    val selectedForced = if (!relative && valid) value!! else initialForced
    fun update(text: String) { if (relative) offset = text else forced = text }
    WindowBlurDialog(title = "调整 HDR 比例", text = "选择调整方式，应用后从下一次截图开始生效。",
        confirmText = "应用", dismissText = "取消", confirmEnabled = valid,
        content = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RatioMode("offset", mode, "当前比例加减", "每次以本次捕获比例为基准，加上或减去设定值。") { mode = it }
                RatioMode("force", mode, "强制指定比例", "每次使用固定的 HDR / SDR 比例。") { mode = it }
            }
            OutlinedTextField(value = input, onValueChange = ::update, singleLine = true,
                label = { Text(if (relative) "增减值（倍）" else "固定 HDR / SDR 比例") },
                supportingText = { Text(if (!valid) "请输入 $low 至 $high 之间的有限数值。"
                    else if (relative) "例如当前为 4×，+1 得到 5×，−1 得到 3×。结果最低为 1×。"
                    else "例如设为 4×，截图将始终使用 4×。") },
                isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { update(formatRatio((value!! - .25f).coerceAtLeast(low))) },
                    enabled = valid && value!! > low) {
                    Icon(Icons.Default.Remove, "减小 0.25 倍", Modifier.size(18.dp)); Text(" 0.25")
                }
                OutlinedButton(onClick = { update(formatRatio((value!! + .25f).coerceAtMost(high))) },
                    enabled = valid && value!! < high) {
                    Icon(Icons.Default.Add, "增加 0.25 倍", Modifier.size(18.dp)); Text(" 0.25")
                }
            }
        }) { confirmed ->
        onResult(if (confirmed && valid) selectedMode else null, selectedOffset, selectedForced)
    }
}

@Composable
private fun RatioMode(id: String, selected: String, title: String, description: String,
                      onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected == id, role = Role.RadioButton,
        onClick = { onSelect(id) }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected == id, onClick = null)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatRatio(value: Float): String = if (value.isFinite()) BigDecimal(value.toString()).stripTrailingZeros().toPlainString() else value.toString()
private fun signedRatio(value: Float): String = if (value >= 0) "+${formatRatio(value)}" else formatRatio(value)
