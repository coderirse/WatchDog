package io.github.coderirse.watchdog.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** 进场动画时长（ms）。 */
private const val ENTER_DURATION_MS = 160

/** 退场动画时长（ms）+ 其后的收尾缓冲。 */
private const val EXIT_DURATION_MS = 120
private const val EXIT_SETTLE_MS = 20

/**
 * 对话框关闭器：由 [AnimatedDialog] 注入给按钮作用域。
 *
 * 调用方通过 [dismiss] 关闭对话框——它**不会立即销毁组合**，而是先播放退场动画，
 * 动画结束后才执行 [afterExit]（保存等副作用）并回调 `onDismissRequest`。
 * 这解决了原先 `if (flag) Dialog(...)` 控制显隐时退场动画来不及播完的问题，
 * 也避免了在 ViewModel 里引入"两步 dismiss"状态。
 */
interface AnimatedDialogCloser {
    /** @param afterExit 退场动画结束后执行的操作（如保存输入值）；可为空 */
    fun dismiss(afterExit: (() -> Unit)? = null)
}

/**
 * 带进出场动画的对话框（替代裸 `AlertDialog`）。
 *
 * 背景：设置页四个弹窗此前以 `if (flag) AlertDialog(...)` 硬切出现，无任何过渡。
 * M3 `AlertDialog` 不可自定义窗口动画，因此本组件用 `Dialog` + `Surface`
 * 复刻其视觉（28dp 圆角、surfaceContainerHigh 底色、24dp 内边距、headlineSmall 标题），
 * 并在内容上叠加淡入/淡出 + 缩放的进出场动画。
 *
 * 关闭必须走 [AnimatedDialogCloser.dismiss]：按钮作用域会拿到 closer 实例，
 * 系统返回键 / 点击外部也已接入同一条路径；否则组合会被立即销毁、退场动画来不及播放。
 */
@Composable
fun AnimatedDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    title: String? = null,
    buttons: @Composable RowScope.(closer: AnimatedDialogCloser) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var exitAction: (() -> Unit)? by remember { mutableStateOf(null) }

    val closer = remember {
        object : AnimatedDialogCloser {
            override fun dismiss(afterExit: (() -> Unit)?) {
                // 退场已开始时忽略重复触发（连点、返回键与按钮同时到达）
                if (!visible) return
                exitAction = afterExit
                visible = false
            }
        }
    }

    LaunchedEffect(Unit) { visible = true }

    // visible=false 后等退场动画播完，再执行保存类副作用并通知外部真正销毁
    LaunchedEffect(visible) {
        if (!visible) {
            delay((EXIT_DURATION_MS + EXIT_SETTLE_MS).toLong())
            exitAction?.invoke()
            exitAction = null
            onDismissRequest()
        }
    }

    Dialog(
        onDismissRequest = { closer.dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = true)
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(ENTER_DURATION_MS)) +
                scaleIn(initialScale = 0.92f, animationSpec = tween(ENTER_DURATION_MS)),
            exit = fadeOut(tween(EXIT_DURATION_MS)) +
                scaleOut(targetScale = 0.94f, animationSpec = tween(EXIT_DURATION_MS))
        ) {
            Surface(
                modifier = modifier,
                shape = AlertDialogDefaults.shape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = AlertDialogDefaults.TonalElevation
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    icon?.let {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            it()
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    content()
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        buttons(closer)
                    }
                }
            }
        }
    }
}
