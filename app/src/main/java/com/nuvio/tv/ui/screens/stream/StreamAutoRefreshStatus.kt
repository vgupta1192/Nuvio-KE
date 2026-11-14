package com.nuvio.tv.ui.screens.stream

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val DRY_ROUNDS = 5

/**
 * One line that shows the stream auto refresh is alive. It says whether the screen is waiting for
 * the first load, checking the addons now, or idle, how many rounds ran and how many new links
 * they added in total. The line hides itself once it has nothing to report: after five rounds in
 * a row that added no links. New links bring it back.
 */
@Composable
internal fun StreamAutoRefreshStatus(
    phase: Int,
    rounds: Int,
    added: Int,
    dryRounds: Int,
    lastAtMs: Long,
    lastOk: Boolean,
    manualLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    // The dry-round streak is kept by the view model, so coming back from the player does not
    // restart it; this composable only reads it.
    val visible = dryRounds < DRY_ROUNDS
    if (!visible) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val text = when {
        manualLoading || phase == 0 -> "Auto refresh: waiting for the load to finish"
        phase == 1 -> "Auto refresh: checking addons now · round ${rounds + 1} · +$added new links since opening"
        else -> {
            val ago = ((now - lastAtMs) / 1000).coerceAtLeast(0)
            val result = if (lastOk) "ok" else "timed out"
            "Auto refresh: last check ${ago}s ago ($result) · $rounds rounds · +$added links added"
        }
    }
    val color = if (phase == 2 && !lastOk) Color(0xFFFFB74D) else NuvioTheme.marquee.onImageBody
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(bottom = 6.dp)
    )
}
