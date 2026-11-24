package com.alchemistaloha.xflow.core.db

import com.alchemistaloha.xflow.core.model.MediaFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

const val tableAccounts = "accounts"
const val tableSubscriptions = "subscriptions"
const val tableCachedMedia = "cached_media"
const val tableHashtags = "hashtags"

object BookmarkFields {
    const val tableBookmarks = "bookmarks"
    const val id = "id"
    const val tweetId = "tweet_id"
    const val savedAt = "saved_at"
}

const val createBookmarksTable = """
  CREATE TABLE ${BookmarkFields.tableBookmarks} (
    ${BookmarkFields.id} INTEGER PRIMARY KEY AUTOINCREMENT,
    ${BookmarkFields.tweetId} TEXT NOT NULL UNIQUE,
    ${BookmarkFields.savedAt} INTEGER NOT NULL
  )
"""

/**
 * Every SQL string and WHERE clause of the repository, kept free of Android
 * so it can be unit-tested. The schema, version and upgrade steps are the
 * ones the Flutter app (sqflite) used: an existing `xflow.db` opens unchanged.
 */
object RepositorySql {
    /** Same file sqflite used: `context.getDatabasePath("xflow.db")`. */
    const val DATABASE_NAME = "xflow.db"
    const val DATABASE_VERSION = 10

    /** Statements run when the database file does not exist yet (Dart `onCreate`). */
    val createStatements: List<String> = listOf(
        "CREATE TABLE $tableAccounts (id TEXT PRIMARY KEY, screen_name TEXT, rest_id TEXT, auth_header TEXT)",
        "CREATE TABLE $tableSubscriptions (id TEXT PRIMARY KEY, screen_name TEXT, name TEXT, profile_image_url TEXT, description TEXT, followers_count INTEGER, following_count INTEGER)",
        "CREATE TABLE $tableHashtags (tag TEXT PRIMARY KEY, added_at INTEGER)",
        """
          CREATE TABLE $tableCachedMedia (
            id TEXT PRIMARY KEY,
            text TEXT,
            user_handle TEXT,
            user_avatar_url TEXT,
            media_key TEXT,
            media_urls TEXT,
            thumbnail_url TEXT,
            is_video INTEGER,
            created_at INTEGER,
            played_count INTEGER DEFAULT 0,
            last_played_at INTEGER,
            duration_watched INTEGER DEFAULT 0,
            last_suggested_at INTEGER,
            favorite_count INTEGER NOT NULL DEFAULT 0,
            reply_count INTEGER NOT NULL DEFAULT 0,
            retweet_count INTEGER NOT NULL DEFAULT 0,
            view_count INTEGER NOT NULL DEFAULT 0
          )
        """,
        createBookmarksTable,
        "CREATE INDEX idx_discovery_lookup ON $tableCachedMedia (played_count, created_at DESC)",
        "CREATE INDEX idx_media_key ON $tableCachedMedia (media_key)",
        "CREATE INDEX idx_suggested ON $tableCachedMedia (last_suggested_at)",
    )

    /** Statements that bring a database at [oldVersion] up to version 10 (Dart `onUpgrade`). */
    fun upgradeStatements(oldVersion: Int): List<String> {
        val out = ArrayList<String>()
        if (oldVersion < 2) {
            out += "ALTER TABLE $tableAccounts ADD COLUMN rest_id TEXT"
        }
        if (oldVersion < 3) {
            out += "CREATE TABLE IF NOT EXISTS $tableSubscriptions (id TEXT PRIMARY KEY, screen_name TEXT, name TEXT, profile_image_url TEXT)"
        }
        if (oldVersion < 4) {
            out += "ALTER TABLE $tableSubscriptions ADD COLUMN description TEXT"
            out += "ALTER TABLE $tableSubscriptions ADD COLUMN followers_count INTEGER"
            out += "ALTER TABLE $tableSubscriptions ADD COLUMN following_count INTEGER"
        }
        if (oldVersion < 5) {
            out += """
            CREATE TABLE IF NOT EXISTS $tableCachedMedia (
              id TEXT PRIMARY KEY,
              text TEXT,
              user_handle TEXT,
              user_avatar_url TEXT,
              media_urls TEXT,
              thumbnail_url TEXT,
              is_video INTEGER,
              created_at INTEGER,
              played_count INTEGER DEFAULT 0,
              last_played_at INTEGER,
              duration_watched INTEGER DEFAULT 0
            )
          """
        }
        if (oldVersion < 6) {
            out += "CREATE INDEX IF NOT EXISTS idx_discovery_lookup ON $tableCachedMedia (played_count, created_at DESC)"
        }
        if (oldVersion < 7) {
            out += "ALTER TABLE $tableCachedMedia ADD COLUMN media_key TEXT"
            out += "CREATE INDEX IF NOT EXISTS idx_media_key ON $tableCachedMedia (media_key)"
        }
        if (oldVersion < 8) {
            out += "CREATE TABLE IF NOT EXISTS $tableHashtags (tag TEXT PRIMARY KEY, added_at INTEGER)"
        }
        if (oldVersion < 9) {
            out += "ALTER TABLE $tableCachedMedia ADD COLUMN last_suggested_at INTEGER"
            out += "CREATE INDEX IF NOT EXISTS idx_suggested ON $tableCachedMedia (last_suggested_at)"
        }
        if (oldVersion < 10) {
            out += "ALTER TABLE $tableCachedMedia ADD COLUMN favorite_count INTEGER NOT NULL DEFAULT 0"
            out += "ALTER TABLE $tableCachedMedia ADD COLUMN reply_count INTEGER NOT NULL DEFAULT 0"
            out += "ALTER TABLE $tableCachedMedia ADD COLUMN retweet_count INTEGER NOT NULL DEFAULT 0"
            out += "ALTER TABLE $tableCachedMedia ADD COLUMN view_count INTEGER NOT NULL DEFAULT 0"
            out += createBookmarksTable
        }
        return out
    }

    // ---- WHERE clauses -------------------------------------------------

    /** One SQL condition per selected filter, in the set's iteration order. */
    fun mediaFilterConditions(filters: Set<MediaFilter>?): List<String> {
        if (filters == null || filters.isEmpty()) return emptyList()
        return filters.map { filter ->
            when (filter) {
                MediaFilter.video -> "is_video = 1"
                MediaFilter.image -> "(media_urls != \"[]\" AND is_video = 0)"
                MediaFilter.text -> "media_urls = \"[]\""
            }
        }
    }

    private fun filterSuffix(filters: Set<MediaFilter>?): String {
        val conditions = mediaFilterConditions(filters)
        return if (conditions.isNotEmpty()) " AND (${conditions.joinToString(" OR ")})" else ""
    }

    /** `getUnplayedCachedMedia`: unplayed rows whose media was not played under another id. */
    fun unplayedWhereClause(filters: Set<MediaFilter>?): String {
        var whereClause = "played_count = 0"
        whereClause +=
            " AND (media_key IS NULL OR media_key NOT IN (SELECT media_key FROM $tableCachedMedia WHERE played_count > 0 AND media_key IS NOT NULL))"
        return whereClause + filterSuffix(filters)
    }

    /** `getCachedMediaCandidates` without `avoidWatchedContent`: null means "no WHERE". */
    fun candidatesWhereClause(filters: Set<MediaFilter>?): String? {
        val conditions = mediaFilterConditions(filters)
        return if (conditions.isNotEmpty()) "(${conditions.joinToString(" OR ")})" else null
    }

    fun userMediaWhereClause(filters: Set<MediaFilter>?): String =
        "LOWER(REPLACE(user_handle, '@', '')) = ?" + filterSuffix(filters)

    /** The bound argument of [userMediaWhereClause]: one leading '@' removed, lower-cased. */
    fun userMediaHandleArg(userHandle: String): String {
        val rawHandle = if (userHandle.startsWith("@")) userHandle.substring(1) else userHandle
        return rawHandle.lowercase()
    }

    fun hashtagWhereClause(filters: Set<MediaFilter>?): String = "text LIKE ?" + filterSuffix(filters)

    fun hashtagLikeArg(hashtag: String): String = "%$hashtag%"

    /** The bound argument of [USER_PLAYED_COUNT_SQL]: every '@' removed, lower-cased. */
    fun playedCountHandleArg(userHandle: String): String = userHandle.replace("@", "").lowercase()

    // ---- Raw statements ------------------------------------------------

    const val ORDER_BY_SUGGESTED = "last_suggested_at ASC"
    const val ORDER_BY_CREATED_DESC = "created_at DESC"

    const val PLAYED_COUNTS_BY_USER_SQL = """
      SELECT LOWER(REPLACE(user_handle, '@', '')) AS normalized_handle,
             SUM(played_count) AS total_played
      FROM $tableCachedMedia
      GROUP BY normalized_handle
    """

    const val MARK_MEDIA_AS_PLAYED_SQL = """
      UPDATE $tableCachedMedia 
      SET played_count = played_count + 1, last_played_at = ? 
      WHERE id = ?
    """

    const val USER_PLAYED_COUNT_SQL = """
      SELECT SUM(played_count) AS total_played
      FROM $tableCachedMedia
      WHERE LOWER(REPLACE(user_handle, '@', '')) = ?
    """

    const val CACHED_MEDIA_COUNT_SQL = "SELECT COUNT(*) as count FROM $tableCachedMedia"

    const val PRUNE_BY_AGE_WHERE = "created_at < ?"

    const val PRUNE_OLDEST_SQL = """
        DELETE FROM $tableCachedMedia 
        WHERE id IN (
          SELECT id FROM $tableCachedMedia 
          ORDER BY COALESCE(last_played_at, created_at) ASC 
          LIMIT ?
        )
      """

    const val PURGE_SEEN_WHERE =
        "played_count > 0 AND NOT EXISTS (SELECT 1 FROM ${BookmarkFields.tableBookmarks} WHERE ${BookmarkFields.tweetId} = $tableCachedMedia.id)"

    const val BOOKMARKS_SQL = """
      SELECT cm.*
      FROM ${BookmarkFields.tableBookmarks} bm
      INNER JOIN $tableCachedMedia cm ON cm.id = bm.${BookmarkFields.tweetId}
      ORDER BY bm.${BookmarkFields.savedAt} DESC
    """

    private const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** Rows created before this instant are pruned (7 days before [nowMillis]). */
    fun pruneCutoffMillis(nowMillis: Long): Long = nowMillis - SEVEN_DAYS_MILLIS

    /** How many of the oldest rows to delete so [count] drops to [threshold]; 0 when under it. */
    fun pruneDeleteCount(count: Int, threshold: Int): Int = if (count > threshold) count - threshold else 0

    // ---- Column formats --------------------------------------------------

    /** `media_urls` column: a compact JSON array, `[]` when empty (the filters compare against that text). */
    fun encodeMediaUrls(mediaUrls: List<String>): String =
        buildJsonArray { mediaUrls.forEach { add(JsonPrimitive(it)) } }.toString()

    /** Reads a `media_urls` column. Null or unreadable text gives an empty list. */
    fun decodeMediaUrls(stored: String?): List<String> {
        if (stored == null) return emptyList()
        return try {
            val element = Json.parseToJsonElement(stored)
            if (element !is JsonArray) return emptyList()
            element.mapNotNull { item ->
                if (item is JsonPrimitive && item !is JsonNull) item.content else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
