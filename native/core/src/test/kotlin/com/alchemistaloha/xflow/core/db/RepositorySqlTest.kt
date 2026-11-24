package com.alchemistaloha.xflow.core.db

import com.alchemistaloha.xflow.core.model.MediaFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Dart tests in test/core/database/repository_test.dart run against a real
 * SQLite database, which is not available on the plain JVM. These cover the
 * same behaviour at the level of the SQL the repository sends.
 */
class RepositorySqlTest {
    @Test
    fun `database keeps the Flutter file name and version`() {
        assertEquals("xflow.db", RepositorySql.DATABASE_NAME)
        assertEquals(10, RepositorySql.DATABASE_VERSION)
    }

    @Test
    fun `fresh schema creates the four tables bookmarks and three indexes`() {
        val sql = RepositorySql.createStatements
        assertEquals(8, sql.size)
        assertEquals(
            "CREATE TABLE accounts (id TEXT PRIMARY KEY, screen_name TEXT, rest_id TEXT, auth_header TEXT)",
            sql[0],
        )
        assertEquals(
            "CREATE TABLE subscriptions (id TEXT PRIMARY KEY, screen_name TEXT, name TEXT, profile_image_url TEXT, description TEXT, followers_count INTEGER, following_count INTEGER)",
            sql[1],
        )
        assertEquals("CREATE TABLE hashtags (tag TEXT PRIMARY KEY, added_at INTEGER)", sql[2])
        val cached = sql[3].split(Regex("\\s+")).joinToString(" ").trim()
        assertEquals(
            "CREATE TABLE cached_media ( id TEXT PRIMARY KEY, text TEXT, user_handle TEXT, user_avatar_url TEXT, " +
                "media_key TEXT, media_urls TEXT, thumbnail_url TEXT, is_video INTEGER, created_at INTEGER, " +
                "played_count INTEGER DEFAULT 0, last_played_at INTEGER, duration_watched INTEGER DEFAULT 0, " +
                "last_suggested_at INTEGER, favorite_count INTEGER NOT NULL DEFAULT 0, " +
                "reply_count INTEGER NOT NULL DEFAULT 0, retweet_count INTEGER NOT NULL DEFAULT 0, " +
                "view_count INTEGER NOT NULL DEFAULT 0 )",
            cached,
        )
        assertEquals(
            "CREATE TABLE bookmarks ( id INTEGER PRIMARY KEY AUTOINCREMENT, tweet_id TEXT NOT NULL UNIQUE, saved_at INTEGER NOT NULL )",
            sql[4].split(Regex("\\s+")).joinToString(" ").trim(),
        )
        assertEquals("CREATE INDEX idx_discovery_lookup ON cached_media (played_count, created_at DESC)", sql[5])
        assertEquals("CREATE INDEX idx_media_key ON cached_media (media_key)", sql[6])
        assertEquals("CREATE INDEX idx_suggested ON cached_media (last_suggested_at)", sql[7])
    }

    @Test
    fun `no statement drops or deletes anything`() {
        val all = RepositorySql.createStatements + (1..10).flatMap { RepositorySql.upgradeStatements(it) }
        assertFalse(all.any { it.contains("DROP", ignoreCase = true) || it.contains("DELETE", ignoreCase = true) })
    }

    @Test
    fun `current version needs no upgrade`() {
        assertTrue(RepositorySql.upgradeStatements(10).isEmpty())
    }

    @Test
    fun `upgrade from 9 adds the counters and the bookmarks table`() {
        val sql = RepositorySql.upgradeStatements(9)
        assertEquals(5, sql.size)
        assertEquals("ALTER TABLE cached_media ADD COLUMN favorite_count INTEGER NOT NULL DEFAULT 0", sql[0])
        assertEquals("ALTER TABLE cached_media ADD COLUMN reply_count INTEGER NOT NULL DEFAULT 0", sql[1])
        assertEquals("ALTER TABLE cached_media ADD COLUMN retweet_count INTEGER NOT NULL DEFAULT 0", sql[2])
        assertEquals("ALTER TABLE cached_media ADD COLUMN view_count INTEGER NOT NULL DEFAULT 0", sql[3])
        assertEquals(createBookmarksTable, sql[4])
    }

    @Test
    fun `upgrade steps run in version order`() {
        assertEquals(17, RepositorySql.upgradeStatements(1).size)
        assertEquals("ALTER TABLE accounts ADD COLUMN rest_id TEXT", RepositorySql.upgradeStatements(1).first())
        assertEquals(
            listOf(
                "ALTER TABLE cached_media ADD COLUMN media_key TEXT",
                "CREATE INDEX IF NOT EXISTS idx_media_key ON cached_media (media_key)",
                "CREATE TABLE IF NOT EXISTS hashtags (tag TEXT PRIMARY KEY, added_at INTEGER)",
                "ALTER TABLE cached_media ADD COLUMN last_suggested_at INTEGER",
                "CREATE INDEX IF NOT EXISTS idx_suggested ON cached_media (last_suggested_at)",
            ),
            RepositorySql.upgradeStatements(6).take(5),
        )
        assertEquals(
            listOf(
                "ALTER TABLE subscriptions ADD COLUMN description TEXT",
                "ALTER TABLE subscriptions ADD COLUMN followers_count INTEGER",
                "ALTER TABLE subscriptions ADD COLUMN following_count INTEGER",
            ),
            RepositorySql.upgradeStatements(3).take(3),
        )
    }

    @Test
    fun `media_urls is stored as compact JSON and an empty list is exactly the text the filters compare`() {
        assertEquals("[]", RepositorySql.encodeMediaUrls(emptyList()))
        assertEquals(
            "[\"https://test.com/1.mp4\",\"https://test.com/2.jpg\"]",
            RepositorySql.encodeMediaUrls(listOf("https://test.com/1.mp4", "https://test.com/2.jpg")),
        )
    }

    @Test
    fun `media_urls written by the Flutter app reads back`() {
        assertEquals(listOf("https://test.com/1.mp4"), RepositorySql.decodeMediaUrls("[\"https://test.com/1.mp4\"]"))
        assertEquals(listOf("a", "b"), RepositorySql.decodeMediaUrls("[\"a\",\"b\"]"))
        assertEquals(emptyList<String>(), RepositorySql.decodeMediaUrls("[]"))
        assertEquals(emptyList<String>(), RepositorySql.decodeMediaUrls(null))
        assertEquals(emptyList<String>(), RepositorySql.decodeMediaUrls("not json"))
        val odd = listOf("https://x.com/a?b=1&c=\"q\"", "line\nbreak", "ü\\n")
        assertEquals(odd, RepositorySql.decodeMediaUrls(RepositorySql.encodeMediaUrls(odd)))
    }

    @Test
    fun `filter conditions follow the selected filters`() {
        assertTrue(RepositorySql.mediaFilterConditions(null).isEmpty())
        assertTrue(RepositorySql.mediaFilterConditions(emptySet()).isEmpty())
        assertEquals(
            listOf("is_video = 1", "(media_urls != \"[]\" AND is_video = 0)", "media_urls = \"[]\""),
            RepositorySql.mediaFilterConditions(linkedSetOf(MediaFilter.video, MediaFilter.image, MediaFilter.text)),
        )
    }

    @Test
    fun `unplayed query excludes media already played under another id`() {
        val base = "played_count = 0 AND (media_key IS NULL OR media_key NOT IN (SELECT media_key FROM cached_media WHERE played_count > 0 AND media_key IS NOT NULL))"
        assertEquals(base, RepositorySql.unplayedWhereClause(null))
        assertEquals(
            "$base AND (is_video = 1 OR media_urls = \"[]\")",
            RepositorySql.unplayedWhereClause(linkedSetOf(MediaFilter.video, MediaFilter.text)),
        )
    }

    @Test
    fun `candidates query has no WHERE without filters`() {
        assertNull(RepositorySql.candidatesWhereClause(null))
        assertNull(RepositorySql.candidatesWhereClause(emptySet()))
        assertEquals(
            "((media_urls != \"[]\" AND is_video = 0))",
            RepositorySql.candidatesWhereClause(setOf(MediaFilter.image)),
        )
    }

    // Dart: "getUserCachedMedia matches handles case-insensitively"
    @Test
    fun `user media query matches handles case-insensitively`() {
        assertEquals("LOWER(REPLACE(user_handle, '@', '')) = ?", RepositorySql.userMediaWhereClause(null))
        assertEquals("mixedcaseuser", RepositorySql.userMediaHandleArg("mixedcaseuser"))
        assertEquals("mixedcaseuser", RepositorySql.userMediaHandleArg("@MixedCaseUser"))
        assertEquals(
            "LOWER(REPLACE(user_handle, '@', '')) = ? AND (is_video = 1)",
            RepositorySql.userMediaWhereClause(setOf(MediaFilter.video)),
        )
        assertEquals("some@user", RepositorySql.userMediaHandleArg("@Some@User"))
        assertEquals("someuser", RepositorySql.playedCountHandleArg("@Some@User"))
    }

    @Test
    fun `hashtag query searches the text`() {
        assertEquals("text LIKE ?", RepositorySql.hashtagWhereClause(null))
        assertEquals("%#nature%", RepositorySql.hashtagLikeArg("#nature"))
        assertEquals("text LIKE ? AND (is_video = 1)", RepositorySql.hashtagWhereClause(setOf(MediaFilter.video)))
    }

    // Dart: "markMediaAsPlayed updates played_count and last_played_at"
    @Test
    fun `mark as played bumps the count and stamps the time for one id`() {
        val sql = RepositorySql.MARK_MEDIA_AS_PLAYED_SQL.split(Regex("\\s+")).joinToString(" ").trim()
        assertEquals(
            "UPDATE cached_media SET played_count = played_count + 1, last_played_at = ? WHERE id = ?",
            sql,
        )
    }

    // Dart: "pruneCachedMedia removes oldest WATCHED items when limit exceeded"
    @Test
    fun `prune deletes only the overflow oldest first`() {
        assertEquals(2, RepositorySql.pruneDeleteCount(count = 5, threshold = 3))
        assertEquals(0, RepositorySql.pruneDeleteCount(count = 3, threshold = 3))
        assertEquals(0, RepositorySql.pruneDeleteCount(count = 1, threshold = 5000))
        val sql = RepositorySql.PRUNE_OLDEST_SQL.split(Regex("\\s+")).joinToString(" ").trim()
        assertEquals(
            "DELETE FROM cached_media WHERE id IN ( SELECT id FROM cached_media ORDER BY COALESCE(last_played_at, created_at) ASC LIMIT ? )",
            sql,
        )
        assertEquals("created_at < ?", RepositorySql.PRUNE_BY_AGE_WHERE)
        assertEquals(1_000_000_000_000L - 604_800_000L, RepositorySql.pruneCutoffMillis(1_000_000_000_000L))
    }

    @Test
    fun `purge keeps bookmarked rows`() {
        assertEquals(
            "played_count > 0 AND NOT EXISTS (SELECT 1 FROM bookmarks WHERE tweet_id = cached_media.id)",
            RepositorySql.PURGE_SEEN_WHERE,
        )
    }

    @Test
    fun `bookmarks are read newest first through the cache`() {
        assertEquals(
            "SELECT cm.* FROM bookmarks bm INNER JOIN cached_media cm ON cm.id = bm.tweet_id ORDER BY bm.saved_at DESC",
            RepositorySql.BOOKMARKS_SQL.split(Regex("\\s+")).joinToString(" ").trim(),
        )
    }
}
