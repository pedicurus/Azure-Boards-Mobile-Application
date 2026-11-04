package com.example.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AppNotificationItem
import com.example.data.model.CachedNotificationsState
import com.example.data.model.CachedOrganizationMetadata
import com.example.data.model.CachedOrganizationSnapshot
import com.example.data.model.NotificationCategory
import com.example.data.model.WorkItem
import com.example.di.AppContainer
import com.example.ui.ConnectionStatus
import com.example.ui.viewmodel.delegates.WorkItemFilterDelegate
import com.example.ui.viewmodel.delegates.WorkItemProcessDelegate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkItemLocalStoreStreamingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        AppContainer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        WorkItemLocalStore(context).clearAllCaches()
    }

    @After
    fun tearDown() {
        WorkItemLocalStore(context).clearAllCaches()
        AppContainer.resetForTesting()
    }

    @Test
    fun `streaming saveSnapshot and loadSnapshotSync round-trips from disk across store instances`() = runTest {
        val writerStore = WorkItemLocalStore(context)
        val snapshot = CachedOrganizationSnapshot(
            organization = "contoso-org",
            workItems = listOf(
                WorkItem(
                    id = 101,
                    title = "Stream JSON via Okio Sink",
                    workItemType = "User Story",
                    state = "Active",
                    assignedToName = "Ada Lovelace",
                    description = "Avoid large intermediate String allocations",
                    teamProject = "CorePlatform",
                    areaPath = "CorePlatform\\Storage",
                    iterationPath = "CorePlatform\\Sprint 12",
                    tags = listOf("perf", "okio")
                )
            ),
            unloadedProposedIds = listOf(202, 203),
            unloadedOtherIds = listOf(304),
            lastSyncedChangedDate = "2026-11-04T00:00:00Z",
            savedAtEpochMs = 1762214400000L
        )

        writerStore.saveSnapshot(snapshot)

        // Create a fresh WorkItemLocalStore instance so inMemorySnapshots is empty and disk streaming read is exercised
        val readerStore = WorkItemLocalStore(context)
        val loaded = readerStore.loadSnapshotSync("contoso-org")

        assertNotNull(loaded)
        assertEquals("contoso-org", loaded?.organization)
        assertEquals(1, loaded?.workItems?.size)
        val loadedItem = loaded!!.workItems.first()
        assertEquals(101, loadedItem.id)
        assertEquals("Stream JSON via Okio Sink", loadedItem.title)
        assertEquals("coreplatform\\storage", loadedItem.normAreaPathLower)
        assertEquals("coreplatform\\sprint 12", loadedItem.normIterationPathLower)
        assertEquals(listOf("perf", "okio"), loadedItem.tagsLower)
        assertEquals("avoid large intermediate string allocations", loadedItem.descriptionLower)
        assertEquals(listOf(202, 203), loaded.unloadedProposedIds)
    }

    @Test
    fun `streaming saveMetadata and loadMetadataSync round-trips from disk across store instances`() = runTest {
        val writerStore = WorkItemLocalStore(context)
        val metadata = CachedOrganizationMetadata(
            organization = "contoso-org",
            processHierarchyByProject = mapOf(
                "contoso-org|coreplatform" to mapOf("Bug" to listOf("New", "Active", "Resolved", "Closed"))
            ),
            categoryMapByProject = mapOf(
                "contoso-org|coreplatform" to mapOf("Bug" to "Microsoft.BugCategory")
            ),
            areasByProject = mapOf("contoso-org|coreplatform" to listOf("CorePlatform\\Backend")),
            iterationsByProject = mapOf("contoso-org|coreplatform" to listOf("CorePlatform\\Sprint 1")),
            schemaVersion = CachedOrganizationMetadata.CURRENT_SCHEMA_VERSION
        )

        writerStore.saveMetadata(metadata)

        val readerStore = WorkItemLocalStore(context)
        val loaded = readerStore.loadMetadataSync("contoso-org")

        assertNotNull(loaded)
        assertEquals("contoso-org", loaded?.organization)
        assertEquals(
            listOf("New", "Active", "Resolved", "Closed"),
            loaded?.processHierarchyByProject?.get("contoso-org|coreplatform")?.get("Bug")
        )
        assertEquals(CachedOrganizationMetadata.CURRENT_SCHEMA_VERSION, loaded?.schemaVersion)
    }

    @Test
    fun `streaming saveNotificationsState and loadNotificationsStateSync round-trips from disk`() = runTest {
        val writerStore = WorkItemLocalStore(context)
        val notificationsState = CachedNotificationsState(
            organization = "contoso-org",
            detectedDisplayName = "Grace Hopper",
            detectedEmail = "grace@contoso.com",
            detectedUserId = "user-123",
            notifications = listOf(
                AppNotificationItem(
                    id = "notif-1",
                    organization = "contoso-org",
                    workItemId = 501,
                    workItemTitle = "Optimize search debounce",
                    workItemType = "Task",
                    workItemState = "Active",
                    teamProject = "CorePlatform",
                    category = NotificationCategory.ASSIGNED,
                    actorName = "Alan Turing",
                    messageSnippet = "Assigned to Grace Hopper"
                )
            ),
            notifiedNotificationIds = listOf("notif-1"),
            baselineAssignedInitialized = true,
            baselineMentionsInitialized = true
        )

        writerStore.saveNotificationsState(notificationsState)

        val readerStore = WorkItemLocalStore(context)
        val loaded = readerStore.loadNotificationsStateSync("contoso-org")

        assertNotNull(loaded)
        assertEquals("Grace Hopper", loaded?.detectedDisplayName)
        assertEquals(1, loaded?.notifications?.size)
        assertEquals(501, loaded?.notifications?.first()?.workItemId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `WorkItemFilterDelegate updates searchQuery immediately and debounces non-blank queries`() = runTest {
        val container = AppContainer.getInstance(context)
        container.sessionManager.updateDefaultOrganization("contoso")
        container.sessionManager.updateConnectionStatus(ConnectionStatus.Connected("contoso"))

        val allItemsFlow = MutableStateFlow(
            listOf(
                WorkItem(
                    id = 1,
                    title = "Alpha feature",
                    workItemType = "Bug",
                    state = "Active",
                    teamProject = "ProjA"
                ),
                WorkItem(
                    id = 2,
                    title = "Beta bugfix",
                    workItemType = "Bug",
                    state = "Active",
                    teamProject = "ProjA"
                )
            )
        )
        val orgFlow = MutableStateFlow<String?>("contoso")
        val processDelegate = WorkItemProcessDelegate(
            repository = container.repository
        )
        val filterDelegate = WorkItemFilterDelegate(
            scope = backgroundScope,
            sessionManager = container.sessionManager,
            repository = container.repository,
            processDelegate = processDelegate,
            allWorkItems = allItemsFlow,
            workItemsOrg = orgFlow,
            onEnsureProjectProcessLoaded = {}
        )

        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            filterDelegate.filteredWorkItems.collect {}
        }

        // Wait for initial unfiltered emission
        val initialDeadline = System.currentTimeMillis() + 2000L
        while (filterDelegate.filteredWorkItems.value.size != 2 && System.currentTimeMillis() < initialDeadline) {
            Thread.sleep(10)
        }
        assertEquals(2, filterDelegate.filteredWorkItems.value.size)

        // Rapid typing updates searchQuery.value immediately
        filterDelegate.setSearchQuery("Be")
        filterDelegate.setSearchQuery("Bet")
        filterDelegate.setSearchQuery("Beta")
        assertEquals("Beta", filterDelegate.searchQuery.value)

        // Advance virtual time past 180ms debounce window
        advanceTimeBy(220L)
        val filteredDeadline = System.currentTimeMillis() + 2000L
        while (filterDelegate.filteredWorkItems.value.size != 1 && System.currentTimeMillis() < filteredDeadline) {
            Thread.sleep(10)
        }
        assertEquals(1, filterDelegate.filteredWorkItems.value.size)
        assertEquals(2, filterDelegate.filteredWorkItems.value.first().id)

        // Clearing search query resets immediately (0ms debounce)
        filterDelegate.setSearchQuery("")
        assertEquals("", filterDelegate.searchQuery.value)
        advanceTimeBy(10L)
        val resetDeadline = System.currentTimeMillis() + 2000L
        while (filterDelegate.filteredWorkItems.value.size != 2 && System.currentTimeMillis() < resetDeadline) {
            Thread.sleep(10)
        }
        assertEquals(2, filterDelegate.filteredWorkItems.value.size)

        collectJob.cancel()
    }
}
