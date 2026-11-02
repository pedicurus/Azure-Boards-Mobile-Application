# Implementation Plan: Fix Push Notifications for Discussion @Mentions

## Problem Summary & Root Causes
While assignment notifications worked as expected, mention push notifications failed to deliver due to three specific issues:
1. **Self-Mention Suppression**: In `AzureDevOpsRepository`, `isJustMentionedForPush` and `shouldMarkUnread` explicitly required `!isAuthorSelf`. When you test by mentioning yourself (or commenting as "You" / your own account), the app recognized the author as yourself and intentionally suppressed push notifications.
2. **Missing In-App Push Trigger on Comment Post/Evaluation**: In `AzureDevOpsViewModel`, when a comment is posted or loaded, `evaluateWorkItemCommentsForMentions` detected the mention in-memory, but never invoked `NotificationHelper.postSystemNotification()`, and immediately marked the notification as read. Subsequent background syncs then ignored it because it was already stored.
3. **WIQL Syntax Error in Server Mention Discovery**: In `AzureDevOpsRepository.syncOrganizationNotifications`, the background query attempted `WHERE [System.History] CONTAINS '$token'`. In Azure DevOps WIQL, `[System.History]` requires `CONTAINS WORDS` (using `CONTAINS` triggers an HTTP 400 Bad Request error). Furthermore, work items that had new discussion comments were not being discovered if they weren't already assigned to you or pre-cached in memory.
4. **Missing Identity GUID Retrieval**: If `detectedDisplayName` was cached from an earlier session, `getConnectionData` was skipped, leaving `detectedUserId` null. Mentions formatted by Azure as `@<USER_GUID>` would then fail to match your identity.

---

## Proposed Changes

### 1. Enable Push Notifications for Self-Mentions & Testing (`AzureDevOpsRepository.kt`)
- In `evaluateWorkItemCommentsForMentions` and `syncOrganizationNotifications`:
  - Update `isJustMentionedForPush` so mentions of your account trigger push notifications even if you authored the comment.
  - Allow self-mentions to be flagged as unread and added to `newlyTriggeredNotifications`.
  - Maintain detection of both `@<DisplayName>`, `@<email>`, and `@<USER_GUID>` tokens.

### 2. Fix WIQL Server Query & Expand Recently Active Items (`AzureDevOpsRepository.kt`)
- Update the WIQL query syntax from `CONTAINS` to `CONTAINS WORDS` for `[System.History]`.
- Add a query for recently changed items:
  `SELECT [System.Id] FROM WorkItems WHERE [System.ChangedDate] >= @Today - 14 AND [System.State] <> 'Removed' ORDER BY [System.ChangedDate] DESC`
  to guarantee that any work item with recent discussion comments is scanned for mentions, even if it is not assigned to you.
- Always fetch and update `detectedUserId` from `getConnectionData` if `detectedUserId` is missing.

### 3. Trigger Device Push Notification on Comment Addition & Evaluation (`AzureDevOpsViewModel.kt`)
- In `evaluateWorkItemCommentsForMentions` within `AzureDevOpsViewModel.kt`:
  - When new mentions are found in `evaluateWorkItemCommentsForMentions`, invoke `NotificationHelper.postSystemNotification(appContext, notif)` for each item in `newlyTriggeredNotifications` if push notifications are enabled.
- In `addCommentToWorkItem`:
  - When the comment you posted contains a mention of yourself (e.g. `@<My Name>` or `@<Email>`), evaluate and immediately post the system push notification so you see instant device feedback.

---

## Verification Plan
1. **Unit Tests**:
   - Add unit tests in `MentionFormattingTest.kt` verifying:
     - `commentMentionsIdentity` accurately flags self-mentions.
     - Self-mentions are included in `newlyTriggeredNotifications` when evaluated in `evaluateWorkItemCommentsForMentions`.
     - WIQL token formation uses valid `CONTAINS WORDS` syntax.
2. **Build & Automated Execution**:
   - Run `gradle :app:testDebugUnitTest` to ensure all tests pass.
   - Run `compile_applet` to confirm a clean build.
