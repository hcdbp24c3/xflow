package com.alchemistaloha.xflow.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.ui.theme.AppColors

/** Full-page card for a post that has no media: the text, centred on a soft gradient. */
@Composable
fun TextTweetCard(tweet: Tweet, modifier: Modifier = Modifier) {
    val text = shownText(tweet)
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AppColors.surfaceHigh, AppColors.background)))
            // Keeps the text clear of the top bar, the action rail and the caption.
            .padding(start = 32.dp, top = 96.dp, end = 72.dp, bottom = 200.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            modifier = Modifier.verticalScroll(rememberScrollState()),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge.copy(
                color = AppColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 1.35.em,
            ),
        )
    }
}
