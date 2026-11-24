package com.alchemistaloha.xflow.core.link

import java.net.URI

/** Where a tapped link goes. */
sealed interface LinkTarget {
    /** A profile on X: opens the profile screen of the app. [screenName] has no '@'. */
    data class Profile(val screenName: String) : LinkTarget

    /** A hashtag page on X: opens the hashtag feed of the app. [hashtag] starts with '#'. */
    data class Hashtag(val hashtag: String) : LinkTarget

    /** Any other web page: opens in the in-app browser. */
    data class Browser(val url: String) : LinkTarget

    /** Not a web page (mailto:, tel:, market:, intent:, ...): handed to the system. */
    data class System(val url: String) : LinkTarget
}

/** Decides what opens a link: a screen of the app, the in-app browser or the system. */
object LinkRouter {
    private val xHosts = setOf("x.com", "twitter.com")
    private val hostPrefixes = listOf("www.", "mobile.", "m.")
    private val screenNamePattern = Regex("^[A-Za-z0-9_]{1,15}$")
    private val hashtagPattern = Regex("^[\\p{L}\\p{N}_]+$")
    private val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /** First path segments of x.com that are pages of the site, not accounts. */
    private val reservedPaths = setOf(
        "home", "explore", "search", "notifications", "messages", "compose", "settings", "i",
        "login", "logout", "signup", "account", "tos", "privacy", "hashtag", "intent", "share",
        "about", "jobs", "download", "help", "communities", "lists", "bookmarks", "premium",
        "verified", "who_to_follow", "following", "followers", "topics", "trends", "grok",
    )

    fun classify(raw: String): LinkTarget {
        val url = raw.trim()
        if (url.isEmpty()) return LinkTarget.System(url)
        val scheme = schemePattern.find(url)?.value?.dropLast(1)?.lowercase()
        // "example.com/page" without a scheme is still a web page.
        if (scheme == null) return classify("https://$url")
        if (scheme != "http" && scheme != "https") return LinkTarget.System(url)

        val uri = try {
            URI(url)
        } catch (_: Exception) {
            // Not a strict URI (spaces, unescaped characters): the browser can still try it.
            return LinkTarget.Browser(url)
        }
        val host = baseHost(uri.host) ?: return LinkTarget.Browser(url)
        if (host !in xHosts) return LinkTarget.Browser(url)

        val segments = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
        if (segments.size == 1) {
            val name = segments[0]
            if (screenNamePattern.matches(name) && name.lowercase() !in reservedPaths) {
                return LinkTarget.Profile(name)
            }
        }
        if (segments.size == 2 && segments[0].lowercase() == "hashtag" && hashtagPattern.matches(segments[1])) {
            return LinkTarget.Hashtag("#${segments[1]}")
        }
        return LinkTarget.Browser(url)
    }

    /** True for a page the in-app browser itself loads (http or https). */
    fun isWebUrl(raw: String): Boolean {
        val scheme = schemePattern.find(raw.trim())?.value?.dropLast(1)?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** The host to show under the page title: no "www.", lower case. Empty when there is none. */
    fun displayHost(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val host = try {
            URI(raw.trim()).host
        } catch (_: Exception) {
            null
        } ?: Regex("^[A-Za-z][A-Za-z0-9+.-]*://([^/?#:]+)").find(raw.trim())?.groupValues?.get(1)
        return host?.lowercase()?.removePrefix("www.") ?: ""
    }

    private fun baseHost(host: String?): String? {
        var h = host?.lowercase()?.trimEnd('.') ?: return null
        for (prefix in hostPrefixes) {
            if (h.startsWith(prefix)) {
                h = h.removePrefix(prefix)
                break
            }
        }
        return h
    }
}
