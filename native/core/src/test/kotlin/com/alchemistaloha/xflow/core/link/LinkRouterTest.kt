package com.alchemistaloha.xflow.core.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkRouterTest {
    @Test
    fun ordinaryWebPagesOpenInTheBrowser() {
        assertEquals(LinkTarget.Browser("https://example.com/a?b=1"), LinkRouter.classify("https://example.com/a?b=1"))
        assertEquals(LinkTarget.Browser("http://example.com"), LinkRouter.classify("http://example.com"))
        assertEquals(LinkTarget.Browser("https://t.co/AbCdEf123"), LinkRouter.classify(" https://t.co/AbCdEf123 "))
        assertEquals(LinkTarget.Browser("HTTPS://Example.com/"), LinkRouter.classify("HTTPS://Example.com/"))
    }

    @Test
    fun aLinkWithoutSchemeIsTreatedAsHttps() {
        assertEquals(LinkTarget.Browser("https://example.com/page"), LinkRouter.classify("example.com/page"))
        assertEquals(LinkTarget.Profile("someone"), LinkRouter.classify("x.com/someone"))
    }

    @Test
    fun otherSchemesGoToTheSystem() {
        for (url in listOf(
            "mailto:a@example.com",
            "tel:+123",
            "market://details?id=com.example",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "file:///sdcard/a.txt",
            "content://media/1",
        )) {
            assertEquals(LinkTarget.System(url), LinkRouter.classify(url), url)
        }
        assertEquals(LinkTarget.System(""), LinkRouter.classify("   "))
    }

    @Test
    fun profileLinksOpenTheProfileScreen() {
        assertEquals(LinkTarget.Profile("some_one1"), LinkRouter.classify("https://x.com/some_one1"))
        assertEquals(LinkTarget.Profile("someone"), LinkRouter.classify("https://twitter.com/someone/"))
        assertEquals(LinkTarget.Profile("someone"), LinkRouter.classify("https://www.x.com/someone?s=20"))
        assertEquals(LinkTarget.Profile("someone"), LinkRouter.classify("http://mobile.twitter.com/someone#top"))
        assertEquals(LinkTarget.Profile("Someone"), LinkRouter.classify("https://X.COM/Someone"))
    }

    @Test
    fun pagesOfXThatAreNotProfilesOpenInTheBrowser() {
        for (url in listOf(
            "https://x.com/",
            "https://x.com",
            "https://x.com/home",
            "https://x.com/Explore",
            "https://x.com/i/flow/login",
            "https://x.com/search?q=cats",
            "https://x.com/someone/status/123456",
            "https://x.com/someone/media",
            "https://x.com/settings",
            "https://x.com/a-b",
            "https://x.com/name_longer_than_15",
            "https://x.com/hashtag",
            "https://x.com/hashtag/a/b",
            "https://x.com/hashtag/a-b",
        )) {
            assertEquals(LinkTarget.Browser(url), LinkRouter.classify(url), url)
        }
    }

    @Test
    fun aLookalikeHostIsNotX() {
        for (url in listOf(
            "https://notx.com/someone",
            "https://x.com.evil.example/someone",
            "https://evil.example/x.com/someone",
            "https://x.com@evil.example/someone",
            "https://api.x.com/someone",
        )) {
            assertEquals(LinkTarget.Browser(url), LinkRouter.classify(url), url)
        }
    }

    @Test
    fun hashtagLinksOpenTheHashtagFeed() {
        assertEquals(LinkTarget.Hashtag("#nature"), LinkRouter.classify("https://x.com/hashtag/nature"))
        assertEquals(LinkTarget.Hashtag("#nature"), LinkRouter.classify("https://twitter.com/hashtag/nature?src=hash"))
    }

    @Test
    fun aUrlThatIsNotAStrictUriStillOpensInTheBrowser() {
        val url = "https://example.com/a b|c"
        assertEquals(LinkTarget.Browser(url), LinkRouter.classify(url))
    }

    @Test
    fun isWebUrlAcceptsOnlyHttpAndHttps() {
        assertTrue(LinkRouter.isWebUrl("https://example.com"))
        assertTrue(LinkRouter.isWebUrl(" HTTP://example.com"))
        assertFalse(LinkRouter.isWebUrl("intent://x#Intent;end"))
        assertFalse(LinkRouter.isWebUrl("about:blank"))
        assertFalse(LinkRouter.isWebUrl("example.com"))
    }

    @Test
    fun displayHostDropsWwwAndCase() {
        assertEquals("example.com", LinkRouter.displayHost("https://WWW.Example.com/path?q=1"))
        assertEquals("sub.example.com", LinkRouter.displayHost("http://sub.example.com:8080/"))
        assertEquals("example.com", LinkRouter.displayHost("https://example.com/a b"))
        assertEquals("", LinkRouter.displayHost(null))
        assertEquals("", LinkRouter.displayHost("about:blank"))
    }
}
