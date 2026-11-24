package com.alchemistaloha.xflow.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class MainTab { media, subscriptions, trending }

data class NavigationState(
    val currentTab: MainTab = MainTab.media,
    /** If not null, show UserDetails or UserMediaFeed. */
    val selectedUser: String? = null,
    val userMediaInitialIndex: Int? = null,
    val userMediaInitialTweetId: String? = null,
    val selectedHashtag: String? = null,
) {
    fun copyWith(
        currentTab: MainTab? = null,
        selectedUser: String? = null,
        userMediaInitialIndex: Int? = null,
        userMediaInitialTweetId: String? = null,
        selectedHashtag: String? = null,
        clearUser: Boolean = false,
        clearMediaIndex: Boolean = false,
        clearHashtag: Boolean = false,
    ) = NavigationState(
        currentTab = currentTab ?: this.currentTab,
        selectedUser = if (clearUser) null else (selectedUser ?: this.selectedUser),
        userMediaInitialIndex = if (clearUser || clearMediaIndex) null
        else (userMediaInitialIndex ?: this.userMediaInitialIndex),
        userMediaInitialTweetId = if (clearUser || clearMediaIndex) null
        else (userMediaInitialTweetId ?: this.userMediaInitialTweetId),
        selectedHashtag = if (clearHashtag || clearUser) null
        else (selectedHashtag ?: this.selectedHashtag),
    )
}

/**
 * Which tab is showing and which profile / hashtag overlay is open on top of
 * the tabs. Same state machine as the Flutter navigationProvider.
 */
object NavigationStore {
    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    fun setTab(tab: MainTab) =
        _state.update { it.copyWith(currentTab = tab, clearUser = true, clearHashtag = true) }

    fun selectUser(screenName: String) =
        _state.update { it.copyWith(selectedUser = screenName, clearMediaIndex = true, clearHashtag = true) }

    fun openUserMedia(screenName: String, index: Int, tweetId: String? = null) = _state.update {
        it.copyWith(
            selectedUser = screenName,
            userMediaInitialIndex = index,
            userMediaInitialTweetId = tweetId,
            clearHashtag = true,
        )
    }

    fun selectHashtag(hashtag: String) = _state.update { it.copyWith(selectedHashtag = hashtag) }

    fun back() = _state.update {
        when {
            it.selectedHashtag != null -> it.copyWith(clearHashtag = true)
            it.userMediaInitialIndex != null -> it.copyWith(clearMediaIndex = true)
            it.selectedUser != null -> it.copyWith(clearUser = true)
            else -> it
        }
    }
}

/**
 * Pushed full screens (Settings, Bookmarks, Login, ...): the Compose
 * counterpart of Flutter's `Navigator.push(MaterialPageRoute(...))`.
 * `Navigator.push { SettingsScreen() }` shows a screen above everything
 * else; system back or [pop] removes the top one.
 */
object Navigator {
    class Entry(val id: Long, val content: @Composable () -> Unit)

    private var nextId = 0L
    val stack = mutableStateListOf<Entry>()

    fun push(content: @Composable () -> Unit) {
        stack.add(Entry(nextId++, content))
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    val canPop: Boolean get() = stack.isNotEmpty()
}
