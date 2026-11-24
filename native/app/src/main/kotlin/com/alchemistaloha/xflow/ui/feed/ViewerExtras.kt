package com.alchemistaloha.xflow.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.subtitles.HlsSubtitles
import com.alchemistaloha.xflow.core.subtitles.SubtitleTrack
import com.alchemistaloha.xflow.core.subtitles.WebVtt
import com.alchemistaloha.xflow.core.translate.TranslateLogic
import com.alchemistaloha.xflow.data.feed.TranslationState
import com.alchemistaloha.xflow.data.feed.TranslationStore
import com.alchemistaloha.xflow.data.settings.ViewerPrefs
import com.alchemistaloha.xflow.player.SubtitleInfo
import com.alchemistaloha.xflow.player.SubtitleStore
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.DraggableBottomSheet
import com.alchemistaloha.xflow.ui.theme.rememberDraggableSheetState
import com.alchemistaloha.xflow.ui.theme.sheetDragArea
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Translation
// ---------------------------------------------------------------------------

/** The text of [tweet] as it should be read now: its translation while one is showing, else the original. */
@Composable
internal fun shownText(tweet: Tweet): String {
    val state = TranslationStore[tweet.id]
    return if (state is TranslationState.Ready && state.showing) state.text else tweet.text
}

/** True when [tweet] has a translation or can ask for one. */
@Composable
internal fun canTranslate(tweet: Tweet): Boolean {
    val prefs by ViewerPrefs.state.collectAsState()
    return TranslationStore[tweet.id] != null ||
        TranslateLogic.shouldOffer(tweet.text, tweet.lang, prefs.targetLang)
}

/**
 * The line under a post's text: "Translate", then "Translated from … · Show original".
 * Draws nothing for a post that is already in the reader's language. [onMedia] picks the
 * colours for text over video; otherwise it is drawn for a dark page.
 */
@Composable
internal fun TranslateLine(tweet: Tweet, modifier: Modifier = Modifier, onMedia: Boolean = true) {
    if (!canTranslate(tweet)) return
    val state = TranslationStore[tweet.id]
    val quiet = if (onMedia) Color.White.copy(alpha = 0.7f) else AppColors.textTertiary
    val link = if (onMedia) Color.White else AppColors.accent
    val shadow = if (onMedia) FeedTextShadow else null
    val quietStyle = TextStyle(color = quiet, fontSize = 12.5.sp, shadow = shadow)
    val linkStyle = TextStyle(color = link, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, shadow = shadow)

    @Composable
    fun Link(label: String) {
        Text(
            label,
            style = linkStyle,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { TranslationStore.toggle(tweet) }
                // A taller target than the glyphs.
                .padding(vertical = 6.dp),
        )
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            null -> Link("Translate")
            is TranslationState.Loading -> Text("Translating…", style = quietStyle, modifier = Modifier.padding(vertical = 6.dp))
            is TranslationState.Ready -> if (state.showing) {
                Text("${TranslateLogic.translatedFromLabel(state.sourceLang)} · ", style = quietStyle)
                Link("Show original")
            } else {
                Link("Show translation")
            }
            is TranslationState.Failed -> {
                Text("Couldn't translate · ", style = quietStyle)
                Link("Retry")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Subtitles
// ---------------------------------------------------------------------------

/** The track of [info] that is shown with the current settings. */
@Composable
private fun pickedTrack(info: SubtitleInfo?): SubtitleTrack? {
    val prefs by ViewerPrefs.state.collectAsState()
    val tracks = (info as? SubtitleInfo.Available)?.tracks ?: return null
    return remember(tracks, prefs.subtitleLang) {
        HlsSubtitles.pickTrack(tracks, prefs.subtitleLang, ViewerPrefs.deviceLang)
    }
}

/**
 * The subtitle line of a video, when subtitles are on and the video has some. Only this
 * composable follows the playback position, so the page around it is not redrawn.
 */
@Composable
internal fun SubtitleOverlay(tweet: Tweet, player: ExoPlayer, active: Boolean, modifier: Modifier = Modifier) {
    val prefs by ViewerPrefs.state.collectAsState()
    if (!prefs.subtitlesOn) return
    LaunchedEffect(tweet.id, active) { if (active) SubtitleStore.load(tweet) }
    val track = pickedTrack(SubtitleStore.info(tweet.id)) ?: return
    LaunchedEffect(track) { SubtitleStore.loadCues(track) }
    val cues = SubtitleStore.cues(track)
    if (cues.isNullOrEmpty()) return

    var text by remember(track) { mutableStateOf("") }
    LaunchedEffect(player, cues, active) {
        while (true) {
            text = WebVtt.textAt(cues, player.currentPosition)
            if (!active) break
            delay(120)
        }
    }
    if (text.isEmpty()) return
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text,
            textAlign = TextAlign.Center,
            style = TextStyle(color = Color.White, fontSize = 15.sp, lineHeight = 1.3.em, fontWeight = FontWeight.Medium),
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** What the "Subtitles" row says about a video. */
internal fun subtitleSummary(info: SubtitleInfo?, picked: SubtitleTrack?): String = when (info) {
    null, SubtitleInfo.Loading -> "Checking…"
    SubtitleInfo.None -> "No subtitles for this video"
    is SubtitleInfo.Available -> {
        val name = picked?.let { HlsSubtitles.label(it) } ?: "Available"
        if (info.tracks.size > 1) "$name · ${info.tracks.size} languages" else name
    }
}

// ---------------------------------------------------------------------------
// The "More" sheet of a feed item
// ---------------------------------------------------------------------------

/**
 * The sheet behind the three dots of the action column: full screen and subtitles for a
 * video, and translation for a post in another language.
 */
@Composable
internal fun FeedMoreSheet(
    tweet: Tweet,
    onFullscreen: (() -> Unit)?,
    isFullscreen: Boolean,
    onDismiss: () -> Unit,
) {
    val sheet = rememberDraggableSheetState(initialFraction = 0.42f, minFraction = 0.28f, maxFraction = 0.7f)
    val prefs by ViewerPrefs.state.collectAsState()
    LaunchedEffect(tweet.id) { SubtitleStore.load(tweet) }
    val info = SubtitleStore.info(tweet.id)
    val picked = pickedTrack(info)

    DraggableBottomSheet(onDismiss = onDismiss, state = sheet) {
        Text(
            "More",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.fillMaxWidth().sheetDragArea(sheet).padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (tweet.isVideo && onFullscreen != null) {
                SheetRow(
                    icon = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    title = if (isFullscreen) "Exit full screen" else "Full screen",
                    onTap = {
                        sheet.close()
                        onFullscreen()
                    },
                )
            }
            if (tweet.isVideo) {
                val none = info == SubtitleInfo.None
                // A video without subtitles cannot turn them on, but the switch can still be turned off.
                val usable = !none || prefs.subtitlesOn
                SheetRow(
                    icon = Icons.Rounded.ClosedCaption,
                    title = "Subtitles",
                    subtitle = subtitleSummary(info, picked),
                    enabled = usable,
                    onTap = { ViewerPrefs.setSubtitlesOn(!prefs.subtitlesOn) },
                    trailing = {
                        Switch(
                            checked = prefs.subtitlesOn,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AppColors.accent,
                                checkedBorderColor = Color.Transparent,
                                uncheckedThumbColor = AppColors.textSecondary,
                                uncheckedTrackColor = AppColors.surfaceHighest,
                                uncheckedBorderColor = Color.Transparent,
                            ),
                        )
                    },
                )
                val tracks = (info as? SubtitleInfo.Available)?.tracks.orEmpty()
                if (tracks.size > 1 && prefs.subtitlesOn) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 58.dp, end = 20.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (track in tracks) {
                            val selected = track == picked
                            Text(
                                HlsSubtitles.label(track),
                                style = TextStyle(
                                    color = if (selected) Color.Black else AppColors.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (selected) Color.White else AppColors.surfaceHighest)
                                    .clickable(role = Role.RadioButton) { ViewerPrefs.setSubtitleLang(track.language) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            if (canTranslate(tweet)) {
                val state = TranslationStore[tweet.id]
                val showing = state is TranslationState.Ready && state.showing
                SheetRow(
                    icon = Icons.Rounded.Translate,
                    title = when {
                        showing -> "Show original"
                        state is TranslationState.Loading -> "Translating…"
                        else -> "Translate post"
                    },
                    subtitle = if (showing && state is TranslationState.Ready) {
                        TranslateLogic.translatedFromLabel(state.sourceLang)
                    } else {
                        "Into ${TranslateLogic.languageName(prefs.targetLang) ?: prefs.targetLang}"
                    },
                    enabled = state !is TranslationState.Loading,
                    onTap = {
                        TranslationStore.toggle(tweet)
                        sheet.close()
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    title: String,
    onTap: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onTap) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.textPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(color = AppColors.textPrimary, fontSize = 15.5.sp))
            if (subtitle != null) {
                Text(subtitle, style = TextStyle(color = AppColors.textSecondary, fontSize = 12.5.sp))
            }
        }
        if (trailing != null) Box(Modifier.padding(start = 16.dp)) { trailing() }
    }
}
