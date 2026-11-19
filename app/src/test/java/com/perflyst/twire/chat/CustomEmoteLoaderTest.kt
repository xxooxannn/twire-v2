package com.perflyst.twire.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CustomEmoteLoaderTest {
    @Test
    fun failedGlobalRequestsStillLoadChannelKeywords() {
        val loader = CustomEmoteLoader { url ->
            if (url.endsWith("emote-sets/global")) throw IOException("timeout")
            sevenTvUser(sevenTvEmote("OMEGALUL"))
        }

        val result = loader.load("71092938", "xqc", false, false, true)

        assertTrue(result.global.isEmpty())
        assertEquals(listOf("OMEGALUL"), result.channel.map { it.keyword })
        assertTrue(result.channel.single().isCustomChannelEmote)
        val index = EmoteKeyIndex.of(result.channel)
        try {
            assertEquals("OMEGALUL", index.findInMessage("😂 OMEGALUL")[3]?.keyword)
        } finally {
            index.close()
        }
    }

    @Test
    fun failedChannelRequestsKeepGlobalEmotes() {
        val loader = CustomEmoteLoader { url ->
            if (url.contains("users/twitch")) throw IOException("timeout")
            sevenTvSet(sevenTvEmote("GIGACHAD"))
        }

        val result = loader.load("71092938", "xqc", false, false, true)

        assertEquals(listOf("GIGACHAD"), result.global.map { it.keyword })
        assertFalse(result.global.single().isCustomChannelEmote)
        assertTrue(result.channel.isEmpty())
    }

    @Test
    fun fallsBackAfterEmptyMalformedOrErrorResponses() {
        for (badResponse in listOf("", "<html>Bad Gateway</html>", """{"error":"Unavailable"}""")) {
            val requests = Collections.synchronizedList(ArrayList<String>())
            val loader = CustomEmoteLoader { url ->
                requests.add(url)
                when {
                    url.startsWith("https://api.7tv.app/") -> badResponse
                    url.endsWith("emote-sets/global") -> sevenTvSet(sevenTvEmote("Global"))
                    else -> sevenTvUser(sevenTvEmote("Channel"))
                }
            }

            val result = loader.load("71092938", "xqc", false, false, true)

            assertEquals(listOf("Global"), result.global.map { it.keyword })
            assertEquals(listOf("Channel"), result.channel.map { it.keyword })
            assertEquals(2, requests.count { it.startsWith("https://7tv.io/") })
        }
    }

    @Test
    fun bttvErrorDoesNotSkipFfzOrSevenTv() {
        val loader = CustomEmoteLoader { url ->
            when {
                url.endsWith("cached/emotes/global") -> """[{"id":"1","code":"BTTVGlobal"}]"""
                url.contains("betterttv.net") -> """{"message":"user not found"}"""
                url.endsWith("/set/global") -> ffzGlobal()
                url.contains("frankerfacez.com") -> """{"sets":{}}"""
                url.endsWith("emote-sets/global") -> sevenTvSet()
                else -> sevenTvUser(sevenTvEmote("Channel"))
            }
        }

        val result = loader.load("71092938", "xqc", true, true, true)

        assertEquals(listOf("BTTVGlobal", "FFZGlobal"), result.global.map { it.keyword })
        assertEquals(listOf("Channel"), result.channel.map { it.keyword })
    }

    @Test
    fun bttvGlobalTimeoutDoesNotSkipSharedChannelEmotes() {
        val loader = CustomEmoteLoader { url ->
            if (url.endsWith("/global")) throw IOException("timeout")
            """{"channelEmotes":[{"id":"1","code":"Channel"}],"sharedEmotes":[{"id":"2","code":"Shared"}]}"""
        }

        val result = loader.load("71092938", "xqc", true, false, false)

        assertEquals(listOf("Channel", "Shared"), result.channel.map { it.keyword })
        assertEquals("https://cdn.betterttv.net/emote/2/2x", result.channel[1].getEmoteUrl(2, true))
    }

    @Test
    fun malformedAndDeletedEmotesDoNotDiscardRestOfSet() {
        val loader = CustomEmoteLoader { url ->
            if (url.endsWith("emote-sets/global")) sevenTvSet() else sevenTvUser(
                sevenTvEmote("Before"),
                JSONObject().put("name", "Deleted").put("data", JSONObject.NULL),
                JSONObject().put("name", "NoImages").put("data", JSONObject().put("host",
                    JSONObject().put("url", "//cdn.7tv.app/emote/no-images").put("files", JSONArray()))),
                sevenTvEmote("After")
            )
        }

        val result = loader.load("71092938", "xqc", false, false, true)

        assertEquals(listOf("Before", "After"), result.channel.map { it.keyword })
    }

    @Test
    fun preservesAliasesAndPrefersWebpRegardlessOfFileOrder() {
        for (base in listOf("//cdn.7tv.app/emote/id", "https://cdn.7tv.app/emote/id/")) {
            for (files in listOf(listOf("1x.webp", "1x.gif", "1x.avif"), listOf("1x.avif", "1x.gif", "1x.webp"))) {
                val emote = sevenTvEmote("ChannelAlias", base, files)
                val loader = CustomEmoteLoader { url ->
                    if (url.endsWith("emote-sets/global")) sevenTvSet() else sevenTvUser(emote)
                }

                val result = loader.load("71092938", "xqc", false, false, true)

                assertEquals("ChannelAlias", result.channel.single().keyword)
                assertEquals("https://cdn.7tv.app/emote/id/1x.webp", result.channel.single().getEmoteUrl(1, true))
            }
        }
    }

    @Test
    fun noActiveSevenTvSetIsValidAndDoesNotRetry() {
        val requests = Collections.synchronizedList(ArrayList<String>())
        val loader = CustomEmoteLoader { url ->
            requests.add(url)
            if (url.endsWith("emote-sets/global")) sevenTvSet(sevenTvEmote("Global"))
            else """{"id":"71092938","emote_set":null}"""
        }

        val result = loader.load("71092938", "xqc", false, false, true)

        assertEquals(1, result.global.size)
        assertTrue(result.channel.isEmpty())
        assertEquals(2, requests.size)
    }

    @Test
    fun publishesFastProvidersWhileAnotherProviderIsStillLoading() {
        val releaseSlowProvider = CountDownLatch(1)
        val fastProviderPublished = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val loader = CustomEmoteLoader { url ->
            when {
                url == "https://api.betterttv.net/3/cached/emotes/global" -> {
                    releaseSlowProvider.await(3, TimeUnit.SECONDS)
                    """[{"id":"1","code":"BTTVGlobal"}]"""
                }
                url.endsWith("/set/global") -> ffzGlobal()
                url.contains("frankerfacez.com") -> """{"sets":{}}"""
                url.endsWith("emote-sets/global") -> sevenTvSet(sevenTvEmote("SevenGlobal"))
                url.contains("users/twitch") -> sevenTvUser(sevenTvEmote("SevenChannel"))
                else -> """{"channelEmotes":[],"sharedEmotes":[]}"""
            }
        }

        try {
            val result = worker.submit<CustomEmoteLoader.Result> {
                loader.load("71092938", "xqc", true, true, true) { partial ->
                    if (partial.global.any { it.keyword == "FFZGlobal" }) {
                        fastProviderPublished.countDown()
                    }
                }
            }

            assertTrue(
                "Fast provider results should publish before the slow BTTV response",
                fastProviderPublished.await(2, TimeUnit.SECONDS)
            )
            releaseSlowProvider.countDown()
            val loaded = result.get(5, TimeUnit.SECONDS)
            assertTrue(loaded.global.any { it.keyword == "BTTVGlobal" })
            assertTrue(loaded.global.any { it.keyword == "FFZGlobal" })
            assertTrue(loaded.global.any { it.keyword == "SevenGlobal" })
            assertTrue(loaded.channel.any { it.keyword == "SevenChannel" })
        } finally {
            releaseSlowProvider.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun ffzGlobalUsesDefaultSetsAndAnimatedImages() {
        val loader = CustomEmoteLoader { url ->
            if (url.endsWith("/set/global")) ffzGlobal() else """{"sets":{}}"""
        }

        val result = loader.load("71092938", "xqc", false, true, false)

        assertEquals("FFZGlobal", result.global.single().keyword)
        assertEquals("https://cdn.frankerfacez.com/emote/1/animated/1", result.global.single().getEmoteUrl(1, true))
    }

    @Test
    fun allFailuresReturnEmptyResultAndDisabledProvidersMakeNoRequests() {
        var requests = 0
        val loader = CustomEmoteLoader {
            requests++
            throw IOException("offline")
        }

        val disabled = loader.load("71092938", "xqc", false, false, false)
        assertTrue(disabled.global.isEmpty() && disabled.channel.isEmpty())
        assertEquals(0, requests)

        val offline = loader.load("71092938", "xqc", true, true, true)
        assertTrue(offline.global.isEmpty() && offline.channel.isEmpty())
        assertTrue(requests > 0)
    }

    private fun sevenTvEmote(
        alias: String,
        base: String = "//cdn.7tv.app/emote/id",
        files: List<String> = listOf("1x.webp", "2x.webp")
    ): JSONObject = JSONObject().put("name", alias).put("data", JSONObject()
        .put("name", "OriginalName")
        .put("host", JSONObject().put("url", base).put("files", JSONArray().apply {
            files.forEach { put(JSONObject().put("name", it)) }
        })))

    private fun sevenTvSet(vararg emotes: JSONObject): String =
        JSONObject().put("emotes", JSONArray(emotes.toList())).toString()

    private fun sevenTvUser(vararg emotes: JSONObject): String =
        JSONObject().put("emote_set", JSONObject(sevenTvSet(*emotes))).toString()

    private fun ffzGlobal(): String = """{
        "default_sets":[3],
        "sets":{"3":{"emoticons":[{
            "name":"FFZGlobal",
            "urls":{"1":"//cdn.frankerfacez.com/emote/1/1"},
            "animated":{"1":"//cdn.frankerfacez.com/emote/1/animated/1"}
        }]}}
    }"""
}
