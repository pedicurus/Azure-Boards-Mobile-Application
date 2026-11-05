package com.example.data.repository

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.WorkItemLocalStore
import com.example.data.model.AppNotificationItem
import com.example.data.model.CachedNotificationsState
import com.example.data.model.NotificationCategory
import com.example.data.model.WorkItem
import com.example.data.model.WorkItemComment
import com.example.ui.screens.NotificationsScreen
import com.example.ui.screens.computeStartOfCurrentMonthEpochMs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationsFilteringTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var repository: AzureDevOpsRepository
    private lateinit var localStore: WorkItemLocalStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = AzureDevOpsRepository(context)
        localStore = WorkItemLocalStore(context)
    }

    @Test
    fun testUnassignedWorkItemIsRemovedImmediately() = runBlocking {
        val org = "contoso"
        val nowMs = System.currentTimeMillis()
        val initialState = CachedNotificationsState(
            organization = org,
            detectedDisplayName = "Ali Keshavarzi",
            detectedEmail = "keshavarzi72@gmail.com",
            notifications = listOf(
                AppNotificationItem(
                    id = "assigned_contoso_101",
                    organization = org,
                    workItemId = 101,
                    workItemTitle = "Old item from 3 years ago",
                    workItemType = "Bug",
                    workItemState = "Active",
                    category = NotificationCategory.ASSIGNED,
                    epochMs = nowMs - 1000_000_000L,
                    isRead = false
                ),
                AppNotificationItem(
                    id = "assigned_contoso_102",
                    organization = org,
                    workItemId = 102,
                    workItemTitle = "Current active item",
                    workItemType = "Task",
                    workItemState = "Active",
                    category = NotificationCategory.ASSIGNED,
                    epochMs = nowMs,
                    isRead = false
                )
            ),
            knownAssignedItemStates = mapOf("101" to "ali keshavarzi", "102" to "ali keshavarzi")
        )
        localStore.saveNotificationsState(initialState)

        val updated = repository.removeAssignedNotificationForWorkItem(org, 101)
        assertEquals(1, updated.notifications.size)
        assertEquals(102, updated.notifications.first().workItemId)
        assertFalse(updated.knownAssignedItemStates.containsKey("101"))
    }

    @Test
    fun testOldMentionsDoNotTriggerPushNotifications() = runBlocking {
        val org = "contoso"
        val initialState = CachedNotificationsState(
            organization = org,
            detectedDisplayName = "Ali Keshavarzi",
            detectedEmail = "keshavarzi72@gmail.com",
            baselineAssignedInitialized = true,
            baselineMentionsInitialized = true
        )
        localStore.saveNotificationsState(initialState)

        val workItem = WorkItem(
            id = 500,
            title = "Legacy Architecture",
            workItemType = "User Story",
            state = "Active",
            teamProject = "Core"
        )

        val oldComment = WorkItemComment(
            id = 1,
            workItemId = 500,
            text = "Hey @Ali Keshavarzi please check this from 2023",
            authorName = "John Doe",
            authorEmail = "john@contoso.com",
            createdDate = "2023-04-10T12:00:00Z",
            formattedDate = "Apr 10, 2023"
        )

        val outcome = repository.evaluateWorkItemCommentsForMentions(
            organization = org,
            workItem = workItem,
            comments = listOf(oldComment),
            allowSystemNotification = true
        )

        // Old comment from 2023 must NOT trigger a push notification and must be marked read
        assertTrue("3-year-old mention must not trigger push notification", outcome.newlyTriggeredNotifications.isEmpty())
        assertEquals(1, outcome.state.notifications.size)
        assertTrue("Old mention outside this month should be marked read", outcome.state.notifications.first().isRead)
    }

    @Test
    fun testNotificationsScreenShowsOnlyThisMonthByDefaultAndExpandsOnSeeMore() {
        val startOfMonth = computeStartOfCurrentMonthEpochMs()
        val thisMonthItem = AppNotificationItem(
            id = "assigned_contoso_201",
            organization = "contoso",
            workItemId = 201,
            workItemTitle = "This Month Feature Task",
            workItemType = "Task",
            workItemState = "Active",
            category = NotificationCategory.ASSIGNED,
            formattedDate = "Nov 1, 2026",
            epochMs = startOfMonth + 3600_000L,
            isRead = false
        )
        val threeYearsAgoItem = AppNotificationItem(
            id = "assigned_contoso_99",
            organization = "contoso",
            workItemId = 99,
            workItemTitle = "Ancient Task From 3 Years Ago",
            workItemType = "Bug",
            workItemState = "Closed",
            category = NotificationCategory.ASSIGNED,
            formattedDate = "May 12, 2023",
            epochMs = startOfMonth - (365L * 3 * 24 * 3600 * 1000L),
            isRead = true
        )

        composeTestRule.setContent {
            NotificationsScreen(
                organization = "contoso",
                effectiveUserIdentity = "Ali Keshavarzi",
                assignedNotifications = listOf(threeYearsAgoItem, thisMonthItem),
                mentionedNotifications = emptyList(),
                isSyncingNotifications = false,
                onRefreshNotifications = {},
                onNotificationClick = {},
                onMarkAllRead = {},
                onClose = {}
            )
        }

        // By default, only "This Month Feature Task" is visible
        composeTestRule.onNodeWithText("This Month Feature Task", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Ancient Task From 3 Years Ago", useUnmergedTree = true).assertDoesNotExist()

        // Scroll to and click "See More Items" button
        composeTestRule.onNodeWithTag("notifications_list_assigned")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("notifications_see_more_button"))
        composeTestRule.onNodeWithTag("notifications_see_more_button").assertExists()
        composeTestRule.onNodeWithTag("notifications_see_more_button").performClick()
        composeTestRule.waitForIdle()

        // After clicking See More Items, scroll to the older item and verify it exists
        composeTestRule.onNodeWithTag("notifications_list_assigned")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("notification_card_assigned_contoso_99"))
        composeTestRule.onNodeWithText("Ancient Task From 3 Years Ago", useUnmergedTree = true).assertExists()
    }

    @Test
    fun notificationsScreen_newUIRefinements_headerAndCardLabels() {
        val startOfMonth = computeStartOfCurrentMonthEpochMs()
        val item = AppNotificationItem(
            id = "assigned_contoso_201",
            organization = "contoso",
            workItemId = 201,
            workItemTitle = "Clean Up Refactor",
            workItemType = "Task",
            workItemState = "Active",
            category = NotificationCategory.ASSIGNED,
            formattedDate = "2026-11-02 · 14:30",
            epochMs = startOfMonth + 3600000L,
            isRead = false
        )

        composeTestRule.setContent {
            NotificationsScreen(
                organization = "contoso",
                effectiveUserIdentity = "Farhad",
                assignedNotifications = listOf(item),
                mentionedNotifications = emptyList(),
                isSyncingNotifications = false,
                onRefreshNotifications = {},
                onNotificationClick = {},
                onMarkAllRead = {},
                onClose = {}
            )
        }

        // "This Month" header must exist without month/year or unread counts
        composeTestRule.onNodeWithText("This Month", useUnmergedTree = true).assertExists()

        // Card should NOT have "Assigned to you" text label
        composeTestRule.onNodeWithText("Assigned to you", useUnmergedTree = true).assertDoesNotExist()

        // Work Item ID and title exist
        composeTestRule.onNodeWithText("#201", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Clean Up Refactor", useUnmergedTree = true).assertExists()
    }

    @Test
    fun testMentionWiqlQueriesAvoidContainsWordsForMultiWordAndGuidTokens() {
        val notifRepo = AzureNotificationsRepository(context, localStore)

        val tokens = notifRepo.buildMentionSearchTokens(
            displayName = "Ali Keshavarzi",
            email = "keshavarzi72@gmail.com",
            userId = "8c9d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"
        )
        assertTrue(tokens.contains("8c9d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"))
        assertTrue(tokens.contains("Ali Keshavarzi"))
        assertTrue(tokens.contains("Ali"))
        assertTrue(tokens.contains("Keshavarzi"))
        assertTrue(tokens.contains("keshavarzi72"))

        // Multi-word display name and hyphenated GUID must ONLY use CONTAINS, never CONTAINS WORDS
        val fullNameQueries = notifRepo.buildHistoryMentionQueriesForToken("Ali Keshavarzi")
        assertEquals(1, fullNameQueries.size)
        assertTrue(fullNameQueries.first().contains("[System.History] CONTAINS 'Ali Keshavarzi'"))

        val guidQueries = notifRepo.buildHistoryMentionQueriesForToken("8c9d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f")
        assertEquals(1, guidQueries.size)
        assertTrue(guidQueries.first().contains("[System.History] CONTAINS '8c9d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f'"))

        // Single-word token uses CONTAINS first and CONTAINS WORDS as fallback
        val singleWordQueries = notifRepo.buildHistoryMentionQueriesForToken("Keshavarzi")
        assertEquals(2, singleWordQueries.size)
        assertTrue(singleWordQueries[0].contains("[System.History] CONTAINS 'Keshavarzi'"))
        assertTrue(singleWordQueries[1].contains("[System.History] CONTAINS WORDS 'Keshavarzi'"))
    }

    @Test
    fun testPrioritizeMentionCandidateItemsPlacesCustomStateCommentedItemsBeforeCommentlessItems() {
        val notifRepo = AzureNotificationsRepository(context, localStore)

        // Create 85 commentless Active items modified recently
        val commentlessItems = (1..85).map { idx ->
            WorkItem(
                id = 1000 + idx,
                title = "Active Task #$idx",
                workItemType = "Task",
                state = "Active",
                teamProject = "Atlas",
                changedDate = "2026-11-04T10:00:00Z",
                commentCount = 0
            )
        }

        // Create #10395 in custom state "Ready to Review" with 2 comments
        val customStateCommentedItem = WorkItem(
            id = 10395,
            title = "URL to connect to production site",
            workItemType = "Product Backlog Item",
            state = "Ready to Review",
            teamProject = "Atlas",
            changedDate = "2026-11-02T09:30:00Z",
            commentCount = 2
        )

        val itemsById = (commentlessItems + customStateCommentedItem).associateBy { it.id }

        val prioritized = notifRepo.prioritizeMentionCandidateItems(
            itemsById = itemsById,
            highPriorityMentionIds = emptySet(),
            recentCommentedIds = setOf(10395),
            assignedToMeServerIds = emptyList()
        )

        assertTrue("Commented custom-state item #10395 must be prioritized first", prioritized.isNotEmpty())
        assertEquals(10395, prioritized.first().id)
        assertFalse(
            "Unassigned commentless items should not crowd out candidate list",
            prioritized.any { it.commentCount == 0 }
        )
    }
}


