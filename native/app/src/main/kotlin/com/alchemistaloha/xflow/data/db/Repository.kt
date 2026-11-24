package com.alchemistaloha.xflow.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.db.BookmarkFields
import com.alchemistaloha.xflow.core.db.RepositorySql
import com.alchemistaloha.xflow.core.db.tableAccounts
import com.alchemistaloha.xflow.core.db.tableCachedMedia
import com.alchemistaloha.xflow.core.db.tableHashtags
import com.alchemistaloha.xflow.core.db.tableSubscriptions
import com.alchemistaloha.xflow.core.model.Account
import com.alchemistaloha.xflow.core.model.MediaFilter
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's SQLite store. It opens the very file the Flutter app (sqflite)
 * created, `databases/xflow.db`, at the same version with the same schema and
 * upgrade steps, so an existing database is used as it is. Nothing here ever
 * deletes or recreates the file: if it cannot be opened the error is logged
 * and rethrown.
 */
object Repository {
    private val lock = Any()
    private var helper: DbHelper? = null

    /**
     * The open database (Dart `Repository.database`). Opening can block on
     * disk the first time; prefer the suspend methods, or [open] at startup.
     */
    val database: SQLiteDatabase
        get() = synchronized(lock) {
            val h = helper ?: DbHelper(AppGraph.appContext).also { helper = it }
            try {
                h.writableDatabase
            } catch (e: Exception) {
                AppLogger.log("XFLOW: Could not open database ${RepositorySql.DATABASE_NAME}: $e")
                throw e
            }
        }

    /** Opens (and if needed creates or upgrades) the database off the main thread. */
    suspend fun open() {
        io { database }
    }

    private class DbHelper(context: Context) : SQLiteOpenHelper(
        context.applicationContext,
        RepositorySql.DATABASE_NAME,
        null,
        RepositorySql.DATABASE_VERSION,
        // The platform default handler deletes a database it believes is
        // corrupt. Never do that with the user's data: only report it.
        DatabaseErrorHandler { db ->
            AppLogger.log("XFLOW: SQLite reported corruption in ${db.path}; the file is left untouched")
        },
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            for (sql in RepositorySql.createStatements) db.execSQL(sql)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            for (sql in RepositorySql.upgradeStatements(oldVersion)) db.execSQL(sql)
        }

        // onDowngrade is not overridden: the platform default throws, which
        // leaves a database written by a newer version untouched.
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private inline fun <T> SQLiteDatabase.inTransaction(block: () -> T): T {
        beginTransaction()
        try {
            val result = block()
            setTransactionSuccessful()
            return result
        } finally {
            endTransaction()
        }
    }

    // ---- hashtags ----------------------------------------------------------

    suspend fun addHashtag(tag: String) = io {
        val values = ContentValues().apply {
            put("tag", tag)
            put("added_at", System.currentTimeMillis())
        }
        database.insertWithOnConflict(tableHashtags, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    suspend fun getHashtags(): List<String> = io {
        database.query(tableHashtags, null, null, null, null, null, "added_at DESC").use { c ->
            val out = ArrayList<String>(c.count)
            val tagIdx = c.getColumnIndexOrThrow("tag")
            while (c.moveToNext()) out.add(c.getString(tagIdx))
            out
        }
    }

    suspend fun deleteHashtag(tag: String) = io {
        database.delete(tableHashtags, "tag = ?", arrayOf(tag))
        Unit
    }

    // ---- accounts ----------------------------------------------------------

    suspend fun insertAccount(account: Account) = io {
        val values = ContentValues().apply {
            put("id", account.id)
            put("screen_name", account.screenName)
            put("rest_id", account.restId)
            put("auth_header", account.authHeader)
        }
        database.insertWithOnConflict(tableAccounts, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    suspend fun getAccounts(): List<Account> = io {
        database.query(tableAccounts, null, null, null, null, null, null).use { c ->
            val out = ArrayList<Account>(c.count)
            while (c.moveToNext()) {
                out.add(
                    Account(
                        id = c.string("id") ?: "",
                        screenName = c.string("screen_name") ?: "",
                        restId = c.string("rest_id") ?: "",
                        authHeader = c.string("auth_header") ?: "",
                    )
                )
            }
            out
        }
    }

    /** Removes every stored account (what Dart `TwitterAccount.logout` did with `db.delete(tableAccounts)`). */
    suspend fun deleteAllAccounts() = io {
        database.delete(tableAccounts, null, null)
        Unit
    }

    // ---- subscriptions -----------------------------------------------------

    private fun Subscription.toValues() = ContentValues().apply {
        put("id", id)
        put("screen_name", screenName)
        put("name", name)
        put("profile_image_url", profileImageUrl)
        put("description", description)
        put("followers_count", followersCount)
        put("following_count", followingCount)
    }

    suspend fun insertSubscription(sub: Subscription) = io {
        database.insertWithOnConflict(tableSubscriptions, null, sub.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    suspend fun insertSubscriptions(subs: List<Subscription>) = io {
        val db = database
        db.inTransaction {
            for (sub in subs) {
                db.insertWithOnConflict(tableSubscriptions, null, sub.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    suspend fun getSubscriptions(): List<Subscription> = io {
        database.query(tableSubscriptions, null, null, null, null, null, null).use { c ->
            val out = ArrayList<Subscription>(c.count)
            while (c.moveToNext()) {
                out.add(
                    Subscription(
                        id = c.string("id") ?: "",
                        screenName = c.string("screen_name") ?: "",
                        name = c.string("name") ?: "",
                        profileImageUrl = c.string("profile_image_url"),
                        description = c.string("description"),
                        followersCount = c.intOrNull("followers_count"),
                        followingCount = c.intOrNull("following_count"),
                    )
                )
            }
            out
        }
    }

    suspend fun clearSubscriptions() = io {
        database.delete(tableSubscriptions, null, null)
        Unit
    }

    /** Removes one subscription by its row id. (Not in the Dart Repository; the subscriptions store asks for it.) */
    suspend fun deleteSubscription(id: String) = io {
        database.delete(tableSubscriptions, "id = ?", arrayOf(id))
        Unit
    }

    /**
     * Removes the subscription(s) with this screen name, ignoring case: the
     * statement the Dart subscription list ran directly on the database.
     */
    suspend fun deleteSubscriptionByScreenName(screenName: String) = io {
        database.delete(tableSubscriptions, "LOWER(screen_name) = ?", arrayOf(screenName.lowercase()))
        Unit
    }

    // ---- post extras -------------------------------------------------------
    // The language and the HLS playlist of a post. The cached media table keeps the layout
    // the Flutter app gave it, so these live in a table of their own that Flutter never reads.

    private const val tableTweetExtras = "tweet_extras"

    @Volatile private var extrasReady = false

    private fun extrasTable(db: SQLiteDatabase): Boolean {
        if (extrasReady) return true
        return try {
            db.execSQL("CREATE TABLE IF NOT EXISTS $tableTweetExtras (id TEXT PRIMARY KEY, lang TEXT, hls_url TEXT)")
            extrasReady = true
            true
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Could not create $tableTweetExtras: $e")
            false
        }
    }

    private fun saveExtras(db: SQLiteDatabase, tweets: List<Tweet>) {
        val withExtras = tweets.filter { it.lang != null || it.hlsUrl != null }
        if (withExtras.isEmpty() || !extrasTable(db)) return
        try {
            db.inTransaction {
                for (tweet in withExtras) {
                    val values = ContentValues().apply {
                        put("id", tweet.id)
                        put("lang", tweet.lang)
                        put("hls_url", tweet.hlsUrl)
                    }
                    db.insertWithOnConflict(tableTweetExtras, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Could not save post extras: $e")
        }
    }

    /** Language and playlist of a post seen before: `lang to hlsUrl`, or null when nothing is stored. */
    suspend fun getTweetExtras(id: String): Pair<String?, String?>? = io {
        val db = database
        if (!extrasTable(db)) return@io null
        try {
            db.rawQuery("SELECT lang, hls_url FROM $tableTweetExtras WHERE id = ?", arrayOf(id)).use { c ->
                if (c.moveToFirst()) {
                    (if (c.isNull(0)) null else c.getString(0)) to (if (c.isNull(1)) null else c.getString(1))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Could not read post extras: $e")
            null
        }
    }

    /** Keeps the extras of a post that was looked up on its own (it may not be in the cache). */
    suspend fun saveTweetExtras(tweet: Tweet) = io { saveExtras(database, listOf(tweet)) }

    // ---- cached media ------------------------------------------------------

    suspend fun insertCachedMedia(tweets: List<Tweet>) = io {
        val db = database
        saveExtras(db, tweets)
        db.inTransaction {
            for (tweet in tweets) {
                val values = ContentValues().apply {
                    put("id", tweet.id)
                    put("text", tweet.text)
                    put("user_handle", tweet.userHandle)
                    put("user_avatar_url", tweet.userAvatarUrl)
                    put("media_key", tweet.mediaKey)
                    put("media_urls", RepositorySql.encodeMediaUrls(tweet.mediaUrls))
                    put("thumbnail_url", tweet.thumbnailUrl)
                    put("is_video", if (tweet.isVideo) 1 else 0)
                    put("created_at", tweet.createdAtMillis)
                    put("favorite_count", tweet.favoriteCount)
                    put("reply_count", tweet.replyCount)
                    put("retweet_count", tweet.retweetCount)
                    put("view_count", tweet.viewCount)
                }
                // Don't overwrite play counts if already exists
                db.insertWithOnConflict(tableCachedMedia, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }

        // Update stats for existing tweets (ignore doesn't update existing rows)
        for (tweet in tweets) {
            if (tweet.favoriteCount > 0 ||
                tweet.replyCount > 0 ||
                tweet.retweetCount > 0 ||
                tweet.viewCount > 0
            ) {
                val values = ContentValues().apply {
                    put("favorite_count", tweet.favoriteCount)
                    put("reply_count", tweet.replyCount)
                    put("retweet_count", tweet.retweetCount)
                    put("view_count", tweet.viewCount)
                }
                db.update(tableCachedMedia, values, "id = ?", arrayOf(tweet.id))
            }
        }
    }

    suspend fun getUnplayedCachedMedia(limit: Int, filters: Set<MediaFilter>? = null): List<Tweet> = io {
        unplayedCachedMedia(limit, filters)
    }

    private fun unplayedCachedMedia(limit: Int, filters: Set<MediaFilter>?): List<Tweet> {
        // Use a subquery to exclude any tweets whose media_key has been played elsewhere
        val results = queryTweets(
            where = RepositorySql.unplayedWhereClause(filters),
            whereArgs = emptyArray(),
            orderBy = RepositorySql.ORDER_BY_SUGGESTED,
            limit = limit * 2,
        )
        if (results.isEmpty()) return emptyList()

        // Mark these as suggested
        markSuggested(results.map { it.id })

        return results.shuffled().take(limit)
    }

    suspend fun getCachedMediaCandidates(
        limit: Int,
        avoidWatchedContent: Boolean,
        filters: Set<MediaFilter>? = null,
    ): List<Tweet> = io {
        if (avoidWatchedContent) {
            return@io unplayedCachedMedia(limit, filters)
        }

        val whereClause = RepositorySql.candidatesWhereClause(filters)
        val results = queryTweets(
            where = whereClause,
            whereArgs = if (whereClause != null) emptyArray() else null,
            orderBy = RepositorySql.ORDER_BY_SUGGESTED,
            limit = limit * 2, // Fetch more to allow for shuffling
        )

        // Mark these as suggested so they go to the back of the queue
        if (results.isNotEmpty()) {
            markSuggested(results.map { it.id })
        }

        results.shuffled().take(limit)
    }

    suspend fun markAsSuggested(ids: List<String>) = io { markSuggested(ids) }

    private fun markSuggested(ids: List<String>) {
        val db = database
        val now = System.currentTimeMillis()
        db.inTransaction {
            for (id in ids) {
                val values = ContentValues().apply { put("last_suggested_at", now) }
                db.update(tableCachedMedia, values, "id = ?", arrayOf(id))
            }
        }
    }

    suspend fun getPlayedCountsByUser(): Map<String, Int> = io {
        database.rawQuery(RepositorySql.PLAYED_COUNTS_BY_USER_SQL, null).use { c ->
            val out = LinkedHashMap<String, Int>()
            val handleIdx = c.getColumnIndexOrThrow("normalized_handle")
            val totalIdx = c.getColumnIndexOrThrow("total_played")
            while (c.moveToNext()) {
                val handle = if (c.isNull(handleIdx)) null else c.getString(handleIdx)
                if (handle == null || handle.isEmpty()) continue
                out[handle] = if (c.isNull(totalIdx)) 0 else c.getInt(totalIdx)
            }
            out
        }
    }

    suspend fun getUserCachedMedia(userHandle: String, limit: Int, filters: Set<MediaFilter>? = null): List<Tweet> =
        io {
            queryTweets(
                where = RepositorySql.userMediaWhereClause(filters),
                whereArgs = arrayOf(RepositorySql.userMediaHandleArg(userHandle)),
                orderBy = RepositorySql.ORDER_BY_CREATED_DESC,
                limit = limit,
            )
        }

    suspend fun getHashtagCachedMedia(hashtag: String, limit: Int, filters: Set<MediaFilter>? = null): List<Tweet> =
        io {
            queryTweets(
                where = RepositorySql.hashtagWhereClause(filters),
                whereArgs = arrayOf(RepositorySql.hashtagLikeArg(hashtag)),
                orderBy = RepositorySql.ORDER_BY_CREATED_DESC,
                limit = limit,
            )
        }

    suspend fun markMediaAsPlayed(id: String) = io {
        database.execSQL(
            RepositorySql.MARK_MEDIA_AS_PLAYED_SQL,
            arrayOf<Any>(System.currentTimeMillis(), id),
        )
    }

    suspend fun getMediaPlayedCount(id: String): Int = io {
        database.query(tableCachedMedia, arrayOf("played_count"), "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0
        }
    }

    suspend fun getUserPlayedCount(userHandle: String): Int = io {
        val normalized = RepositorySql.playedCountHandleArg(userHandle)
        database.rawQuery(RepositorySql.USER_PLAYED_COUNT_SQL, arrayOf(normalized)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0
        }
    }

    suspend fun getCachedMediaCount(): Int = io { cachedMediaCount(database) }

    private fun cachedMediaCount(db: SQLiteDatabase): Int =
        db.rawQuery(RepositorySql.CACHED_MEDIA_COUNT_SQL, null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    suspend fun pruneCachedMedia(threshold: Int = 5000) = io {
        val db = database

        // 1. Delete by age: Remove anything older than 7 days
        val sevenDaysAgo = RepositorySql.pruneCutoffMillis(System.currentTimeMillis())
        db.delete(tableCachedMedia, RepositorySql.PRUNE_BY_AGE_WHERE, arrayOf(sevenDaysAgo.toString()))

        // 2. Delete by count: If still over threshold, delete oldest watched items
        val count = cachedMediaCount(db)
        val deleteCount = RepositorySql.pruneDeleteCount(count, threshold)
        if (deleteCount > 0) {
            db.execSQL(RepositorySql.PRUNE_OLDEST_SQL, arrayOf<Any>(deleteCount))
        }

        // Extras of posts that are gone from the cache and are not bookmarked.
        if (extrasTable(db)) {
            try {
                db.execSQL(
                    "DELETE FROM $tableTweetExtras WHERE id NOT IN (SELECT id FROM $tableCachedMedia) " +
                        "AND id NOT IN (SELECT ${BookmarkFields.tweetId} FROM ${BookmarkFields.tableBookmarks})",
                )
            } catch (e: Exception) {
                AppLogger.log("XFLOW: Could not prune post extras: $e")
            }
        }
    }

    suspend fun purgeSeenMetadata() = io {
        database.delete(tableCachedMedia, RepositorySql.PURGE_SEEN_WHERE, null)
        Unit
    }

    // ---- bookmarks ---------------------------------------------------------

    suspend fun insertBookmark(tweet: Tweet) = io {
        val values = ContentValues().apply {
            put(BookmarkFields.tweetId, tweet.id)
            put(BookmarkFields.savedAt, System.currentTimeMillis())
        }
        database.insertWithOnConflict(BookmarkFields.tableBookmarks, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    suspend fun removeBookmark(tweetId: String) = io {
        database.delete(BookmarkFields.tableBookmarks, "${BookmarkFields.tweetId} = ?", arrayOf(tweetId))
        Unit
    }

    suspend fun isBookmarked(tweetId: String): Boolean = io {
        database.query(
            BookmarkFields.tableBookmarks,
            arrayOf(BookmarkFields.tweetId),
            "${BookmarkFields.tweetId} = ?",
            arrayOf(tweetId),
            null,
            null,
            null,
            "1",
        ).use { c -> c.count > 0 }
    }

    suspend fun getBookmarks(): List<Tweet> = io {
        database.rawQuery(RepositorySql.BOOKMARKS_SQL, null).use { c -> c.readTweets(isBookmarked = true) }
    }

    suspend fun close() = io {
        synchronized(lock) {
            helper?.close()
            helper = null
        }
    }

    // ---- row mapping -------------------------------------------------------

    private fun queryTweets(where: String?, whereArgs: Array<String>?, orderBy: String, limit: Int): List<Tweet> =
        database.query(tableCachedMedia, null, where, whereArgs, null, null, orderBy, limit.toString())
            .use { c -> c.readTweets(isBookmarked = false) }

    private fun Cursor.string(column: String): String? {
        val idx = getColumnIndex(column)
        return if (idx < 0 || isNull(idx)) null else getString(idx)
    }

    private fun Cursor.intOrNull(column: String): Int? {
        val idx = getColumnIndex(column)
        return if (idx < 0 || isNull(idx)) null else getInt(idx)
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val idx = getColumnIndex(column)
        return if (idx < 0 || isNull(idx)) null else getLong(idx)
    }

    /**
     * Reads `cached_media` rows in the formats the Flutter app wrote:
     * `media_urls` a JSON array, `is_video` 0/1, `created_at` epoch millis.
     * isBookmarked is only set by the bookmarks query; the other queries do
     * not restore it.
     */
    private fun Cursor.readTweets(isBookmarked: Boolean): List<Tweet> {
        val out = ArrayList<Tweet>(count)
        while (moveToNext()) {
            out.add(
                Tweet(
                    id = string("id") ?: continue,
                    text = string("text") ?: "",
                    userHandle = string("user_handle") ?: "",
                    userAvatarUrl = string("user_avatar_url"),
                    mediaKey = string("media_key"),
                    mediaUrls = RepositorySql.decodeMediaUrls(string("media_urls")),
                    thumbnailUrl = string("thumbnail_url"),
                    isVideo = intOrNull("is_video") == 1,
                    createdAtMillis = longOrNull("created_at"),
                    favoriteCount = intOrNull("favorite_count") ?: 0,
                    replyCount = intOrNull("reply_count") ?: 0,
                    retweetCount = intOrNull("retweet_count") ?: 0,
                    viewCount = intOrNull("view_count") ?: 0,
                    isBookmarked = isBookmarked,
                )
            )
        }
        return withExtras(out)
    }

    /** Puts the stored language and playlist back on posts read from the cache. */
    private fun withExtras(tweets: List<Tweet>): List<Tweet> {
        if (tweets.isEmpty()) return tweets
        val db = database
        if (!extrasTable(db)) return tweets
        return try {
            val extras = HashMap<String, Pair<String?, String?>>()
            db.rawQuery("SELECT id, lang, hls_url FROM $tableTweetExtras", null).use { c ->
                while (c.moveToNext()) {
                    extras[c.getString(0)] =
                        (if (c.isNull(1)) null else c.getString(1)) to (if (c.isNull(2)) null else c.getString(2))
                }
            }
            if (extras.isEmpty()) return tweets
            tweets.map { tweet ->
                val found = extras[tweet.id] ?: return@map tweet
                tweet.copy(lang = found.first, hlsUrl = found.second)
            }
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Could not read post extras: $e")
            tweets
        }
    }
}
