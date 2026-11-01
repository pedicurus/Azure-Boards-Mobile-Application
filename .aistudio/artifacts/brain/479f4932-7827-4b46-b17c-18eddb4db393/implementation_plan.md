# Notifications Feature Plan (Assigned & Mentioned)

## 1. Feature Overview & Goals
We will add real-time and periodic notifications for **Work Item Assignments** and **Discussion `@Mentions`**, paired with a dedicated **Notifications Page** accessible via a **Bell icon** right next to the Settings gear in the Home header.

- **Device System Notifications**:
  - Alerts the user on their Android device whenever a work item is newly assigned to them or they are `@mentioned` in a work item's Discussion comments.
  - Tapping a system notification opens the app directly to that Work Item's Detail page and marks the notification as read.
- **Header Bell Icon & Unread Badge**:
  - Placed immediately beside the Settings icon in the top header bar on the Home screen.
  - Displays a vibrant unread count badge when there are unread Assigned or Mentioned notifications.
- **Dedicated Notifications Page (`Assigned` & `Mentioned` Tabs)**:
  - Opens when tapping the Bell icon (with hardware/gesture Back navigation returning to the Home screen).
  - Features two switchable tabs: **Assigned** and **Mentioned**, each displaying its badge count.
  - Includes a **"Mark all as read"** action, pull/button refresh to check for new notifications immediately, and rich cards showing Work Item ID, Type badge, State badge, Project, Title, Actor/Author, timestamp, and comment snippet (for mentions) with `@mention` highlighting.
  - Tapping any notification card marks it as read and navigates straight to that Work Item's Detail view.

---

## 2. User Identity Resolution (Auto-Detect + Settings Override)
To accurately know which Azure DevOps user is "you" for assignments and `@mentions`:
1. **Auto-Detection via Azure DevOps Connection Data API**:
   - Add `GET {organization}/_apis/connectionData` to `AzureDevOpsService` to fetch the authenticated user's `authenticatedUser.providerDisplayName`, `customDisplayName`, `id`, and email/uniqueName from the active PAT.
   - Also support WIQL `@Me` queries (`SELECT [System.Id] FROM WorkItems WHERE [System.AssignedTo] = @Me`) as an authoritative server-side check for items assigned to the PAT owner.
2. **Manual Identity Override in Settings**:
   - In the Settings modal, add a **"Your Identity (For Notifications)"** selector showing the auto-detected user name (with an option to pick/override from known project team members or type a custom display name/email).

---

## 3. Detection & Background / Live Sync Architecture
1. **Assigned Items Detection**:
   - During initial sync, delta sync, background streaming, and periodic background checks, query and compare items assigned to `@Me` (or matching the resolved user identity).
   - Maintain a persisted baseline of known assigned Work Item IDs + last assignment revision timestamps per organization so existing historical assignments on first install populate the **Assigned** tab without spamming tens of device push alerts, while any newly assigned item triggers a system notification and appears as unread.
2. **Discussion `@Mention` Detection**:
   - Inspect recently changed work items (`System.ChangedDate` / `System.CommentCount > 0`) and newly fetched discussion comments.
   - Match `@DisplayName`, `@UniqueName`, or HTML `data-vss-mention` tokens referencing the current user's identity (excluding self-mentions authored by the user themselves, while still allowing self-test if desired or when assigned/mentioned in updates).
   - Store discovered mention notifications in persistent local storage (`WorkItemLocalStore` / SharedPreferences JSON) keyed by `organization + workItemId + commentId`.
3. **Periodic Background Checks & Live Checks**:
   - **Live Checks**: Automatically run lightweight assignment & mention delta checks on app launch, pull-to-refresh, and every periodic in-app sync cycle on `Dispatchers.IO`.
   - **Background Checks**: Register an Android `JobService` (`AzureNotificationJobService`) scheduled via `JobScheduler` (with `NETWORK_TYPE_ANY`) to periodically check for new assignments and mentions in the background and post device notifications via `NotificationManagerCompat`.

---

## 4. Android Permissions & Notification Channels
1. **Manifest Permissions (`AndroidManifest.xml`)**:
   - Declare `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` (requested gracefully at runtime on Android 13+ / API 33+ when notifications are enabled or when opening the Notifications screen).
   - Register `AzureNotificationJobService` with `android:permission="android.permission.BIND_JOB_SERVICE"`.
2. **Notification Channels (`NotificationHelper.kt`)**:
   - Create two distinct Android notification channels (API 26+):
     - **Assigned Work Items** (`azure_boards_assigned_channel`)
     - **Discussion Mentions** (`azure_boards_mentions_channel`)
   - Build deep-link `PendingIntent`s carrying `extra_notification_work_item_id`, `extra_notification_org`, and `extra_notification_id` so tapping a system notification opens `MainActivity` and immediately displays the target Work Item.

---

## 5. Planned File Changes
1. **`app/src/main/AndroidManifest.xml`**:
   - Add `POST_NOTIFICATIONS` permission and register `AzureNotificationJobService`.
2. **`app/src/main/java/com/example/data/model/WorkItemModels.kt`**:
   - Add `AppNotificationItem` (id, organization, workItemId, workItemTitle, workItemType, workItemState, teamProject, category: `ASSIGNED` / `MENTIONED`, actorName, snippet, timestamp, commentId, isRead) and `ConnectionDataResponse` / `AuthenticatedUser` models.
3. **`app/src/main/java/com/example/data/api/AzureDevOpsApi.kt`**:
   - Add `@GET("{organization}/_apis/connectionData")` endpoint.
4. **`app/src/main/java/com/example/data/repository/AzureDevOpsRepository.kt`**:
   - Add user identity auto-detection & caching per organization, manual identity override preference, `@Me` assigned work items query, recent comment `@mention` scanner, and local persistence for notifications and read/notified state.
5. **`app/src/main/java/com/example/notifications/NotificationHelper.kt` & `AzureNotificationJobService.kt`**:
   - Implement Android NotificationChannel setup, system notification builder with deep-link `PendingIntent`, and periodic background `JobService`.
6. **`app/src/main/java/com/example/ui/AzureDevOpsViewModel.kt`**:
   - Expose `notifications`, `assignedNotifications`, `mentionedNotifications`, `unreadNotificationsCount`, `currentUserIdentity`, `manualIdentityOverride`, mark-as-read actions, and deep-link handling when a system notification is tapped.
7. **`app/src/main/java/com/example/ui/screens/NotificationsScreen.kt` & `WorkItemsScreen.kt` & `MainActivity.kt`**:
   - Add the **Bell icon button with unread badge** beside the Settings icon in the Home header.
   - Build the full **Notifications Page** with switchable **Assigned** and **Mentioned** tabs (with badge counts), empty states, mark-all-read button, and item navigation to the Work Item Detail view.
   - Add the identity configuration row in the Settings dialog and runtime `POST_NOTIFICATIONS` permission prompt on Android 13+.
