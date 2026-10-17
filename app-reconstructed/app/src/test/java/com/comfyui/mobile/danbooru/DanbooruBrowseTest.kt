package com.comfyui.mobile.danbooru

import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DanbooruBrowseTest {
    private val date = LocalDate.of(2026, 10, 17)

    @Test fun hotAndFavoritesUseServerOrdersAndNumericPaging() {
        for ((sort, order) in listOf(DanbooruSort.HOT to "order:rank", DanbooruSort.SCORE to "order:score",
            DanbooruSort.FAVORITES to "order:favcount")) {
            val query = DanbooruQuery.resolve("blue_sky -cloud", ContentFilter.GENERAL, sort)
            assertTrue(query.tags.contains(order))
            assertTrue(query.tags.contains("blue_sky -cloud"))
            assertFalse(query.cursorPaging)
            assertEquals(sort, query.sort)
        }
        assertTrue(DanbooruQuery.resolve("", ContentFilter.GENERAL).cursorPaging)
    }

    @Test fun leaderboardsUseOfficialCalendarRangesAndActivePosts() {
        val expected = mapOf(RankingPeriod.DAY to "date:2026-10-17..2026-10-18",
            RankingPeriod.WEEK to "date:2026-10-12..2026-10-19",
            RankingPeriod.MONTH to "date:2026-10-01..2026-11-01")
        expected.forEach { (period, range) ->
            val query = DanbooruQuery.resolve("blue_sky", ContentFilter.GENERAL, DanbooruSort.RANKING, period, date)
            assertTrue(query.tags, query.tags.contains(range))
            assertTrue(query.tags.contains("status:active"))
            assertEquals(1, query.tags.split(" ").count { it == "order:score" })
            assertFalse(query.cursorPaging)
        }
    }

    @Test fun calendarNavigationHandlesYearAndLeapMonthBoundaries() {
        assertEquals(LocalDate.of(2025, 12, 29), RankingPeriod.WEEK.start(LocalDate.of(2026, 1, 1)))
        assertEquals("date:2024-02-01..2024-03-01", RankingPeriod.MONTH.dateTag(LocalDate.of(2024, 2, 29)))
        assertEquals(LocalDate.of(2026, 1, 1), RankingPeriod.MONTH.shift(LocalDate.of(2025, 12, 31), 1))
        assertEquals("2026-10-12 — 2026-10-18", RankingPeriod.WEEK.label(date))
    }

    @Test fun typedOrdersWinWithoutDuplicateSortOrDateInjection() {
        val query = DanbooruQuery.resolve("blue_sky rating:s order:favcount", ContentFilter.GENERAL,
            DanbooruSort.RANKING, RankingPeriod.WEEK, date)
        assertEquals(DanbooruSort.FAVORITES, query.sort)
        assertEquals(ContentFilter.SENSITIVE, query.filter)
        assertFalse(query.tags.contains("date:"))
        assertFalse(query.tags.contains("rating:g"))
        assertEquals("blue_sky rating:s", DanbooruQuery.withoutOrder("blue_sky ORDER:score rating:s"))
    }

    @Test fun repositoryPreservesRankOrderAndSendsTheSecondNumericPage() = runBlocking {
        MockWebServer().use { server ->
            val cache = Files.createTempDirectory("danbooru-ranking").toFile()
            try {
                val repository = DanbooruRepository(OkHttpClient(), DanbooruApiQueue(0), cache, server.url("/"))
                val query = DanbooruQuery.resolve("blue_sky", ContentFilter.GENERAL, DanbooruSort.RANKING, RankingPeriod.WEEK, date)
                server.enqueue(MockResponse().setBody("""[{"id":3},{"id":100},{"id":8}]"""))
                server.enqueue(MockResponse().setBody("""[{"id":12},{"id":9}]"""))
                assertEquals(listOf(3L, 100L, 8L), repository.posts(query, null).map { it.id })
                assertEquals(listOf(12L, 9L), repository.posts(query, "2").map { it.id })
                assertEquals(query.tags, server.takeRequest().requestUrl!!.queryParameter("tags"))
                val next = server.takeRequest()
                assertEquals("2", next.requestUrl!!.queryParameter("page"))
                assertEquals(query.tags, next.requestUrl!!.queryParameter("tags"))
            } finally { cache.deleteRecursively() }
        }
    }

    @Test fun cacheSeparatesDifferentLeaderboardsAndPeriods() = runBlocking {
        MockWebServer().use { server ->
            val cache = Files.createTempDirectory("danbooru-period-cache").toFile()
            try {
                val repository = DanbooruRepository(OkHttpClient(), DanbooruApiQueue(0), cache, server.url("/"))
                val day = DanbooruQuery.resolve("", ContentFilter.GENERAL, DanbooruSort.RANKING, RankingPeriod.DAY, date)
                val week = day.let { DanbooruQuery.resolve("", it.filter, it.sort, RankingPeriod.WEEK, date) }
                server.enqueue(MockResponse().setBody("""[{"id":1}]"""))
                server.enqueue(MockResponse().setBody("""[{"id":2}]"""))
                assertEquals(1L, repository.posts(day, null).single().id)
                assertEquals(2L, repository.posts(week, null).single().id)
                assertEquals(1L, repository.posts(day, null).single().id)
                assertEquals(2, server.requestCount)
            } finally { cache.deleteRecursively() }
        }
    }
}
