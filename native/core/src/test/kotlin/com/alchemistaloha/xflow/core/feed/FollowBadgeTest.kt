package com.alchemistaloha.xflow.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowBadgeTest {
    @Test
    fun notFollowedShowsPlus() {
        assertEquals(FollowBadge.Plus, FollowBadgeLogic.badgeFor(isSubscribed = false, followedHere = false))
    }

    @Test
    fun followedBeforeShowsNothing() {
        assertEquals(FollowBadge.Hidden, FollowBadgeLogic.badgeFor(isSubscribed = true, followedHere = false))
    }

    @Test
    fun followedFromTheBadgeShowsTheTick() {
        assertEquals(FollowBadge.Confirmed, FollowBadgeLogic.badgeFor(isSubscribed = true, followedHere = true))
    }

    @Test
    fun aFollowThatWasRolledBackShowsPlusAgain() {
        assertEquals(FollowBadge.Plus, FollowBadgeLogic.badgeFor(isSubscribed = false, followedHere = true))
    }

    @Test
    fun ownAccountNeverShowsABadge() {
        assertEquals(
            FollowBadge.Hidden,
            FollowBadgeLogic.badgeFor(isSubscribed = false, followedHere = false, isOwnAccount = true),
        )
    }

    @Test
    fun ownAccountIgnoresAtSignAndCase() {
        assertTrue(FollowBadgeLogic.isOwnAccount("@Someone", "someone"))
        assertTrue(FollowBadgeLogic.isOwnAccount("someone", "@SOMEONE"))
        assertFalse(FollowBadgeLogic.isOwnAccount("@someone", "someone_else"))
        assertFalse(FollowBadgeLogic.isOwnAccount("@someone", null))
        assertFalse(FollowBadgeLogic.isOwnAccount("@someone", ""))
    }
}
