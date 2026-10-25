package dev.local.miuihdr

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import java.util.function.Consumer
import kotlin.math.roundToInt

/** Android window blur and the dialog share one reversible animation timeline. */
@Composable
internal fun WindowBlurDialog(title: String, text: String, confirmText: String,
                              dismissText: String? = null, confirmEnabled: Boolean = true,
                              content: (@Composable ColumnScope.() -> Unit)? = null,
                              onResult: (Boolean) -> Unit) {
    var decision by remember { mutableStateOf<Boolean?>(null) }
    val progress = remember { Animatable(0f) }
    val result by rememberUpdatedState(onResult)
    var closingResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    fun decide(confirmed: Boolean) {
        if (decision == null) { closingResult = result; decision = confirmed }
    }
    Dialog(onDismissRequest = { decide(false) },
        properties = DialogProperties(usePlatformDefaultWidth = true)) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val manager = remember(view) { view.context.getSystemService(WindowManager::class.java) }
        var blurEnabled by remember(manager) { mutableStateOf(manager?.isCrossWindowBlurEnabled == true) }
        val radius = (28 * view.resources.displayMetrics.density).roundToInt()
        DisposableEffect(window, manager) {
            window?.setWindowAnimations(0)
            window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND or WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.attributes = window?.attributes?.apply { blurBehindRadius = 0; dimAmount = 0f }
            val listener = Consumer<Boolean> { blurEnabled = it }
            manager?.addCrossWindowBlurEnabledListener(listener)
            onDispose { manager?.removeCrossWindowBlurEnabledListener(listener) }
        }
        val fraction = progress.value
        SideEffect {
            window?.attributes = window?.attributes?.apply {
                blurBehindRadius = if (blurEnabled) (radius * fraction).roundToInt() else 0
                dimAmount = (if (blurEnabled) .18f else .4f) * fraction
            }
        }
        LaunchedEffect(decision) {
            val closing = decision != null
            progress.animateTo(if (closing) 0f else 1f,
                tween(if (closing) 180 else 280, easing = FastOutSlowInEasing))
            if (closing) closingResult?.invoke(decision == true)
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().graphicsLayer {
                alpha = progress.value; scaleX = .96f + .04f * progress.value; scaleY = scaleX
            }) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    content?.invoke(this)
                }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dismissText != null) TextButton(enabled = decision == null,
                        onClick = { decide(false) }) { Text(dismissText) }
                    TextButton(enabled = decision == null && confirmEnabled,
                        onClick = { decide(true) }) { Text(confirmText) }
                }
            }
        }
    }
}
