package li.lyc.lzuapp.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「♥ made by lyc」署名文本。
 * 已内置彩蛋触发手势：10 秒内连续点击 12 次、再长按 2.4 秒即回调 [onEasterEgg]。
 * 彩蛋页尚未实现，目前回调默认留空——未来实现彩蛋页后在调用处传入即可。
 */
@Composable
fun MadeByLyc(
    modifier: Modifier = Modifier,
    onEasterEgg: () -> Unit = {},
) {
    var tapCount by remember { mutableIntStateOf(0) }
    var firstTapAt by remember { mutableLongStateOf(0L) }
    Text(
        "♥ made by lyc",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.pointerInput(onEasterEgg) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val up = withTimeoutOrNull(2_400) { waitForUpOrCancellation() }
                if (up == null) {
                    // 按住满 2.4s 未抬起：此前累计点击满 12 次则触发彩蛋
                    if (tapCount >= 12) {
                        tapCount = 0
                        firstTapAt = 0L
                        onEasterEgg()
                    }
                    waitForUpOrCancellation() // 吞掉抬起，避免这次长按被记成点击
                } else {
                    // 短按记一次点击；距首次点击超 10s 重新计
                    val now = System.currentTimeMillis()
                    if (now - firstTapAt > 10_000) {
                        tapCount = 0
                        firstTapAt = now
                    }
                    tapCount++
                }
            }
        },
    )
}
