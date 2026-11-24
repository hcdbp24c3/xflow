#!/usr/bin/env python3
"""Writes the hand-made X GraphQL responses in ../responses.

None of this is real X data: the shapes follow what the X web client receives
(instructions, entries, modules, tweet_results, user_results, legacy ...), the
values are invented. Run from anywhere: python3 gen_fixtures.py
"""
import json
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "responses")
os.makedirs(OUT, exist_ok=True)


# --------------------------------------------------------------------------
# building blocks
# --------------------------------------------------------------------------

def user_legacy(uid, screen_name, name, **extra):
    """User result the way X sent it until 2025: everything under legacy."""
    u = {
        "__typename": "User",
        "id": "VXNlcjo" + uid,
        "rest_id": uid,
        "affiliates_highlighted_label": {},
        "has_graduated_access": True,
        "is_blue_verified": False,
        "profile_image_shape": "Circle",
        "legacy": {
            "can_dm": False,
            "created_at": "Tue Mar 21 20:50:14 +0000 2006",
            "default_profile": False,
            "description": "bio of " + name,
            "entities": {"description": {"urls": []}},
            "favourites_count": 1234,
            "followers_count": 5678,
            "friends_count": 321,
            "listed_count": 12,
            "location": "Hà Nội",
            "media_count": 987,
            "name": name,
            "normal_followers_count": 5678,
            "profile_banner_url": "https://pbs.twimg.com/profile_banners/%s/1700000000" % uid,
            "profile_image_url_https": "https://pbs.twimg.com/profile_images/%s/av_normal.jpg" % uid,
            "screen_name": screen_name,
            "statuses_count": 4321,
            "verified": False,
        },
    }
    u.update(extra)
    return u


def user_new(uid, screen_name, name, **extra):
    """User result in the 2025 shape: core.screen_name, avatar.image_url, no legacy.screen_name."""
    u = {
        "__typename": "User",
        "id": "VXNlcjo" + uid,
        "rest_id": uid,
        "affiliates_highlighted_label": {},
        "avatar": {"image_url": "https://pbs.twimg.com/profile_images/%s/new_normal.jpg" % uid},
        "core": {
            "created_at": "Mon Jun 02 08:15:00 +0000 2014",
            "name": name,
            "screen_name": screen_name,
        },
        "dm_permissions": {"can_dm": True},
        "has_graduated_access": True,
        "is_blue_verified": True,
        "legacy": {
            "default_profile": True,
            "description": "new shape bio of " + name,
            "entities": {"description": {"urls": []}},
            "favourites_count": 10,
            "followers_count": 222,
            "friends_count": 111,
            "media_count": 3,
            "statuses_count": 44,
        },
        "location": {"location": "Sài Gòn"},
        "media_permissions": {"can_media_tag": True},
        "privacy": {"protected": False},
        "profile_image_shape": "Circle",
        "verification": {"verified": False},
    }
    u.update(extra)
    return u


def photo(mid, key=None, idx=0):
    return {
        "display_url": "pic.x.com/abc%s" % mid,
        "expanded_url": "https://x.com/u/status/1/photo/%d" % (idx + 1),
        "id_str": mid,
        "indices": [10, 33],
        "media_key": key or "3_" + mid,
        "media_url_https": "https://pbs.twimg.com/media/P%s.jpg" % mid,
        "type": "photo",
        "url": "https://t.co/p%s" % mid,
        "ext_media_availability": {"status": "Available"},
        "sizes": {"large": {"h": 1536, "w": 2048, "resize": "fit"}},
        "original_info": {"height": 1536, "width": 2048, "focus_rects": []},
    }


def video(mid, variants=None, kind="video"):
    if variants is None:
        variants = [
            {"content_type": "application/x-mpegURL",
             "url": "https://video.twimg.com/ext_tw_video/%s/pu/pl/master.m3u8?tag=12" % mid},
            {"bitrate": 256000, "content_type": "video/mp4",
             "url": "https://video.twimg.com/ext_tw_video/%s/pu/vid/avc1/480x270/low.mp4?tag=12" % mid},
            {"bitrate": 2176000, "content_type": "video/mp4",
             "url": "https://video.twimg.com/ext_tw_video/%s/pu/vid/avc1/1280x720/high.mp4?tag=12" % mid},
            {"bitrate": 832000, "content_type": "video/mp4",
             "url": "https://video.twimg.com/ext_tw_video/%s/pu/vid/avc1/640x360/mid.mp4?tag=12" % mid},
        ]
    prefix = "7_" if kind == "video" else "16_"
    return {
        "display_url": "pic.x.com/vid%s" % mid,
        "expanded_url": "https://x.com/u/status/1/video/1",
        "id_str": mid,
        "indices": [0, 23],
        "media_key": prefix + mid,
        "media_url_https": "https://pbs.twimg.com/ext_tw_video_thumb/%s/pu/img/thumb.jpg" % mid,
        "type": kind,
        "url": "https://t.co/v%s" % mid,
        "additional_media_info": {"monetizable": False},
        "ext_media_availability": {"status": "Available"},
        "sizes": {"large": {"h": 720, "w": 1280, "resize": "fit"}},
        "original_info": {"height": 720, "width": 1280, "focus_rects": []},
        "video_info": {"aspect_ratio": [16, 9], "duration_millis": 31234, "variants": variants},
    }


def gif(mid):
    return video(mid, kind="animated_gif", variants=[
        {"bitrate": 0, "content_type": "video/mp4", "url": "https://video.twimg.com/tweet_video/G%s.mp4" % mid},
    ])


def tweet(tid, user, text="hello", media=None, entities_media=None, created="Wed Oct 10 20:19:24 +0000 2018",
          views="1200", legacy_extra=None, **extra):
    legacy = {
        "bookmark_count": 3,
        "bookmarked": False,
        "created_at": created,
        "conversation_id_str": tid,
        "display_text_range": [0, len(text)],
        "entities": {"hashtags": [], "symbols": [], "timestamps": [], "urls": [], "user_mentions": []},
        "favorite_count": 42,
        "favorited": False,
        "full_text": text,
        "is_quote_status": False,
        "lang": "vi",
        "possibly_sensitive": False,
        "possibly_sensitive_editable": True,
        "quote_count": 1,
        "reply_count": 5,
        "retweet_count": 7,
        "retweeted": False,
        "user_id_str": user.get("rest_id") if isinstance(user, dict) else None,
        "id_str": tid,
    }
    if media is not None:
        legacy["entities"]["media"] = entities_media if entities_media is not None else media
        legacy["extended_entities"] = {"media": media}
    elif entities_media is not None:
        legacy["entities"]["media"] = entities_media
    if legacy_extra:
        legacy.update(legacy_extra)
        for k in [k for k, v in legacy_extra.items() if v == "__DROP__"]:
            del legacy[k]
    t = {
        "__typename": "Tweet",
        "rest_id": tid,
        "core": {"user_results": {"result": user}},
        "unmention_data": {},
        "edit_control": {"edit_tweet_ids": [tid], "editable_until_msecs": "1539206364000",
                         "is_edit_eligible": False, "edits_remaining": "5"},
        "is_translatable": True,
        "views": {"count": views, "state": "EnabledWithCount"} if views is not None else {"state": "Enabled"},
        "source": "<a href=\"https://mobile.twitter.com\" rel=\"nofollow\">Twitter Web App</a>",
        "legacy": legacy,
    }
    t.update(extra)
    for k in [k for k, v in extra.items() if v == "__DROP__"]:
        del t[k]
    return t


def visibility(t, key="tweet"):
    return {
        "__typename": "TweetWithVisibilityResults",
        key: t,
        "limitedActionResults": {"limited_actions": [
            {"action": "Reply", "prompt": {"__typename": "CtaLimitedActionPrompt",
                                           "cta_type": "SeeConversation", "headline": {"text": "x", "entities": []}}},
        ]},
    }


def item_content(result, **extra):
    c = {"itemType": "TimelineTweet", "__typename": "TimelineTweet",
         "tweet_results": {"result": result} if result is not None else {},
         "tweetDisplayType": "Tweet"}
    c.update(extra)
    return c


def tweet_entry(result, entry_id=None, sort="1840000000000000000", **extra):
    if entry_id is None:
        rid = result.get("rest_id") or result.get("tweet", {}).get("rest_id") or "x"
        entry_id = "tweet-" + str(rid)
    return {
        "entryId": entry_id,
        "sortIndex": sort,
        "content": {
            "entryType": "TimelineTimelineItem",
            "__typename": "TimelineTimelineItem",
            "itemContent": item_content(result, **extra),
            "clientEventInfo": {"component": "result", "element": "tweet"},
        },
    }


def cursor_entry(kind, value, prefix="cursor"):
    return {
        "entryId": "%s-%s-%s" % (prefix, kind.lower(), "1840000000000000000"),
        "sortIndex": "1840000000000000000",
        "content": {
            "entryType": "TimelineTimelineCursor",
            "__typename": "TimelineTimelineCursor",
            "value": value,
            "cursorType": kind,
        },
    }


def module_entry(entry_id, results, display="VerticalConversation", item_prefix=None):
    items = []
    for i, r in enumerate(results):
        rid = (r or {}).get("rest_id") or (r or {}).get("tweet", {}).get("rest_id") or str(i)
        items.append({
            "entryId": "%s-tweet-%s" % (item_prefix or entry_id, rid),
            "dispensable": i > 0,
            "item": {"itemContent": item_content(r), "clientEventInfo": {"component": "tweet"}},
        })
    return {
        "entryId": entry_id,
        "sortIndex": "1840000000000000000",
        "content": {
            "entryType": "TimelineTimelineModule",
            "__typename": "TimelineTimelineModule",
            "items": items,
            "displayType": display,
            "clientEventInfo": {"component": "suggest_ranked_organic_tweet"},
        },
    }


def user_item(u):
    return {"itemType": "TimelineUser", "__typename": "TimelineUser",
            "user_results": {"result": u} if u is not None else {}, "userDisplayType": "User"}


def user_entry(u, entry_id=None):
    return {
        "entryId": entry_id or "user-%s" % ((u or {}).get("rest_id") or "x"),
        "sortIndex": "1840000000000000000",
        "content": {"entryType": "TimelineTimelineItem", "__typename": "TimelineTimelineItem",
                    "itemContent": user_item(u),
                    "clientEventInfo": {"component": "FollowingSgs", "element": "user"}},
    }


def add_entries(entries):
    return {"type": "TimelineAddEntries", "entries": entries}


def replace_entry(entry):
    return {"type": "TimelineReplaceEntry", "entry_id_to_replace": entry["entryId"], "entry": entry}


def search(instructions):
    return {"data": {"search_by_raw_query": {"search_timeline": {"timeline": {
        "instructions": instructions,
        "responseObjects": {"feedbackActions": []},
    }}}}}


def save(name, obj):
    with open(os.path.join(OUT, name + ".json"), "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
        f.write("\n")


ALICE = user_legacy("1001", "alice_media", "Alice Nguyễn")
BOB = user_new("2002", "bob_new", "Bob 🚀")
CAROL = user_legacy("3003", "carol", "Carol", avatar={"image_url": "https://pbs.twimg.com/profile_images/3003/both_normal.jpg"})
DAVE = user_new("4004", "dave", "Dave")

# --------------------------------------------------------------------------
# SearchTimeline
# --------------------------------------------------------------------------

save("search_basic_mixed", search([add_entries([
    cursor_entry("Top", "DAABCgABGc-top__8KAAIZ"),
    tweet_entry(tweet("1840000000000000001", ALICE, "ảnh đẹp quá https://t.co/p1", media=[photo("11")])),
    tweet_entry(tweet("1840000000000000002", ALICE, "video nè", media=[video("22")], views="98765",
                      legacy_extra={"favorited": True, "retweeted": True, "bookmarked": True})),
    tweet_entry(tweet("1840000000000000003", CAROL, "gif \"quote\" & <tag>", media=[gif("33")])),
    tweet_entry(tweet("1840000000000000004", ALICE, "4 photos",
                      media=[photo("41"), photo("42", idx=1), photo("43", idx=2), photo("44", idx=3)])),
    tweet_entry(tweet("1840000000000000005", CAROL, "mixed", media=[photo("51"), video("52"), gif("53")])),
    tweet_entry(tweet("1840000000000000006", ALICE, "chỉ có chữ, không media 😀")),
    tweet_entry(tweet("1840000000000000007", CAROL, "video first then photo", media=[video("71"), photo("72")],
                      created="Sat Feb 29 23:59:59 +0000 2020")),
    cursor_entry("Bottom", "DAABCgABGc-bottom__8KAAIZ"),
])]))

save("search_new_user_shape", search([add_entries([
    tweet_entry(tweet("1850000000000000001", BOB, "new user shape photo", media=[photo("101")])),
    tweet_entry(tweet("1850000000000000002", DAVE, "new user shape video", media=[video("102")])),
    # both shapes present: legacy wins for the name and for the avatar
    tweet_entry(tweet("1850000000000000003", CAROL, "both shapes", media=[photo("103")])),
    # no user at all
    tweet_entry(tweet("1850000000000000004", {}, "empty user result", media=[photo("104")])),
    tweet_entry(tweet("1850000000000000005", BOB, "core without user_results", media=[photo("105")], core={})),
    tweet_entry(tweet("1850000000000000006", BOB, "no core", media=[photo("106")], core="__DROP__")),
    tweet_entry(tweet("1850000000000000007", {"__typename": "UserUnavailable", "reason": "Suspended"},
                      "unavailable user", media=[photo("107")])),
    cursor_entry("Top", "new-top"),
    cursor_entry("Bottom", "new-bottom"),
])]))

save("search_visibility_results", search([add_entries([
    cursor_entry("Top", "vis-top"),
    # the real shape: the post sits under .tweet
    tweet_entry(visibility(tweet("1860000000000000001", ALICE, "limited", media=[video("201")])),
                entry_id="tweet-1860000000000000001"),
    # the shape the Dart code looks for: .tweet_results.result
    tweet_entry({"__typename": "TweetWithVisibilityResults",
                 "tweet_results": {"result": tweet("1860000000000000002", ALICE, "alt", media=[photo("202")])}},
                entry_id="tweet-1860000000000000002"),
    tweet_entry({"__typename": "TweetTombstone", "tombstone": {"__typename": "TextTombstone", "text": {
        "rtl": False, "text": "This Post is from a suspended account. Learn more", "entities": []}}},
        entry_id="tweet-1860000000000000003"),
    tweet_entry({"__typename": "TweetUnavailable", "reason": "NsfwLoggedOut"}, entry_id="tweet-1860000000000000004"),
    tweet_entry(None, entry_id="tweet-1860000000000000005"),
    # no __typename, no rest_id, post under .tweet
    tweet_entry({"tweet": tweet("1860000000000000006", CAROL, "bare wrapper", media=[photo("206")])},
                entry_id="tweet-1860000000000000006"),
    tweet_entry(tweet("1860000000000000007", CAROL, "plain after", media=[photo("207")])),
    cursor_entry("Bottom", "vis-bottom"),
])]))

# page 2 of a search: tweets in AddEntries, the cursors replaced
save("search_page2_replace_cursors", search([
    add_entries([
        tweet_entry(tweet("1870000000000000001", ALICE, "page 2 a", media=[video("301")])),
        tweet_entry(tweet("1870000000000000002", BOB, "page 2 b", media=[photo("302")])),
    ]),
    replace_entry(cursor_entry("Top", "page2-top")),
    replace_entry(cursor_entry("Bottom", "page2-bottom")),
]))

# the end of a search: nothing new, only the cursors replaced
save("search_end_replace_only", search([
    replace_entry(cursor_entry("Top", "end-top")),
    replace_entry(cursor_entry("Bottom", "end-bottom")),
]))

save("search_end_empty_add_entries", search([
    add_entries([]),
    replace_entry(cursor_entry("Top", "end2-top")),
    replace_entry(cursor_entry("Bottom", "end2-bottom")),
]))

ORIGINAL = tweet("1880000000000000100", CAROL, "original with video", media=[video("401")], views="555000",
                 legacy_extra={"favorite_count": 9001, "retweet_count": 300, "reply_count": 20, "favorited": True})
ORIGINAL_NEW_USER = tweet("1880000000000000200", BOB, "original by new-shape user", media=[photo("402")])
QUOTED = tweet("1880000000000000300", CAROL, "quoted has media", media=[photo("403")])
save("search_retweets_quotes", search([add_entries([
    # retweet, the way X sends it: under legacy
    tweet_entry(tweet("1880000000000000001", ALICE, "RT @carol: original with video",
                      legacy_extra={"retweeted_status_result": {"result": ORIGINAL}, "retweeted": True})),
    # retweet at the top level of the result
    tweet_entry(tweet("1880000000000000002", ALICE, "RT @carol: original with video",
                      retweeted_status_result={"result": ORIGINAL})),
    # retweeted author only in the new shape
    tweet_entry(tweet("1880000000000000003", ALICE, "RT @bob_new: original by new-shape user",
                      legacy_extra={"retweeted_status_result": {"result": ORIGINAL_NEW_USER}})),
    # retweeted post wrapped in TweetWithVisibilityResults
    tweet_entry(tweet("1880000000000000004", ALICE, "RT wrapped",
                      legacy_extra={"retweeted_status_result": {"result": visibility(ORIGINAL)}})),
    # retweeted post gone
    tweet_entry(tweet("1880000000000000005", ALICE, "RT gone", media=[photo("405")],
                      legacy_extra={"retweeted_status_result": {"result": {"__typename": "TweetTombstone"}}})),
    tweet_entry(tweet("1880000000000000006", ALICE, "RT empty", media=[photo("406")],
                      legacy_extra={"retweeted_status_result": {}})),
    # quote: own text, no own media, quoted has media
    tweet_entry(tweet("1880000000000000007", ALICE, "look at this https://t.co/q",
                      quoted_status_result={"result": QUOTED},
                      legacy_extra={"is_quote_status": True, "quoted_status_id_str": "1880000000000000300"})),
    # quote with own media
    tweet_entry(tweet("1880000000000000008", ALICE, "quote + own video", media=[video("408")],
                      quoted_status_result={"result": visibility(QUOTED)},
                      legacy_extra={"is_quote_status": True})),
    # repostedStatusResults spelling
    tweet_entry(tweet("1880000000000000009", ALICE, "reposted spelling",
                      legacy_extra={"repostedStatusResults": {"result": ORIGINAL}})),
    cursor_entry("Bottom", "rt-bottom"),
])]))

NOTE_TEXT = "Bài viết dài " + "rất dài " * 60
save("search_note_tweet", search([add_entries([
    tweet_entry(tweet("1890000000000000001", ALICE, NOTE_TEXT[:280] + "…", note_tweet={
        "is_expandable": True,
        "note_tweet_results": {"result": {
            "id": "Tm90ZVR3ZWV0OjE=", "text": NOTE_TEXT,
            "entity_set": {"hashtags": [], "symbols": [], "urls": [], "user_mentions": [],
                           "media": [photo("501")]},
            "richtext": {"richtext_tags": []},
        }}})),
    # note with media and the post itself with media: the post's media wins
    tweet_entry(tweet("1890000000000000002", ALICE, "note + own media", media=[video("502")], note_tweet={
        "is_expandable": True,
        "note_tweet_results": {"result": {"id": "x", "text": "long", "entity_set": {"media": [photo("503")]}}}})),
    # note with extended_entities
    tweet_entry(tweet("1890000000000000003", ALICE, "note ext", note_tweet={
        "note_tweet_results": {"result": {"id": "y", "text": "long",
                                          "entity_set": {"media": [photo("504")]},
                                          "extended_entities": {"media": [video("505"), photo("506")]}}}})),
    # note without media
    tweet_entry(tweet("1890000000000000004", ALICE, "note no media", note_tweet={
        "note_tweet_results": {"result": {"id": "z", "text": "long", "entity_set": {"hashtags": []}}}})),
    tweet_entry(tweet("1890000000000000005", ALICE, "note empty", note_tweet={"note_tweet_results": {}})),
])]))

V_NO_MP4 = video("601", variants=[
    {"content_type": "application/x-mpegURL", "url": "https://video.twimg.com/amplify_video/601/pl/master.m3u8"},
    {"content_type": "application/dash+xml", "url": "https://video.twimg.com/amplify_video/601/pl/master.mpd"}])
V_EMPTY = video("602", variants=[])
V_NO_INFO = video("603")
del V_NO_INFO["video_info"]
V_NO_BITRATE = video("604", variants=[
    {"content_type": "video/mp4", "url": "https://video.twimg.com/604/nobitrate.mp4"},
    {"bitrate": 632000, "content_type": "video/mp4", "url": "https://video.twimg.com/604/632.mp4"},
    {"bitrate": 632000, "content_type": "video/mp4", "url": "https://video.twimg.com/604/632-second.mp4"}])
V_TIE_ZERO = video("605", variants=[
    {"content_type": "video/mp4", "url": "https://video.twimg.com/605/first-none.mp4"},
    {"bitrate": 0, "content_type": "video/mp4", "url": "https://video.twimg.com/605/second-zero.mp4"},
    {"content_type": "video/mp4", "url": "https://video.twimg.com/605/third-none.mp4"}])
V_NULL_URL = video("606", variants=[
    {"bitrate": 5000000, "content_type": "video/mp4", "url": None},
    {"bitrate": 100, "content_type": "video/mp4", "url": "https://video.twimg.com/606/low.mp4"}])
V_FIRST_NULL = video("607", variants=[{"content_type": "application/x-mpegURL"}, {"content_type": "application/x-mpegURL", "url": "https://video.twimg.com/607/b.m3u8"}])
V_FLOAT = video("608", variants=[
    {"bitrate": 1.5, "content_type": "video/mp4", "url": "https://video.twimg.com/608/float.mp4"},
    {"bitrate": 1, "content_type": "video/mp4", "url": "https://video.twimg.com/608/int.mp4"}])
V_MANY = video("609", variants=[
    {"bitrate": 950000, "content_type": "video/mp4", "url": "https://video.twimg.com/609/a.mp4"},
    {"bitrate": 10368000, "content_type": "video/mp4", "url": "https://video.twimg.com/609/top.mp4"},
    {"content_type": "application/x-mpegURL", "url": "https://video.twimg.com/609/m.m3u8"},
    {"bitrate": 2176000, "content_type": "video/mp4", "url": "https://video.twimg.com/609/c.mp4"},
    {"bitrate": 10368000, "content_type": "video/mp4", "url": "https://video.twimg.com/609/top-dup.mp4"},
    {"bitrate": 288000, "content_type": "video/mp4", "url": "https://video.twimg.com/609/d.mp4"},
    {"bitrate": 632000, "content_type": "video/mp4", "url": "https://video.twimg.com/609/e.mp4"},
    {"bitrate": 4096000, "content_type": "video/mp4", "url": "https://video.twimg.com/609/f.mp4"}])
P_NO_URL = photo("610")
del P_NO_URL["media_url_https"]
P_NO_KEY = photo("611")
del P_NO_KEY["media_key"]
save("search_media_edge_cases", search([add_entries([
    # media only under entities (no extended_entities)
    tweet_entry(tweet("1900000000000000001", ALICE, "entities only", entities_media=[photo("600")])),
    tweet_entry(tweet("1900000000000000002", ALICE, "extended empty, entities filled", entities_media=[photo("600")],
                      legacy_extra={"extended_entities": {"media": []}})),
    tweet_entry(tweet("1900000000000000003", ALICE, "no mp4", media=[V_NO_MP4])),
    tweet_entry(tweet("1900000000000000004", ALICE, "no variants", media=[V_EMPTY])),
    tweet_entry(tweet("1900000000000000005", ALICE, "no video_info", media=[V_NO_INFO])),
    tweet_entry(tweet("1900000000000000006", ALICE, "bitrate missing", media=[V_NO_BITRATE])),
    tweet_entry(tweet("1900000000000000007", ALICE, "ties at zero", media=[V_TIE_ZERO])),
    tweet_entry(tweet("1900000000000000008", ALICE, "best has null url", media=[V_NULL_URL])),
    tweet_entry(tweet("1900000000000000009", ALICE, "first variant no url", media=[V_FIRST_NULL])),
    tweet_entry(tweet("1900000000000000010", ALICE, "float bitrate", media=[V_FLOAT])),
    tweet_entry(tweet("1900000000000000011", ALICE, "many variants", media=[V_MANY])),
    tweet_entry(tweet("1900000000000000012", ALICE, "photo no url first", media=[P_NO_URL, photo("612")])),
    tweet_entry(tweet("1900000000000000013", ALICE, "photo no key", media=[P_NO_KEY])),
    tweet_entry(tweet("1900000000000000014", ALICE, "unknown type",
                      media=[dict(photo("614"), type="model3d"), photo("615")])),
    tweet_entry(tweet("1900000000000000015", ALICE, "empty video then photo", media=[V_EMPTY, photo("616")])),
    tweet_entry(tweet("1900000000000000016", ALICE, "entities null",
                      legacy_extra={"entities": None, "extended_entities": None})),
    tweet_entry(tweet("1900000000000000017", ALICE, "media null",
                      legacy_extra={"entities": {"media": None}, "extended_entities": {"media": None}})),
])]))

T_NO_LEGACY = tweet("1910000000000000001", ALICE, "x", media=[photo("701")])
del T_NO_LEGACY["legacy"]
T_NO_REST = tweet("1910000000000000002", ALICE, "no rest_id", media=[photo("702")])
del T_NO_REST["rest_id"]
save("search_missing_and_wrong_types", search([add_entries([
    tweet_entry(T_NO_LEGACY, entry_id="tweet-1910000000000000001"),
    tweet_entry(T_NO_REST, entry_id="tweet-1910000000000000002"),
    tweet_entry(tweet("1910000000000000003", ALICE, "favorited null", media=[photo("703")],
                      legacy_extra={"favorited": None, "retweeted": None, "favorite_count": None,
                                    "reply_count": None, "retweet_count": None})),
    tweet_entry(tweet("1910000000000000004", ALICE, "count as string", media=[photo("704")],
                      legacy_extra={"favorite_count": "42"})),
    tweet_entry(tweet("1910000000000000005", ALICE, "count as float", media=[photo("705")],
                      legacy_extra={"retweet_count": 7.0})),
    tweet_entry(tweet("1910000000000000006", ALICE, "favorited as string", media=[photo("706")],
                      legacy_extra={"favorited": "true"})),
    tweet_entry(tweet("1910000000000000007", ALICE, "views missing", media=[photo("707")], views=None)),
    tweet_entry(tweet("1910000000000000008", ALICE, "views not a number", media=[photo("708")], views="1.2K")),
    tweet_entry(tweet("1910000000000000009", ALICE, "views int", media=[photo("709")], views=77)),
    tweet_entry(tweet("1910000000000000010", ALICE, "views padded", media=[photo("710")], views=" 0012 ")),
    tweet_entry(tweet("1910000000000000011", ALICE, "views in legacy", media=[photo("711")], views="__DROP__",
                      legacy_extra={"views": {"count": "31337"}})),
    tweet_entry(tweet("1910000000000000012", ALICE, "views huge", media=[photo("712")], views="98765432109")),
    tweet_entry(tweet("1910000000000000013", ALICE, "created_at missing, ms present", media=[photo("713")],
                      legacy_extra={"created_at": "__DROP__", "created_at_ms": 1700000000123})),
    tweet_entry(tweet("1910000000000000014", ALICE, "created_at_ms as string", media=[photo("714")],
                      legacy_extra={"created_at": "__DROP__", "created_at_ms": "1700000000456"})),
    tweet_entry(tweet("1910000000000000015", ALICE, "no date at all", media=[photo("715")],
                      legacy_extra={"created_at": "__DROP__"})),
    tweet_entry(tweet("1910000000000000016", ALICE, "iso date", media=[photo("716")],
                      created="2024-05-06T07:08:09.000Z")),
    tweet_entry(tweet("1910000000000000017", ALICE, "garbage date", media=[photo("717")], created="yesterday")),
    tweet_entry(tweet("1910000000000000018", ALICE, "offset date", media=[photo("718")],
                      created="Mon Jan 01 00:00:00 +0700 2024")),
    tweet_entry(tweet("1910000000000000019", ALICE, "x", media=[photo("719")],
                      legacy_extra={"full_text": "__DROP__", "text": "only text field"})),
    tweet_entry(tweet("1910000000000000020", ALICE, "x", media=[photo("720")],
                      legacy_extra={"full_text": "__DROP__"})),
    tweet_entry(tweet("1910000000000000021", ALICE, "full_text as number", media=[photo("721")],
                      legacy_extra={"full_text": 12345})),
    tweet_entry(tweet("1910000000000000022", user_legacy("9", "x", "x", legacy={"screen_name": 12345}),
                      "screen_name as number", media=[photo("722")])),
    tweet_entry(tweet(1910000000000000023, ALICE, "rest_id as number", media=[photo("723")]),
                entry_id="tweet-1910000000000000023"),
    tweet_entry(tweet("1910000000000000024", ALICE, "bitrate as string", media=[video("724", variants=[
        {"bitrate": "832000", "content_type": "video/mp4", "url": "https://video.twimg.com/724/a.mp4"},
        {"bitrate": 100, "content_type": "video/mp4", "url": "https://video.twimg.com/724/b.mp4"}])])),
    tweet_entry(tweet("1910000000000000025", ALICE, "media is a map", media=[photo("725")],
                      legacy_extra={"extended_entities": {"media": {"0": photo("725")}}})),
    tweet_entry(tweet("1910000000000000026", ALICE, "media item null", media=[None, photo("726")])),
    tweet_entry(tweet("1910000000000000027", ALICE, "media url as number",
                      media=[dict(photo("727"), media_url_https=727)])),
    tweet_entry(tweet("1910000000000000028", ALICE, "legacy is a string", media=[photo("728")], legacy="oops")),
    tweet_entry(tweet("1910000000000000029", ALICE, "avatar as number", media=[photo("729")],
                      core={"user_results": {"result": {"legacy": {"screen_name": "n", "profile_image_url_https": 5}}}})),
    tweet_entry(tweet("1910000000000000030", ALICE, "core is a list", media=[photo("730")], core=[])),
    {"entryId": "tweet-1910000000000000031", "content": None},
    {"entryId": "tweet-1910000000000000032"},
    {"content": {"entryType": "TimelineTimelineItem",
                 "itemContent": item_content(tweet("1910000000000000033", ALICE, "entry without id", media=[photo("733")]))}},
    {"entryId": "tweet-1910000000000000034", "content": {"entryType": "TimelineTimelineItem"}},
    {"entryId": "tweet-1910000000000000035", "content": {"entryType": "TimelineTimelineItem", "itemContent": "text"}},
    {"entryId": "tweet-1910000000000000036", "content": {
        "itemContent": item_content(tweet("1910000000000000036", ALICE, "no entryType", media=[photo("736")]))}},
    {"entryId": "tweet-1910000000000000037", "content": {"entryType": "TimelineTimelineItem",
                                                         "__typename": "TimelineTimelineItem",
                                                         "itemContent": item_content(
                                                             tweet("1910000000000000037", ALICE, "last good one",
                                                                   media=[video("737")]))}},
    {"entryId": "cursor-bottom-0", "content": {"entryType": "TimelineTimelineCursor", "cursorType": "Bottom"}},
    {"entryId": "cursor-top-0", "content": {"entryType": "TimelineTimelineItem",
                                            "itemContent": {"itemType": "TimelineTimelineCursor", "value": "nested-top"}}},
])]))

save("search_modules_and_users", search([add_entries([
    cursor_entry("Top", "mod-top"),
    # the "People" block of a search
    {"entryId": "toptabsrpusermodule-1840000000000000000", "sortIndex": "1",
     "content": {"entryType": "TimelineTimelineModule", "__typename": "TimelineTimelineModule",
                 "items": [{"entryId": "toptabsrpusermodule-1-user-1001", "item": {"itemContent": user_item(ALICE)}},
                           {"entryId": "toptabsrpusermodule-1-user-2002", "item": {"itemContent": user_item(BOB)}}],
                 "displayType": "Carousel", "header": {"displayType": "Classic", "text": "People"}}},
    tweet_entry(tweet("1920000000000000001", ALICE, "before module", media=[photo("801")])),
    module_entry("tweet-module-1", [
        tweet("1920000000000000002", CAROL, "module a", media=[video("802")]),
        visibility(tweet("1920000000000000003", CAROL, "module b wrapped", media=[photo("803")])),
        None,
        tweet("1920000000000000004", BOB, "module c text"),
    ]),
    # module with odd items
    {"entryId": "tweet-module-2", "content": {"entryType": "TimelineTimelineModule", "items": [
        {"entryId": "a"},
        {"entryId": "b", "item": None},
        {"entryId": "c", "item": {"itemContent": {"itemType": "TimelineTimelineCursor", "value": "x"}}},
        {"entryId": "d", "item": {"tweet_results": {"result": tweet("1920000000000000005", ALICE, "direct tweet_results",
                                                                     media=[photo("805")])}}},
        {"entryId": "e", "item": "text"},
        {"entryId": "f", "item": {"itemContent": item_content(
            tweet("1920000000000000006", ALICE, "after bad item", media=[photo("806")]))}},
    ]}},
    {"entryId": "tweet-module-3", "content": {"entryType": "TimelineTimelineModule"}},
    {"entryId": "tweet-module-4", "content": {"entryType": "TimelineTimelineModule", "items": {"0": 1}}},
    tweet_entry(tweet("1920000000000000007", ALICE, "after modules", media=[photo("807")])),
    # promoted
    tweet_entry(tweet("1920000000000000008", DAVE, "promoted", media=[video("808")]),
                entry_id="promoted-tweet-1920000000000000008-abc",
                promotedMetadata={"advertiser_results": {"result": DAVE}, "impressionId": "abc"}),
    cursor_entry("Bottom", "mod-bottom"),
    cursor_entry("Bottom", "sq-bottom", prefix="sq-cursor"),
    cursor_entry("Top", "sq-top", prefix="sq-cursor"),
])]))

save("search_no_instructions", {"data": {"search_by_raw_query": {"search_timeline": {"timeline": {"instructions": []}}}}})
save("search_no_timeline", {"data": {"search_by_raw_query": {}}})
save("search_errors_only", {"errors": [{"message": "Authorization: Denied by access control", "code": 37,
                                         "kind": "Permissions", "name": "AuthorizationError"}], "data": {}})
save("search_instructions_at_top", {"data": {"search_by_raw_query": {"search_timeline": {
    "instructions": [{"type": "TimelineClearCache"},
                     {"type": "TimelineTerminateTimeline", "direction": "Top"},
                     add_entries([tweet_entry(tweet("1930000000000000001", ALICE, "instructions directly under search_timeline",
                                                    media=[photo("901")])),
                                  cursor_entry("Bottom", "direct-bottom")])]}}}})
save("search_instruction_types_mixed", search([
    {"type": "TimelineClearCache"},
    "not a map",
    None,
    {"type": "TimelineShowAlert", "alertType": "NewTweets"},
    {"type": "TimelineAddToModule", "moduleEntryId": "tweet-module-1", "prepend": False, "moduleItems": [
        {"entryId": "tweet-module-1-tweet-1", "item": {"itemContent": item_content(
            tweet("1940000000000000001", ALICE, "in AddToModule", media=[photo("911")]))}}]},
    {"__typename": "TimelineAddEntries", "entries": [
        tweet_entry(tweet("1940000000000000002", ALICE, "typename-only instruction", media=[video("912")])),
        cursor_entry("Top", "mix-top"), cursor_entry("Bottom", "mix-bottom")]},
    add_entries([tweet_entry(tweet("1940000000000000003", ALICE, "second AddEntries", media=[photo("913")]))]),
]))

# --------------------------------------------------------------------------
# UserTweets
# --------------------------------------------------------------------------

PINNED = tweet("1950000000000000001", ALICE, "pinned", media=[photo("1001")])
USER_TWEETS_INSTRUCTIONS = [
    {"type": "TimelineClearCache"},
    {"type": "TimelinePinEntry", "entry": tweet_entry(PINNED, socialContext={
        "type": "TimelineGeneralContext", "contextType": "Pin", "text": "Pinned"})},
    add_entries([
        tweet_entry(tweet("1950000000000000002", ALICE, "latest", media=[video("1002")])),
        module_entry("profile-conversation-1950000000000000003", [
            tweet("1950000000000000003", ALICE, "thread 1", media=[photo("1003")]),
            tweet("1950000000000000004", ALICE, "thread 2", media=[photo("1004")]),
        ]),
        {"entryId": "who-to-follow-1", "content": {"entryType": "TimelineTimelineModule", "items": [
            {"entryId": "who-to-follow-1-user-2002", "item": {"itemContent": user_item(BOB)}}],
            "displayType": "Vertical", "header": {"text": "Who to follow"}}},
        tweet_entry(tweet("1950000000000000005", ALICE, "RT", legacy_extra={
            "retweeted_status_result": {"result": ORIGINAL}})),
        cursor_entry("Top", "ut-top"),
        cursor_entry("Bottom", "ut-bottom"),
    ]),
]
save("usertweets_timeline_v2_with_pin", {"data": {"user": {"result": {
    "__typename": "User", "timeline_v2": {"timeline": {"instructions": USER_TWEETS_INSTRUCTIONS,
                                                       "metadata": {"scribeConfig": {"page": "profileBest"}}}}}}}})
save("usertweets_timeline_v2_no_pin", {"data": {"user": {"result": {
    "__typename": "User", "timeline_v2": {"timeline": {
        "instructions": [USER_TWEETS_INSTRUCTIONS[0], USER_TWEETS_INSTRUCTIONS[2]]}}}}}})
save("usertweets_new_timeline_shape", {"data": {"user": {"result": {
    "__typename": "User", "timeline": {"timeline": {
        "instructions": [USER_TWEETS_INSTRUCTIONS[0], USER_TWEETS_INSTRUCTIONS[2]]}}}}}})
save("usertweets_unavailable", {"data": {"user": {"result": {"__typename": "UserUnavailable", "reason": "Suspended"}}}})

# --------------------------------------------------------------------------
# TweetDetail
# --------------------------------------------------------------------------

FOCAL = tweet("1960000000000000001", ALICE, "focal post", media=[video("1101")])
save("detail_conversation", {"data": {"threaded_conversation_with_injections_v2": {"instructions": [
    add_entries([
        tweet_entry(FOCAL, hasModeratedReplies=False),
        module_entry("conversationthread-1960000000000000010", [
            tweet("1960000000000000010", BOB, "reply 1"),
            tweet("1960000000000000011", ALICE, "author answers", media=[photo("1111")]),
        ], item_prefix="conversationthread-1960000000000000010"),
        module_entry("conversationthread-1960000000000000020", [
            visibility(tweet("1960000000000000020", CAROL, "reply 2 wrapped")),
            {"__typename": "TweetTombstone", "tombstone": {"text": {"text": "This Post was deleted"}}},
        ]),
        {"entryId": "conversationthread-1960000000000000030", "content": {
            "entryType": "TimelineTimelineModule", "items": [
                {"entryId": "conversationthread-1960000000000000030-tweet-1960000000000000030",
                 "item": {"itemContent": item_content(tweet("1960000000000000030", DAVE, "reply 3"))}},
                {"entryId": "conversationthread-1960000000000000030-cursor-showmore-1",
                 "item": {"itemContent": {"itemType": "TimelineTimelineCursor", "value": "showmore",
                                          "cursorType": "ShowMore", "displayTreatment": {"actionText": "Show replies"}}}},
            ], "displayType": "VerticalConversation"}},
        module_entry("tweetdetailrelatedtweets-1960000000000000040", [
            tweet("1960000000000000040", CAROL, "related", media=[video("1140")]),
        ]),
        {"entryId": "cursor-showmorethreads-1", "content": {
            "entryType": "TimelineTimelineItem", "itemContent": {
                "itemType": "TimelineTimelineCursor", "value": "show-more-threads",
                "cursorType": "ShowMoreThreads"}}},
        {"entryId": "cursor-bottom-1960", "content": {
            "entryType": "TimelineTimelineItem", "itemContent": {
                "itemType": "TimelineTimelineCursor", "value": "detail-bottom-in-item",
                "cursorType": "Bottom"}}},
    ]),
    {"type": "TimelineTerminateTimeline", "direction": "Top"},
]}}})
save("detail_focal_tombstone", {"data": {"threaded_conversation_with_injections_v2": {"instructions": [
    add_entries([
        tweet_entry({"__typename": "TweetTombstone", "tombstone": {"text": {"text": "Age-restricted adult content."}}},
                    entry_id="tweet-1970000000000000001"),
        module_entry("conversationthread-1970000000000000002", [tweet("1970000000000000002", BOB, "still here",
                                                                      media=[photo("1202")])]),
        cursor_entry("Bottom", "tomb-bottom"),
    ])]}}})
save("detail_not_found", {"data": {}, "errors": [{"message": "_Missing: No status found with that ID.", "code": 144}]})
save("detail_tweet_result_by_rest_id", {"data": {"tweetResult": {"result": FOCAL}}})

# --------------------------------------------------------------------------
# HomeTimeline / HomeLatestTimeline / MediaTabVideoMixer
# --------------------------------------------------------------------------

save("home_timeline", {"data": {"home": {"home_timeline_urt": {
    "instructions": [add_entries([
        tweet_entry(tweet("1980000000000000001", ALICE, "home video", media=[video("1301")]),
                    entry_id="tweet-1980000000000000001"),
        tweet_entry(tweet("1980000000000000002", BOB, "home photo", media=[photo("1302")])),
        tweet_entry(tweet("1980000000000000003", CAROL, "home text")),
        tweet_entry(tweet("1980000000000000004", DAVE, "promoted home", media=[video("1304")]),
                    entry_id="promoted-tweet-1980000000000000004-9f", promotedMetadata={"impressionId": "9f"}),
        module_entry("home-conversation-1980000000000000010", [
            tweet("1980000000000000010", ALICE, "conv root", media=[photo("1310")]),
            tweet("1980000000000000011", BOB, "conv reply", media=[gif("1311")]),
        ]),
        {"entryId": "who-to-follow-9", "content": {"entryType": "TimelineTimelineModule", "items": [
            {"entryId": "who-to-follow-9-user-3003", "item": {"itemContent": user_item(CAROL)}}]}},
        tweet_entry(tweet("1980000000000000005", ALICE, "RT in home", legacy_extra={
            "retweeted_status_result": {"result": ORIGINAL}}),
            socialContext={"type": "TimelineGeneralContext", "contextType": "Like", "text": "Bob liked"}),
        tweet_entry(tweet("1980000000000000006", CAROL, "photo with empty-variant video", media=[V_EMPTY])),
        cursor_entry("Top", "home-top"),
        cursor_entry("Bottom", "home-bottom"),
    ])],
    "metadata": {"scribeConfig": {"page": "following"}},
    "responseObjects": {"feedbackActions": [{"key": "1", "value": {"feedbackType": "DontLike"}}]},
}}}})
# "instructions" also appears elsewhere, before the timeline
save("home_instructions_found_first_elsewhere", {"data": {
    "viewer": {"onboarding": {"instructions": "read the rules"}},
    "home": {"home_timeline_urt": {"instructions": [add_entries([
        tweet_entry(tweet("1990000000000000001", ALICE, "never reached?", media=[photo("1401")]))])]}}}})
save("home_video_mixer", {"data": {"timeline_response": {"timeline": {"instructions": [
    {"type": "TimelineClearCache"},
    add_entries([
        tweet_entry(tweet("2000000000000000001", ALICE, "mixer 1", media=[video("1501")]),
                    entry_id="tweet-2000000000000000001"),
        tweet_entry(visibility(tweet("2000000000000000002", BOB, "mixer 2 wrapped", media=[video("1502")])),
                    entry_id="tweet-2000000000000000002"),
        tweet_entry(tweet("2000000000000000003", CAROL, "mixer 3", media=[video("1503")])),
        cursor_entry("Bottom", "mixer-bottom"),
    ])]}}}})
save("home_data_null", {"data": None})
save("home_no_data", {"errors": [{"message": "Rate limit exceeded", "code": 88}]})

# --------------------------------------------------------------------------
# Following
# --------------------------------------------------------------------------


def following(instructions):
    return {"data": {"user": {"result": {"__typename": "User", "timeline": {"timeline": {
        "instructions": instructions}}}}}}


save("following_legacy_users", following([
    {"type": "TimelineClearCache"},
    {"type": "TimelineTerminateTimeline", "direction": "Top"},
    add_entries([
        user_entry(ALICE),
        user_entry(user_legacy("1002", "Mixed_Case", "Tên có dấu")),
        user_entry(user_legacy("1003", "no_avatar", "No Avatar", legacy={"screen_name": "no_avatar", "name": "No Avatar"})),
        user_entry(user_legacy("1004", "no_name", "x", legacy={"screen_name": "no_name"})),
        cursor_entry("Bottom", "1790000000000000000|1840000000000000000"),
        cursor_entry("Top", "-1|1840000000000000001"),
    ]),
]))
NO_AVATAR_KEY = user_new("2006", "legacy_avatar_only", "L",
                         legacy={"profile_image_url_https": "https://pbs.twimg.com/profile_images/2006/l_normal.jpg"})
del NO_AVATAR_KEY["avatar"]
save("following_new_shape_users", following([
    {"type": "TimelineClearCache"},
    {"__typename": "TimelineAddEntries", "entries": [
        user_entry(BOB),
        user_entry(DAVE),
        user_entry(CAROL),
        user_entry(user_new("2005", "no_avatar_new", "N", avatar={})),
        user_entry(NO_AVATAR_KEY),
        cursor_entry("Bottom", "new-following-bottom"),
        cursor_entry("Top", "new-following-top"),
    ]},
]))
save("following_mixed_and_broken", following([add_entries([
    cursor_entry("Top", "broken-top"),
    user_entry(ALICE),
    user_entry(None, entry_id="user-0"),
    user_entry({"__typename": "UserUnavailable", "reason": "Suspended"}, entry_id="user-1"),
    {"entryId": "user-2", "content": {"entryType": "TimelineTimelineItem", "itemContent": {"itemType": "TimelineUser"}}},
    {"entryId": "user-3", "content": {"entryType": "TimelineTimelineItem"}},
    {"entryId": "user-4"},
    user_entry(BOB),
    user_entry(user_legacy("1010", "x", "x", rest_id=None, legacy={"screen_name": "no_rest_id", "name": "No Rest"})),
    user_entry(DAVE),
    cursor_entry("Bottom", "broken-bottom"),
])]))
save("following_last_page_cursor_only", following([add_entries([
    cursor_entry("Bottom", "0|1840000000000000000"),
    cursor_entry("Top", "-1|1840000000000000000"),
])]))
save("following_no_add_entries", following([{"type": "TimelineClearCache"},
                                            {"type": "TimelineTerminateTimeline", "direction": "Bottom"}]))
save("following_cursor_before_users", following([add_entries([
    cursor_entry("Bottom", "first-bottom"),
    user_entry(ALICE),
    {"entryId": "cursor-bottom-2", "content": {"entryType": "TimelineTimelineCursor", "cursorType": "Bottom"}},
])]))
save("following_legacy_without_screen_name", following([add_entries([
    user_entry(ALICE),
    user_entry({"__typename": "User", "rest_id": "77", "legacy": {"name": "nameless"}}),
    user_entry(BOB),
    cursor_entry("Bottom", "never"),
])]))
save("following_old_timeline_path", {"data": {"user": {"result": {"timeline_v2": {"timeline": {"instructions": [
    add_entries([user_entry(ALICE)])]}}}}}})

# --------------------------------------------------------------------------
# UserByScreenName
# --------------------------------------------------------------------------

save("profile_legacy_shape", {"data": {"user": {"result": ALICE}}})
save("profile_new_shape", {"data": {"user": {"result": BOB}}})
save("profile_both_shapes", {"data": {"user": {"result": CAROL}}})
save("profile_unavailable", {"data": {"user": {"result": {"__typename": "UserUnavailable", "reason": "Suspended",
                                                           "message": "User is suspended"}}}})
save("profile_not_found", {"data": {}})
save("profile_user_null", {"data": {"user": None}})
save("profile_no_legacy", {"data": {"user": {"result": {"__typename": "User", "rest_id": "5005",
                                                         "avatar": {"image_url": "https://pbs.twimg.com/a.jpg"}}}}})
save("profile_counts_wrong_type", {"data": {"user": {"result": user_legacy(
    "6006", "counts", "Counts", legacy={"screen_name": "counts", "name": "Counts", "followers_count": "12",
                                        "friends_count": 3})}}})
save("profile_null_fields", {"data": {"user": {"result": user_legacy(
    "7007", "nulls", "Nulls", avatar=None, legacy={"screen_name": None, "name": None, "description": None,
                                                    "followers_count": None, "friends_count": None,
                                                    "profile_image_url_https": None})}}})
save("profile_big_counts", {"data": {"user": {"result": user_legacy(
    "8008", "big", "Big", legacy={"screen_name": "big", "name": "Big", "description": "",
                                  "followers_count": 223456789, "friends_count": 0,
                                  "profile_image_url_https": "https://pbs.twimg.com/profile_images/8008/b_normal.jpg"})}}})

print("wrote", len(os.listdir(OUT)), "fixtures to", os.path.normpath(OUT))
