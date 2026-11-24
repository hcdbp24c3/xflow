package com.alchemistaloha.xflow.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.alchemistaloha.xflow.core.format.ProfileFormat
import com.alchemistaloha.xflow.core.model.Async
import com.alchemistaloha.xflow.core.model.FeedState
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.data.profile.UserMediaStore
import com.alchemistaloha.xflow.data.profile.UserMediaStores
import com.alchemistaloha.xflow.data.profile.fetchUserProfile
import com.alchemistaloha.xflow.data.settings.SettingsStore
import com.alchemistaloha.xflow.data.subscriptions.SubscriptionListStore
import com.alchemistaloha.xflow.ui.nav.NavigationStore
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.settings.SettingsScreen
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppIconButton
import com.alchemistaloha.xflow.ui.theme.AppTopBar
import com.alchemistaloha.xflow.ui.theme.EmptyState
import com.alchemistaloha.xflow.ui.theme.listTitleStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Profiles already loaded in this run of the app, so coming back to a profile
 * (or recomposing) does not fetch it again. Main thread only.
 */
private val profileCache = HashMap<String, Subscription?>()

/** A user's profile: header, follow button and their media as a grid or a list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailsScreen(screenName: String) {
    val store = remember(screenName) { UserMediaStores.get(screenName) }
    val tweetsAsync by store.state.collectAsStateWithLifecycle()
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val initialProfile: Async<Subscription?> =
        if (profileCache.containsKey(screenName)) Async.Data(profileCache[screenName]) else Async.Loading
    val profileAsync by produceState(initialProfile, screenName) {
        if (profileCache.containsKey(screenName)) {
            value = Async.Data(profileCache[screenName])
            return@produceState
        }
        value = Async.Loading
        value = try {
            val profile = fetchUserProfile(screenName)
            profileCache[screenName] = profile
            Async.Data(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Async.Error(e)
        }
    }

    Box(Modifier.fillMaxSize().background(AppColors.background)) {
        when (val async = profileAsync) {
            is Async.Data -> {
                val profile = async.value
                if (profile == null) {
                    Text(
                        "User not found",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    var pulling by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxSize()) {
                        AppTopBar(
                            title = profile.name,
                            onBack = { NavigationStore.back() },
                            actions = {
                                AppIconButton(
                                    icon = Icons.Filled.Refresh,
                                    onTap = { scope.launch { store.refresh() } },
                                )
                                AppIconButton(
                                    icon = if (settings.isListView) Icons.Filled.GridView
                                    else Icons.AutoMirrored.Filled.ViewList,
                                    onTap = { SettingsStore.toggleListView(!settings.isListView) },
                                )
                                AppIconButton(
                                    icon = Icons.Outlined.Settings,
                                    onTap = { Navigator.push { SettingsScreen() } },
                                )
                            },
                        )
                        PullToRefreshBox(
                            isRefreshing = pulling,
                            onRefresh = {
                                scope.launch {
                                    pulling = true
                                    try {
                                        store.refresh()
                                    } finally {
                                        pulling = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            ProfileContent(
                                screenName = screenName,
                                profile = profile,
                                tweetsAsync = tweetsAsync,
                                isListView = settings.isListView,
                                store = store,
                            )
                        }
                    }
                }
            }

            is Async.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            is Async.Error -> EmptyState(
                icon = Icons.Rounded.ErrorOutline,
                message = "Error loading profile: ${async.error}",
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            )
        }
    }
}

private val fullLine: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan =
    { GridItemSpan(maxLineSpan) }

@Composable
private fun ProfileContent(
    screenName: String,
    profile: Subscription,
    tweetsAsync: Async<FeedState>,
    isListView: Boolean,
    store: UserMediaStore,
) {
    val feed = (tweetsAsync as? Async.Data)?.value
    val rawTweets = feed?.tweets ?: emptyList()
    // A lazy grid needs unique keys; the index stays the position in the store's list.
    val tweets = remember(rawTweets) { rawTweets.withIndex().distinctBy { it.value.id } }
    val isRefreshing = feed?.isRefreshing == true

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "header", span = fullLine, contentType = "header") {
            Column {
                ProfileHeader(profile)
                // Thin line while a refresh is running.
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (feed != null && isRefreshing && tweets.isNotEmpty()) {
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            trackColor = Color.Transparent,
                        )
                    }
                }
            }
        }

        when (tweetsAsync) {
            is Async.Data -> {
                if (tweets.isEmpty() && !isRefreshing) {
                    item(key = "empty", span = fullLine) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No media found", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (tweets.isEmpty()) {
                    item(key = "fetching", span = fullLine) {
                        Column(
                            Modifier.fillMaxWidth().padding(64.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Fetching latest items...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.textSecondary,
                            )
                        }
                    }
                } else if (isListView) {
                    mediaItems(tweets, span = fullLine, contentType = "row", store = store) { index, tweet ->
                        MediaListRow(tweet) { NavigationStore.openUserMedia(screenName, index) }
                    }
                } else {
                    mediaItems(tweets, span = null, contentType = "cell", store = store) { index, tweet ->
                        MediaGridCell(tweet) {
                            NavigationStore.openUserMedia(screenName, index, tweetId = tweet.id)
                        }
                    }
                }
            }

            is Async.Loading -> item(key = "loading", span = fullLine) {
                Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            is Async.Error -> item(key = "error", span = fullLine) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Error: ${tweetsAsync.error}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** The media items; composing the last one asks the store for the next page. */
private fun LazyGridScope.mediaItems(
    tweets: List<IndexedValue<Tweet>>,
    span: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan)?,
    contentType: String,
    store: UserMediaStore,
    content: @Composable (index: Int, tweet: Tweet) -> Unit,
) {
    itemsIndexed(
        items = tweets,
        key = { _, item -> item.value.id },
        span = if (span != null) { _, _ -> span() } else null,
        contentType = { _, _ -> contentType },
    ) { position, item ->
        if (position == tweets.lastIndex) {
            LaunchedEffect(item.value.id, tweets.size) { store.fetchMore() }
        }
        content(item.index, item.value)
    }
}

@Composable
private fun ProfileHeader(profile: Subscription) {
    // The grid already insets 4dp on each side.
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val avatarUrl = profile.profileImageUrlHighRes
            Box(
                Modifier
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(5.dp)
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarUrl != null) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    "@${profile.screenName}",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
            Spacer(Modifier.width(8.dp))
            SubscribeButton(profile)
        }
        Spacer(Modifier.height(16.dp))
        val description = profile.description
        if (!description.isNullOrEmpty()) {
            Text(
                description,
                modifier = Modifier.padding(bottom = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row {
            ProfileStat(ProfileFormat.formatCount(profile.followingCount ?: 0), "Following")
            Spacer(Modifier.width(24.dp))
            ProfileStat(ProfileFormat.formatCount(profile.followersCount ?: 0), "Followers")
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            thickness = 0.5.dp,
            color = AppColors.outline,
        )
    }
}

@Composable
private fun ProfileStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun SubscribeButton(profile: Subscription) {
    val subscriptionState by SubscriptionListStore.state.collectAsStateWithLifecycle()
    val isSubscribed = subscriptionState.isSubscribed(profile.screenName)
    val onClick = {
        SubscriptionListStore.toggleSubscription(
            Subscription(
                id = profile.screenName,
                screenName = profile.screenName,
                name = profile.name,
                profileImageUrl = profile.profileImageUrl,
                description = profile.description,
                followersCount = profile.followersCount,
                followingCount = profile.followingCount,
            ),
        )
    }
    val label = if (isSubscribed) "Subscribed" else "Subscribe"
    val modifier = Modifier.semantics { contentDescription = label }
    if (isSubscribed) {
        OutlinedIconButton(
            onClick = { onClick() },
            modifier = modifier,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    } else {
        FilledTonalIconButton(onClick = { onClick() }, modifier = modifier) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MediaListRow(tweet: Tweet, onTap: () -> Unit) {
    // The grid already insets 4dp on each side and puts 4dp between rows.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tweet.mediaUrls.isNotEmpty()) {
            AsyncImage(
                model = tweet.thumbnailUrl ?: tweet.mediaUrls.first(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Icon(Icons.Filled.TextFields, contentDescription = null, tint = AppColors.textSecondary)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(tweet.text, style = listTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                ProfileFormat.formatDate(tweet.createdAtMillis),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun MediaGridCell(tweet: Tweet, onTap: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onTap),
    ) {
        if (tweet.mediaUrls.isNotEmpty()) {
            val url = tweet.thumbnailUrl ?: tweet.mediaUrls.first()
            var failed by remember(url) { mutableStateOf(false) }
            if (failed) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    tint = AppColors.textTertiary,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize().background(AppColors.surfaceHigh),
                )
            }
        } else {
            Text(
                tweet.text,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            )
        }
        if (tweet.isVideo) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) {
                // Soft dark copy behind the white icon, standing in for the Flutter shadow.
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color(0x8A000000),
                    modifier = Modifier.size(22.dp).align(Alignment.Center),
                )
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp).align(Alignment.Center),
                )
            }
        }
    }
}
