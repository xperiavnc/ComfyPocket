package com.comfyui.mobile.danbooru

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DanbooruViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private class Service : DanbooruService, DanbooruDataSource {
        override var settings = DanbooruSettings()
        override val repository get() = this
        val requests = mutableListOf<Pair<String, String?>>()
        val refreshFlags = mutableListOf<Boolean>()
        var failSave = false
        var cleared = false
        var failTest: DanbooruException? = null
        var handler: (suspend (DanbooruQuery, String?) -> List<DanbooruPost>)? = null
        override suspend fun posts(query: DanbooruQuery, page: String?, refresh: Boolean): List<DanbooruPost> {
            requests += query.tags to page
            refreshFlags += refresh
            handler?.let { return it(query, page) }
            val top = page?.removePrefix("b")?.toLong()?.minus(1) ?: 10000L
            return (0..39).map { fixture(top - it) }
        }
        override suspend fun post(id: Long) = fixture(id)
        override suspend fun verify(settings: DanbooruSettings) = DanbooruIdentity(12, settings.username, "Member")
        override suspend fun test(settings: DanbooruSettings): DanbooruIdentity? {
            failTest?.let { throw it }
            return if (settings.loggedIn) verify(settings) else null
        }
        override suspend fun save(settings: DanbooruSettings) {
            if (failSave) throw java.io.IOException("fixture")
            this.settings = settings
        }
        override suspend fun clearCache() { cleared = true }
    }
    companion object {
        private fun fixture(id: Long) = DanbooruPost(id, mapOf(TagGroup.GENERAL to listOf("blue_sky", "cloud")))
    }
    @Test fun loadsAtLeastFiveFullPagesUsingMinimumIdWithoutAnArtificialEnd() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.setActive(true); advanceUntilIdle()
        repeat(5) { vm.loadNext(); advanceUntilIdle() }
        assertEquals(240, vm.state.value.posts.size)
        assertEquals(listOf(null, "b9961", "b9921", "b9881", "b9841", "b9801"), service.requests.map { it.second })
        assertFalse(vm.state.value.ended)
    }
    @Test fun boundedWindowRetainsCursorsToLoadEvictedPagesInBothDirections() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.setActive(true); advanceUntilIdle()
        repeat(11) { vm.loadNext(); advanceUntilIdle() }
        assertEquals(8, vm.state.value.pages.size)
        assertEquals(5, vm.state.value.pages.first().number)
        assertEquals(320, vm.state.value.posts.size)
        vm.previous(); advanceUntilIdle()
        assertEquals(4, vm.state.value.pages.first().number)
        assertEquals("b9881", service.requests.last().second)
        assertFalse(vm.state.value.ended)
        vm.loadNext(); advanceUntilIdle()
        assertEquals(12, vm.state.value.pages.last().number)
    }
    @Test fun failedAppendKeepsAllPostsAndRetryUsesTheSameCursor() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.setActive(true); advanceUntilIdle()
        service.handler = { _, _ -> throw DanbooruException("timeout") }
        vm.loadNext(); advanceUntilIdle()
        assertEquals(40, vm.state.value.posts.size); assertFalse(vm.state.value.ended)
        assertEquals("timeout", vm.state.value.appendError)
        service.handler = null
        vm.retry(); advanceUntilIdle()
        assertEquals(80, vm.state.value.posts.size)
        assertEquals(service.requests[1].second, service.requests[2].second)
    }
    @Test fun successfulEmptyPageIsTheOnlyNormalEnd() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.setActive(true); advanceUntilIdle()
        service.handler = { _, _ -> emptyList() }
        vm.loadNext(); advanceUntilIdle()
        assertTrue(vm.state.value.ended)
        assertEquals(40, vm.state.value.posts.size)
        vm.loadNext(); advanceUntilIdle()
        assertEquals(2, service.requests.size)
    }
    @Test fun duplicateOnlyPageIsRetryableNotAnEnd() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.setActive(true); advanceUntilIdle()
        val previous = vm.state.value.posts
        service.handler = { _, _ -> previous }
        vm.loadNext(); advanceUntilIdle()
        assertFalse(vm.state.value.ended); assertNotNull(vm.state.value.appendError)
        assertEquals(40, vm.state.value.posts.size)
    }
    @Test fun scoreSortUsesNumericPagesAnd410DoesNotChangeTheOrder() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        service.handler = { _, page -> if (page == null) listOf(fixture(70)) else throw DanbooruException("boundary", 410) }
        vm.search("order:score"); vm.setActive(true); advanceUntilIdle()
        vm.loadNext(); advanceUntilIdle()
        assertEquals("2", service.requests.last().second)
        assertTrue(vm.state.value.boundary)
        assertFalse(vm.state.value.ended)
        assertEquals("order:score", vm.state.value.query.order)
        vm.loadNext(); advanceUntilIdle(); assertEquals(2, service.requests.size)
    }
    @Test fun repeatedClicksDoNotStartTheSameCursorTwice() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.setActive(true); vm.setActive(true); advanceUntilIdle()
        repeat(10) { vm.loadNext() }; advanceUntilIdle()
        assertEquals(2, service.requests.size)
    }
    @Test fun lateResultsFromTheOldQueryCannotOverwriteNewSearch() = runTest {
        val service = Service()
        service.handler = { q, _ ->
            if (q.text == "old") withContext(NonCancellable) { delay(200); listOf(fixture(1)) }
            else listOf(fixture(2))
        }
        val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.search("old"); vm.setActive(true); runCurrent()
        vm.search("new"); advanceUntilIdle()
        assertEquals("new", vm.state.value.query.text)
        assertEquals(2L, vm.state.value.posts.single().id)
    }
    @Test fun leavingCancelsPendingWorkAndDoesNotAutomaticallyContinuePaging() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.setActive(true); advanceUntilIdle()
        vm.setActive(false); vm.loadNext(); advanceUntilIdle()
        assertEquals(1, service.requests.size)
        vm.setActive(true); advanceUntilIdle()
        assertEquals(1, service.requests.size)
    }
    @Test fun savedWindowScrollAndDetailRestoreWithoutSavingCredentials() = runTest {
        val service = Service(); val saved = SavedStateHandle()
        val first = DanbooruViewModel(saved, service); first.setActive(true); advanceUntilIdle()
        first.loadNext(); advanceUntilIdle()
        first.scrollIndex = 45; first.scrollOffset = 17
        first.open(first.state.value.posts[43]); advanceUntilIdle()
        val second = DanbooruViewModel(saved, service); second.setActive(true); advanceUntilIdle()
        assertEquals(80, second.state.value.posts.size)
        assertEquals(45, second.scrollIndex); assertEquals(17, second.scrollOffset)
        assertEquals(first.state.value.detail?.id, second.state.value.detail?.id)
        assertFalse(saved.keys().any { it.contains("key", true) || it.contains("password", true) })
    }
    @Test fun backClosesSettingsThenSelectionThenDetailOneActionAtATime() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.setActive(true); advanceUntilIdle(); vm.open(vm.state.value.posts[0]); advanceUntilIdle()
        vm.select("cloud"); vm.showSettings()
        vm.back(); assertFalse(vm.state.value.settingsOpen); assertTrue(vm.hasDetail); assertTrue(vm.state.value.selected.isNotEmpty())
        vm.back(); assertTrue(vm.hasDetail); assertTrue(vm.state.value.selected.isEmpty())
        vm.back(); assertFalse(vm.hasDetail); assertEquals(40, vm.state.value.posts.size)
    }
    @Test fun unsavedConnectionTestDoesNotSaveOrVerifyStoredAccount() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        val result = vm.test(DanbooruSettings("user", "fixture-key"))
        assertTrue(result.contains("尚未保存"))
        assertEquals(AccountStatus.ANONYMOUS, vm.state.value.account)
        assertFalse(service.settings.loggedIn)
    }
    @Test fun savedConfigHasExplicitPendingVerifiedAndFailureStatesWithoutDeletingKey() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        val config = DanbooruSettings("user", "fixture-key")
        assertTrue(vm.save(config).isSuccess)
        assertEquals(AccountStatus.SAVED, vm.state.value.account)
        vm.test(config); assertEquals(AccountStatus.VERIFIED, vm.state.value.account)
        service.failTest = DanbooruException("timeout")
        try { vm.test(config); fail() } catch (_: DanbooruException) { }
        assertEquals(AccountStatus.FAILED, vm.state.value.account)
        assertEquals("fixture-key", service.settings.apiKey)
    }
    @Test fun failedSaveLeavesTheWholeConfigurationUntouched() = runTest {
        val service = Service(); service.failSave = true
        val vm = DanbooruViewModel(SavedStateHandle(), service)
        assertTrue(vm.save(DanbooruSettings("user", "fixture-key")).isFailure)
        assertEquals(DanbooruSettings(), service.settings)
        assertNull(vm.state.value.message)
    }
    @Test fun logoutRetainsProxyAndClearCacheRetainsLogin() = runTest {
        val service = Service()
        service.settings = DanbooruSettings("user", "fixture-key", true, "http://localhost:8080")
        val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.clearCache(); assertTrue(service.cleared); assertTrue(service.settings.loggedIn)
        assertTrue(vm.logout().isSuccess)
        assertFalse(service.settings.loggedIn); assertTrue(service.settings.proxyEnabled)
        assertEquals("http://localhost:8080", service.settings.proxyAddress)
    }
    @Test fun hotSortResetsLatestCursorAndPreservesTagsAndExplicitRating() = runTest {
        val service = Service()
        service.handler = { _, page -> if (page == null) listOf(fixture(3), fixture(80)) else listOf(fixture(9), fixture(7)) }
        val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.setActive(true); advanceUntilIdle()
        vm.sort("blue_sky rating:s order:score", DanbooruSort.HOT); advanceUntilIdle()
        assertEquals("blue_sky rating:s", vm.state.value.query.text)
        assertEquals(ContentFilter.SENSITIVE, vm.state.value.query.filter)
        assertTrue(service.requests.last().first.contains("order:rank"))
        assertNull(service.requests.last().second)
        vm.loadNext(); advanceUntilIdle()
        assertEquals("2", service.requests.last().second)
        assertTrue(service.refreshFlags.last())
        assertEquals(listOf(3L, 80L, 9L, 7L), vm.state.value.posts.map { it.id })
    }
    @Test fun rankPeriodAndDateSurviveSavedStateAndKeepNumberedPagination() = runTest {
        val service = Service()
        service.handler = { _, page -> listOf(fixture(if (page == null) 60 else 40)) }
        val saved = SavedStateHandle()
        val vm = DanbooruViewModel(saved, service)
        vm.setActive(true); advanceUntilIdle()
        vm.rankingPeriod("blue_sky", RankingPeriod.MONTH); advanceUntilIdle()
        vm.rankingDate(java.time.LocalDate.of(2025, 12, 31)); advanceUntilIdle()
        vm.loadNext(); advanceUntilIdle()
        assertEquals("2", service.requests.last().second)
        assertTrue(service.requests.last().first.contains("date:2025-12-01..2026-01-01"))
        val restored = DanbooruViewModel(saved, service)
        assertEquals(DanbooruSort.RANKING, restored.state.value.query.sort)
        assertEquals(RankingPeriod.MONTH, restored.state.value.query.rankingPeriod)
        assertEquals(vm.state.value.query.tags, restored.state.value.query.tags)
    }
    @Test fun pullRefreshKeepsPicturesAndRejectsDuplicateRefreshOrAppendUntilComplete() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.setActive(true); advanceUntilIdle()
        val previous = vm.state.value.posts
        service.handler = { _, _ -> delay(100); listOf(fixture(20000)) }
        vm.refresh(); runCurrent()
        assertTrue(vm.state.value.refreshing)
        assertEquals(previous, vm.state.value.posts)
        vm.refresh(); vm.loadNext(); runCurrent()
        assertEquals(2, service.requests.size)
        advanceUntilIdle()
        assertFalse(vm.state.value.refreshing)
        assertEquals(listOf(20000L), vm.state.value.posts.map { it.id })
        assertTrue(service.refreshFlags.last())
        assertEquals(1, vm.state.value.pages.single().number)
    }
    @Test fun failedRefreshKeepsTheWindowAndCanRetryFromTheFirstPage() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.setActive(true); advanceUntilIdle(); vm.loadNext(); advanceUntilIdle()
        val previous = vm.state.value.pages
        service.handler = { _, _ -> throw DanbooruException("refresh timeout") }
        vm.refresh(); advanceUntilIdle()
        assertFalse(vm.state.value.refreshing); assertEquals(previous, vm.state.value.pages)
        assertEquals("refresh timeout", vm.state.value.error); assertFalse(vm.state.value.ended)
        service.handler = { _, _ -> listOf(fixture(30000)) }
        vm.refresh(); advanceUntilIdle()
        assertNull(service.requests.last().second)
        assertNull(vm.state.value.error); assertEquals(30000L, vm.state.value.posts.single().id)
    }
    @Test fun lateRefreshCannotOverwriteANewSortAndLeavingResetsIndicator() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.setActive(true); advanceUntilIdle()
        service.handler = { q, _ ->
            if (q.sort == DanbooruSort.LATEST) withContext(NonCancellable) { delay(100); listOf(fixture(1)) }
            else listOf(fixture(2))
        }
        vm.refresh(); runCurrent()
        vm.sort("", DanbooruSort.HOT); advanceUntilIdle()
        assertEquals(DanbooruSort.HOT, vm.state.value.query.sort)
        assertEquals(2L, vm.state.value.posts.single().id)
        assertFalse(vm.state.value.refreshing)
        vm.setActive(false); assertFalse(vm.state.value.refreshing)
    }
    @Test fun resumingAfterAnotherUiSavedConnectionDiscardsTheOldWindowAndIdentity() = runTest {
        val service = Service(); val vm = DanbooruViewModel(SavedStateHandle(), service)
        vm.setActive(true); advanceUntilIdle()
        vm.open(vm.state.value.posts.first()); advanceUntilIdle()
        vm.setActive(false)
        service.settings = DanbooruSettings("other-user", "fixture-key", defaultFilter = ContentFilter.SENSITIVE)
        service.handler = { _, _ -> listOf(fixture(90000)) }
        vm.setActive(true); advanceUntilIdle()
        assertEquals(listOf(90000L), vm.state.value.posts.map { it.id })
        assertFalse(vm.hasDetail)
        assertEquals(AccountStatus.SAVED, vm.state.value.account)
        assertNull(vm.state.value.identity)
        assertTrue(service.requests.last().first.contains("rating:s"))
        assertNull(service.requests.last().second)
    }
}
