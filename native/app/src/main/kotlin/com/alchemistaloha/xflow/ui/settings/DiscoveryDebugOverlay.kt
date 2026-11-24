package com.alchemistaloha.xflow.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.settings.SettingsFormat
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.db.Repository
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppRadius
import kotlin.coroutines.cancellation.CancellationException

/**
 * Small card of discovery facts about one feed item, shown when
 * "Overlay Source Info" is on (Dart `DiscoveryDebugOverlay`).
 *
 * The Dart widget positions itself (`top: status bar + 60, left: 10`); here
 * the caller does that through [modifier].
 */
@Composable
fun DiscoveryDebugOverlay(tweet: Tweet, modifier: Modifier = Modifier) {
    val stats by produceState(initialValue = 0 to 0, tweet.id, tweet.userHandle) {
        value = try {
            val mediaCount = Repository.getMediaPlayedCount(tweet.id)
            val userCount = Repository.getUserPlayedCount(tweet.userHandle)
            mediaCount to userCount
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("DiscoveryDebugOverlay: stats failed: $e")
            0 to 0
        }
    }

    Column(
        modifier
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(8.dp),
    ) {
        SettingsFormat.debugLines(tweet, stats.first, stats.second).forEach { (label, value) ->
            DebugLine(label, value)
        }
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(
            "$label: ",
            style = TextStyle(
                color = AppColors.success,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            ),
        )
        Text(
            value,
            style = TextStyle(
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}
