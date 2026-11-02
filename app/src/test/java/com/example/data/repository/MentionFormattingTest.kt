package com.example.data.repository

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.extractActiveMentionToken
import com.example.ui.components.formatCommentWithMentions
import com.example.ui.screens.highlightMentionsInNotificationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MentionFormattingTest {

    private lateinit var context: Context
    private lateinit var repository: AzureDevOpsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = AzureDevOpsRepository(context)
        AzureDevOpsRepository.clearKnownIdentitiesForTesting()
    }

    @Test
    fun extractActiveMentionToken_supportsAtAndAtBracketAndIgnoresCompletedBracketMention() {
        // 1. Typing @ali
        val tokenPlain = extractActiveMentionToken(
            TextFieldValue(text = "Hello @ali", selection = TextRange(10))
        )
        assertNotNull(tokenPlain)
        assertEquals(6, tokenPlain!!.startIndex)
        assertEquals(10, tokenPlain.endIndex)
        assertEquals("ali", tokenPlain.query)

        // 2. Typing @<ali
        val tokenBracket = extractActiveMentionToken(
            TextFieldValue(text = "Hello @<ali", selection = TextRange(11))
        )
        assertNotNull(tokenBracket)
        assertEquals(6, tokenBracket!!.startIndex)
        assertEquals(11, tokenBracket.endIndex)
        assertEquals("ali", tokenBracket.query)

        // 3. After inserting @<Alice Johnson> , autocomplete should close (return null)
        val completedText = "Hello @<Alice Johnson> please check"
        val tokenAfterClose = extractActiveMentionToken(
            TextFieldValue(text = completedText, selection = TextRange("Hello @<Alice Johnson> ".length))
        )
        assertNull(tokenAfterClose)
    }

    @Test
    fun serializeCommentForAzureMarkdown_resolvesDisplayNameAndEmailToGuidWithoutHtmlEscaping() {
        val aliceGuid = "d6245f20-2af8-44f4-9451-8107cb2767db"
        AzureDevOpsRepository.registerKnownIdentity(
            id = aliceGuid,
            displayName = "Alice Johnson",
            email = "alice@contoso.com"
        )
        AzureDevOpsRepository.registerKnownIdentity(
            id = null,
            displayName = "Bob Smith",
            email = "bob@contoso.com"
        )

        val input = "Hi @<Alice Johnson> and @<Bob Smith>,\nPlease check this item."
        val serialized = AzureDevOpsRepository.serializeCommentForAzureMarkdown(input)

        // Alice has a known GUID -> resolved to @<GUID>
        // Bob has no GUID yet -> preserved as @<Bob Smith>
        // Angle brackets and newlines are NOT HTML-escaped
        assertEquals(
            "Hi @<$aliceGuid> and @<Bob Smith>,\nPlease check this item.",
            serialized
        )
        assertFalse(serialized.contains("&lt;"))
        assertFalse(serialized.contains("&gt;"))
        assertFalse(serialized.contains("<br/>"))
    }

    @Test
    fun normalizeCommentTextFromAzure_resolvesGuidViaRenderedTextAndPreservesBracketMentions() {
        val sarahGuid = "88a12b34-56c7-48d9-90e1-23456789abcd"
        val rawMarkdownFromServer = "Hello @<$sarahGuid>, can you verify the fix?"
        val renderedHtmlFromServer =
            "<p>Hello <a href=\"#\" data-vss-mention=\"version:2.0,$sarahGuid\">@Sarah Connor</a>, can you verify the fix?</p>"

        val normalized = AzureDevOpsRepository.normalizeCommentTextFromAzure(
            rawText = rawMarkdownFromServer,
            renderedText = renderedHtmlFromServer
        )

        // Should resolve @<GUID> to @<Sarah Connor> and never strip <Sarah Connor> as an HTML tag
        assertEquals("Hello @<Sarah Connor>, can you verify the fix?", normalized)
    }

    @Test
    fun formatCommentWithMentions_and_highlightMentionsInNotificationText_renderCleanPillWithoutBrackets() {
        val guid = "11223344-5566-7788-99aa-bbccddeeff00"
        AzureDevOpsRepository.registerKnownIdentity(
            id = guid,
            displayName = "Reza Keshavarzi",
            email = "reza@example.com"
        )

        // 1. Discussion comment card formatting
        val commentText = "Assigned to @<Reza Keshavarzi> and cc @<$guid> for review."
        val annotatedDiscussion = formatCommentWithMentions(
            text = commentText,
            knownPeopleNames = listOf("Reza Keshavarzi")
        )
        assertEquals(
            "Assigned to @Reza Keshavarzi and cc @Reza Keshavarzi for review.",
            annotatedDiscussion.text
        )
        assertFalse(annotatedDiscussion.text.contains("<"))
        assertFalse(annotatedDiscussion.text.contains(">"))
        assertTrue(annotatedDiscussion.spanStyles.size >= 2)

        // 2. Notification snippet formatting
        val annotatedNotification = highlightMentionsInNotificationText(commentText)
        assertEquals(
            "Assigned to @Reza Keshavarzi and cc @Reza Keshavarzi for review.",
            annotatedNotification.text
        )
        assertFalse(annotatedNotification.text.contains("<"))
        assertFalse(annotatedNotification.text.contains(">"))
        assertTrue(annotatedNotification.spanStyles.size >= 2)
    }

    @Test
    fun commentMentionsIdentity_matchesBracketedDisplayNameEmailAndUserId() {
        val userId = "f0e1d2c3-b4a5-6789-0123-456789abcdef"
        val displayName = "Alice Johnson"
        val email = "alice@contoso.com"

        assertTrue(
            repository.commentMentionsIdentity(
                commentText = "Hey @<Alice Johnson>, take a look",
                effectiveDisplayName = displayName,
                detectedEmail = email,
                detectedUserId = userId
            )
        )
        assertTrue(
            repository.commentMentionsIdentity(
                commentText = "Hey @<$userId>, take a look",
                effectiveDisplayName = displayName,
                detectedEmail = email,
                detectedUserId = userId
            )
        )
        assertTrue(
            repository.commentMentionsIdentity(
                commentText = "Hey @<$email>, take a look",
                effectiveDisplayName = displayName,
                detectedEmail = email,
                detectedUserId = userId
            )
        )
        assertFalse(
            repository.commentMentionsIdentity(
                commentText = "Hey @<Someone Else>, take a look",
                effectiveDisplayName = displayName,
                detectedEmail = email,
                detectedUserId = userId
            )
        )
    }

    @Test
    fun evaluateWorkItemCommentsForMentions_triggersNotificationForSelfMention() = kotlinx.coroutines.test.runTest {
        val org = "ContosoOrg"
        val displayName = "Alice Johnson"
        val email = "alice@contoso.com"
        val userId = "f0e1d2c3-b4a5-6789-0123-456789abcdef"

        repository.setManualIdentityOverride(org, displayName)
        AzureDevOpsRepository.registerKnownIdentity(
            id = userId,
            displayName = displayName,
            email = email
        )

        val workItem = com.example.data.model.WorkItem(
            id = 101,
            title = "Test Work Item",
            workItemType = "Task",
            state = "Active",
            teamProject = "ProjectA"
        )

        val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

        val selfComment = com.example.data.model.WorkItemComment(
            id = 202,
            workItemId = 101,
            text = "Testing mention for @<Alice Johnson> please check",
            authorName = "Alice Johnson",
            authorEmail = email,
            createdDate = nowIso,
            formattedDate = "Just now"
        )

        val outcome = repository.evaluateWorkItemCommentsForMentions(
            organization = org,
            workItem = workItem,
            comments = listOf(selfComment),
            allowSystemNotification = true
        )

        assertEquals(1, outcome.newlyTriggeredNotifications.size)
        val triggered = outcome.newlyTriggeredNotifications.first()
        assertEquals(com.example.data.model.NotificationCategory.MENTIONED, triggered.category)
        assertEquals(101, triggered.workItemId)
        assertEquals(202, triggered.commentId)
    }
}

