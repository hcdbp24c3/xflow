package com.alchemistaloha.xflow.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.client.X_MOBILE_USER_AGENT
import com.alchemistaloha.xflow.core.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.TreeSet
import java.util.concurrent.TimeUnit

/**
 * Disk cache for media: video bytes (Media3 SimpleCache) and images (Coil disk cache).
 * Replaces the Flutter CustomMediaCacheManager. Both live under the app's cache directory in
 * their own folders; the Flutter app's cache folders are never touched.
 */
@OptIn(UnstableApi::class)
object MediaCache {
    private const val VIDEO_DIR = "xflow_video_cache"
    private const val IMAGE_DIR = "xflow_image_cache"

    /** Share of the user's limit given to video; the rest goes to images. */
    private const val VIDEO_SHARE = 0.8

    @Volatile private var maxBytes: Long = 500L * 1024 * 1024

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val evictor = ResizableLruEvictor { (maxBytes * VIDEO_SHARE).toLong() }

    private val videoCache: SimpleCache by lazy {
        val ctx = AppGraph.appContext
        SimpleCache(File(ctx.cacheDir, VIDEO_DIR), evictor, StandaloneDatabaseProvider(ctx))
    }

    /** Data source for players: disk cache in front of OkHttp. */
    val dataSourceFactory: DataSource.Factory by lazy {
        val upstream = DefaultDataSource.Factory(
            AppGraph.appContext,
            OkHttpDataSource.Factory(httpClient).setUserAgent(X_MOBILE_USER_AGENT),
        )
        CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun imageDir(): File = File(AppGraph.appContext.cacheDir, IMAGE_DIR)

    /** The app-wide Coil loader (installed by XFlowApp). */
    fun newImageLoader(): ImageLoader {
        val ctx = AppGraph.appContext
        return ImageLoader.Builder(ctx)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { httpClient })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(imageDir())
                    .maxSizeBytes((maxBytes * (1 - VIDEO_SHARE)).toLong().coerceAtLeast(16L * 1024 * 1024))
                    .build()
            }
            .crossfade(false)
            .build()
    }

    /** Applies the "media cache size" setting. Video is trimmed at once; images on next start. */
    fun applyMaxSize(megabytes: Int) {
        maxBytes = megabytes.coerceAtLeast(1).toLong() * 1024 * 1024
        AppGraph.scope.let {
            Thread {
                try {
                    evictor.trim(videoCache)
                } catch (e: Exception) {
                    AppLogger.log("XFLOW: Error trimming media cache: $e")
                }
            }.start()
        }
    }

    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        try {
            videoCache.cacheSpace + dirSize(imageDir())
        } catch (e: Exception) {
            AppLogger.log("Error calculating cache size: $e")
            0L
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        try {
            for (key in videoCache.keys.toList()) videoCache.removeResource(key)
            coil3.SingletonImageLoader.get(AppGraph.appContext).let {
                it.diskCache?.clear()
                it.memoryCache?.clear()
            }
        } catch (e: Exception) {
            AppLogger.log("Error clearing physical cache: $e")
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }
}

/** Least-recently-used evictor whose limit can change while the cache is open. */
@OptIn(UnstableApi::class)
private class ResizableLruEvictor(private val limit: () -> Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan> { a, b ->
        val byTime = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
        if (byTime != 0) byTime else a.compareTo(b)
    }
    private var currentSize = 0L

    override fun requiresCacheSpanTouches(): Boolean = true
    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    @Synchronized
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        currentSize += span.length
        evict(cache, 0)
    }

    @Synchronized
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) currentSize -= span.length
    }

    @Synchronized
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    fun trim(cache: Cache) = evict(cache, 0)

    @Synchronized
    private fun evict(cache: Cache, required: Long) {
        val max = limit()
        while (currentSize + required > max && spans.isNotEmpty()) {
            val first = spans.first()
            cache.removeSpan(first)
            // removeSpan calls back onSpanRemoved; guard against a cache that does not.
            if (spans.contains(first)) onSpanRemoved(cache, first)
        }
    }
}
