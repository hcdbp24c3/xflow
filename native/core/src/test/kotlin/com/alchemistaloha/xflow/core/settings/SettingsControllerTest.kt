package com.alchemistaloha.xflow.core.settings

import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.MediaFilter
import com.alchemistaloha.xflow.core.model.SettingsState
import com.alchemistaloha.xflow.core.model.VideoEndAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory storage, the stand-in for `SharedPreferences.setMockInitialValues`. */
internal class MapSettingsStorage(val values: MutableMap<String, Any> = mutableMapOf()) : SettingsStorage {
    override fun contains(key: String) = values.containsKey(key)
    override fun getBool(key: String) = values[key] as? Boolean
    override fun getInt(key: String) = values[key] as? Int
    override fun getDouble(key: String) = values[key] as? Double
    @Suppress("UNCHECKED_CAST")
    override fun getStringList(key: String) = values[key] as? List<String>
    override fun setBool(key: String, value: Boolean) { values[key] = value }
    override fun setInt(key: String, value: Int) { values[key] = value }
    override fun setDouble(key: String, value: Double) { values[key] = value }
    override fun setStringList(key: String, value: List<String>) { values[key] = value }
}

/** Port of test/features/settings/settings_provider_test.dart, plus the rest of the notifier. */
class SettingsControllerTest {
    private fun initial() = MapSettingsStorage(
        mutableMapOf("loadBatchSize" to 30, "syncInterval" to 10, "cooldownDuration" to 5),
    )

    @Test
    fun `initializes with values from SharedPreferences`() {
        val controller = SettingsController(initial())
        assertFalse(controller.state.value.isInitialized)
        controller.load()

        val state = controller.state.value
        assertTrue(state.isInitialized)
        assertEquals(30, state.loadBatchSize)
        assertEquals(10, state.syncInterval)
        assertEquals(5, state.cooldownDuration)
        assertEquals(10, state.syncBatchSize) // Default
    }

    @Test
    fun `updates and persists values`() {
        val storage = initial()
        val controller = SettingsController(storage)
        controller.load()

        controller.updateLoadBatchSize(50)

        assertEquals(50, controller.state.value.loadBatchSize)
        assertEquals(50, storage.getInt("loadBatchSize"))
    }

    @Test
    fun `empty storage loads the Flutter defaults`() {
        val controller = SettingsController(MapSettingsStorage())
        controller.load()
        // Same as the Flutter app: everything matches the state defaults except
        // fetchStrategy, which loads as index 0 (latest) when nothing is stored.
        assertEquals(
            SettingsState(isInitialized = true, fetchStrategy = FeedSort.latest),
            controller.state.value,
        )
    }

    @Test
    fun `out of range enum indexes fall back`() {
        val controller = SettingsController(
            MapSettingsStorage(mutableMapOf("fetchStrategy" to 99, "videoEndAction" to 99)),
        )
        controller.load()
        assertEquals(FeedSort.latest, controller.state.value.fetchStrategy)
        assertEquals(VideoEndAction.playNext, controller.state.value.videoEndAction)
    }

    @Test
    fun `stored values round trip through a second controller`() {
        val storage = MapSettingsStorage()
        val first = SettingsController(storage)
        first.load()
        first.updateMediaCacheSize(900)
        first.updateSyncInterval(3)
        first.updateSyncBatchSize(4)
        first.updateTimelineBatchSize(44)
        first.updateCooldownDuration(6)
        first.updatePruneThreshold(123)
        first.toggleAutoplay(false)
        first.toggleListView(true)
        first.toggleDebugInfo(true)
        first.toggleFilter(MediaFilter.text)
        first.toggleFilter(MediaFilter.video)
        first.updateDiscoveryParam(
            avoidWatchedContent = false,
            freshMixRatio = 0.75,
            fetchStrategy = FeedSort.videomixer,
            minFavesFilter = 7,
            videoEndAction = VideoEndAction.replay,
            mediaDeduplicationWindow = 12,
        )

        val second = SettingsController(storage)
        second.load()
        assertEquals(first.state.value, second.state.value)
        val s = second.state.value
        assertEquals(900, s.mediaCacheSizeMB)
        assertEquals(setOf(MediaFilter.text, MediaFilter.video), s.filters)
        assertEquals(listOf(MediaFilter.text, MediaFilter.video), s.filters.toList())
        assertEquals(0.75, s.freshMixRatio, 0.0)
        assertEquals(FeedSort.videomixer, s.fetchStrategy)
        assertEquals(VideoEndAction.replay, s.videoEndAction)
        assertFalse(s.avoidWatchedContent)
        assertFalse(s.autoplay)
        assertTrue(s.isListView)
        assertTrue(s.showDebugInfo)
        assertEquals(5, storage.getInt("fetchStrategy"))
        assertEquals(1, storage.getInt("videoEndAction"))
    }

    @Test
    fun `updateDiscoveryParam only touches what is passed`() {
        val storage = MapSettingsStorage()
        val controller = SettingsController(storage)
        controller.load()
        val before = controller.state.value

        controller.updateDiscoveryParam(saturationThreshold = 4)

        assertEquals(before.copy(saturationThreshold = 4), controller.state.value)
        assertEquals(mapOf<String, Any>("saturationThreshold" to 4), storage.values)
    }

    @Test
    fun `toggleFilter adds then removes and unknown stored names are ignored`() {
        val storage = MapSettingsStorage(mutableMapOf("filters" to listOf("video", "bogus")))
        val controller = SettingsController(storage)
        controller.load()
        assertEquals(setOf(MediaFilter.video), controller.state.value.filters)

        controller.toggleFilter(MediaFilter.image)
        assertEquals(listOf("video", "image"), storage.getStringList("filters"))
        controller.toggleFilter(MediaFilter.video)
        assertEquals(setOf(MediaFilter.image), controller.state.value.filters)
        assertEquals(listOf("image"), storage.getStringList("filters"))
    }

    @Test
    fun `every stored key is a known key`() {
        val storage = MapSettingsStorage()
        val c = SettingsController(storage)
        c.load()
        c.updateMediaCacheSize(1); c.updateSyncInterval(1); c.updateSyncBatchSize(1); c.updateLoadBatchSize(1)
        c.updateTimelineBatchSize(1); c.updateCooldownDuration(1); c.updatePruneThreshold(1)
        c.toggleAutoplay(true); c.toggleListView(true); c.toggleDebugInfo(true); c.toggleFilter(MediaFilter.video)
        c.updateDiscoveryParam(
            true, true, 0.1, 1, 1, FeedSort.popular, 1, true, true, true, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            VideoEndAction.pause,
        )
        val known = SettingsKeys.INT_KEYS + SettingsKeys.BOOL_KEYS + SettingsKeys.DOUBLE_KEYS +
            SettingsKeys.STRING_LIST_KEYS
        assertEquals(known, storage.values.keys)
        assertEquals(37, known.size)
    }
}
