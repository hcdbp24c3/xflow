package com.alchemistaloha.xflow.ui.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.subscriptions.SubscriptionListState
import com.alchemistaloha.xflow.core.subscriptions.SubscriptionSort
import com.alchemistaloha.xflow.core.subscriptions.subscriptionSubtitle
import com.alchemistaloha.xflow.data.client.AccountStore
import com.alchemistaloha.xflow.data.subscriptions.SubscriptionListStore
import com.alchemistaloha.xflow.ui.auth.LoginScreen
import com.alchemistaloha.xflow.ui.nav.NavigationStore
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.profile.UserDetailsScreen
import com.alchemistaloha.xflow.ui.settings.SettingsScreen
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppIconButton
import com.alchemistaloha.xflow.ui.theme.AppRadius
import com.alchemistaloha.xflow.ui.theme.AppTopBar
import com.alchemistaloha.xflow.ui.theme.HSpace
import com.alchemistaloha.xflow.ui.theme.PressableScale
import com.alchemistaloha.xflow.ui.theme.VSpace
import kotlinx.coroutines.launch

/**
 * The followed accounts, searchable and sortable. As a tab
 * ([isStandalone] false) it shows refresh and settings buttons and opens a
 * profile as an overlay; pushed on its own it has a back button and pushes
 * the profile.
 */
@Composable
fun SubscriptionListScreen(isStandalone: Boolean = true) {
    val state by SubscriptionListStore.state.collectAsStateWithLifecycle()
    val account by AccountStore.state.collectAsStateWithLifecycle()

    if (state.isLoading) {
        Box(Modifier.fillMaxSize().background(AppColors.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val subs = state.filteredSubscriptions

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.background)
            .then(if (isStandalone) Modifier.navigationBarsPadding() else Modifier),
    ) {
        if (isStandalone) {
            AppTopBar(title = "Subscriptions", onBack = { Navigator.pop() })
        } else {
            TabTopBar(
                onRefresh = { AppGraph.scope.launch { SubscriptionListStore.refresh() } },
                onSettings = { Navigator.push { SettingsScreen() } },
            )
        }
        SearchAndSort(state)

        if (subs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (state.searchQuery.isEmpty()) "No subscriptions found."
                        else "No results matching \"${state.searchQuery}\"",
                        style = MaterialTheme.typography.bodyMedium.copy(color = AppColors.textSecondary),
                    )
                    if (account == null) {
                        VSpace(16.dp)
                        FilledTonalButton(onClick = { Navigator.push { LoginScreen() } }) {
                            Text("Login to X")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(subs, key = { it.id }) { sub ->
                    val views = state.userViews[sub.screenName.lowercase()] ?: 0
                    SubscriptionRow(sub = sub, views = views) {
                        if (isStandalone) {
                            Navigator.push { UserDetailsScreen(screenName = sub.screenName) }
                        } else {
                            NavigationStore.selectUser(sub.screenName)
                        }
                    }
                }
            }
        }
    }
}

/** Top bar of the tab: refresh on the left, centred title, settings on the right. */
@Composable
private fun TabTopBar(onRefresh: () -> Unit, onSettings: () -> Unit) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        Modifier
            .fillMaxWidth()
            .background(AppColors.background)
            .padding(top = topInset)
            .height(56.dp),
    ) {
        AppIconButton(
            icon = Icons.Filled.Refresh,
            onTap = onRefresh,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
        )
        Text(
            "Subscriptions",
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AppIconButton(
            icon = Icons.Outlined.Settings,
            onTap = onSettings,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
        )
    }
}

@Composable
private fun SubscriptionRow(sub: Subscription, views: Int, onTap: () -> Unit) {
    Row(
        Modifier
            .padding(vertical = 3.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.surface)
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .heightIn(min = 68.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(AppColors.surfaceHighest),
            contentAlignment = Alignment.Center,
        ) {
            val avatar = sub.profileImageUrl
            if (avatar != null) {
                AsyncImage(
                    model = avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = AppColors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        HSpace(16.dp)
        Column(Modifier.weight(1f)) {
            Text(
                sub.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subscriptionSubtitle(sub, views),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = AppColors.textSecondary,
                    fontSize = 12.5.sp,
                ),
            )
        }
    }
}

@Composable
private fun SearchAndSort(state: SubscriptionListState) {
    var query by rememberSaveable { mutableStateOf(state.searchQuery) }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(AppRadius.md))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = AppColors.textSecondary,
                modifier = Modifier.size(24.dp),
            )
            HSpace(8.dp)
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    SubscriptionListStore.setSearchQuery(it)
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                cursorBrush = SolidColor(AppColors.accent),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                "Search subscriptions...",
                                style = MaterialTheme.typography.bodyMedium.copy(color = AppColors.textTertiary),
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        VSpace(8.dp)
        SubscriptionSortSettings(state)
    }
}

/** The row of sort chips and the ascending / descending switch. */
@Composable
private fun SubscriptionSortSettings(state: SubscriptionListState) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SortChip("Name", state.sort == SubscriptionSort.Name) {
            SubscriptionListStore.setSort(SubscriptionSort.Name)
        }
        HSpace(8.dp)
        SortChip("Handle", state.sort == SubscriptionSort.Handle) {
            SubscriptionListStore.setSort(SubscriptionSort.Handle)
        }
        HSpace(8.dp)
        SortChip("Followers", state.sort == SubscriptionSort.Followers) {
            SubscriptionListStore.setSort(SubscriptionSort.Followers)
        }
        HSpace(8.dp)
        SortChip("Views", state.sort == SubscriptionSort.Views) {
            SubscriptionListStore.setSort(SubscriptionSort.Views)
        }
        HSpace(8.dp)
        PressableScale(
            onTap = { SubscriptionListStore.toggleOrder() },
            tooltip = if (state.isAscending) "Ascending" else "Descending",
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                if (state.isAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SortChip(label: String, isSelected: Boolean, onTap: () -> Unit) {
    // Selected chips are near-white, so their label is black to stay readable.
    val background = if (isSelected) AppColors.textPrimary else AppColors.surfaceHigh
    val foreground = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, color = foreground),
            maxLines = 1,
        )
    }
}
