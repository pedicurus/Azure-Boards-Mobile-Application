# Notifications: 10 Recent Items & Deep Mention Discovery

Deliver high-accuracy discovery of discussion `@mentions` and work item assignments across Azure DevOps, ensuring at least 10 mentioned items and 10 assigned items are retrieved and strictly displaying only the 10 most recent items on each tab of the Notifications page.

## User Review & Critical Decisions

> [!IMPORTANT]
> The user confirmed the following decisions via the planning interview:
> - **Mention Search Strategy**: Use a hybrid approach combining direct Azure DevOps WIQL `[System.History]` queries with deep discussion comment scanning to capture all work items where the user is mentioned.
> - **Search Depth**: If fewer than 10 mentions are initially discovered, automatically query deeper discussion history until at least 10 mentions are retrieved (or all available history is exhausted).
> - **Display Limit**: Strictly cap both the **Assigned** and **Mentioned** sections to display exactly the **10 most recent** notifications.

---

## 1. Overview & Core Concept

- **What It Does**: Enhances the notifications engine so that user `@mentions` in work item discussions are reliably detected across all projects and work items in the active Azure DevOps organization, guaranteeing that at least 10 mentioned items are populated if they exist in history, and strictly limiting the list on the Notifications screen to the 10 most recent items for both Assigned and Mentioned tabs.
- **Root Cause of Previous Single-Item Mention**: The previous implementation only scanned the 10 most recently changed work items locally for discussion comments, missing work items where mentions occurred earlier or on items not in that tiny 10-item slice. Furthermore, it did not execute targeted WIQL discussion history queries (`[System.History] CONTAINS ...`) on the server.
- **Key Value**: Users will reliably see their recent mentions and assignments without gaps, while the Notifications page maintains a fast, uncluttered layout showing strictly the 10 most recent items.

---

## 2. User Experience & Visual Design

- **Notifications Page (Strict 10-Item Cap)**:
  - **Assigned Tab**: Shows up to the 10 most recent work items assigned to the user, ordered newest first by changed/created timestamp.
  - **Mentioned Tab**: Shows up to the 10 most recent discussion comments where the user is `@mentioned`, ordered newest first.
  - **Tab Badge Counts**: Reflect the strict top-10 window (e.g. showing `10` or the exact count if fewer than 10 exist).
- **Empty & Loading States**:
  - While deep-querying discussion mentions, the refresh indicator shows smooth progress.
  - If no mentions or assignments exist after deep scanning, the clean empty state informs the user with a "Check Now" button.
- **Visual Polish & Hierarchy**:
  - Preserves the clean Material 3 design, official Azure work item type color bars, state badges, `@mention` author avatars, and truncated comment quotes.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Server-Side WIQL History Query for Mentions**
  - *Chosen Approach*: Query Azure DevOps WIQL with `[System.History] CONTAINS '@Name'` / `[System.History] CONTAINS 'User'` in addition to scanning recent items.
  - *Why*: WIQL `System.History` search is executed on the Azure DevOps search index server-side. This instantly identifies work items where the user was mentioned regardless of how many other work items have been updated since.
  - *Fallback*: If WIQL history search returns few results, the scanner iterates through additional recently changed work items in batches of 20 until at least 10 mentions are found or 100 candidate items have been checked.
- **Decision 2: Enhanced Mention Pattern Matching**
  - *Chosen Approach*: Parse both raw comment HTML (which contains Azure DevOps mention tags like `data-vss-mention="version:2.0,..."` and `@DisplayName`) and clean plain text, matching across display name, unique name, email address, email prefix, and first name.
  - *Why*: Azure DevOps web and mobile clients format mentions as rich HTML elements; scanning both ensures no mention is missed.
- **Decision 3: Strict 10-Item Display Cap in UI & ViewModel**
  - *Chosen Approach*: Apply `.take(10)` in both ViewModel StateFlows and repository outputs for `assignedNotifications` and `mentionedNotifications`.
  - *Why*: Meets the user's explicit request to strictly show only 10 recent items per section.

---

## 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────┐
│                   Azure DevOps Server                  │
│  - WIQL [System.AssignedTo] = @Me                      │
│  - WIQL [System.History] CONTAINS '@User'              │
│  - Work Item Comments API (_apis/wit/workItems/.../comments) │
└──────────────────────────┬─────────────────────────────┘
                           │ Network / REST
                           ▼
┌────────────────────────────────────────────────────────┐
│               AzureDevOpsRepository                    │
│  1. Execute WIQL for Assigned & Mentioned Work Items   │
│  2. Hydrate missing work item metadata in batches      │
│  3. Fetch comments & inspect HTML for @mention tags    │
│  4. If mentions < 10, expand candidate scan window     │
│  5. Save cached notification feed to local store       │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│               AzureDevOpsViewModel                     │
│  - assignedNotifications: StateFlow<List> (.take(10))   │
│  - mentionedNotifications: StateFlow<List> (.take(10))  │
│  - unreadNotificationsCount (computed from top 10)     │
└──────────────────────────┬─────────────────────────────┘
                           │ StateFlow Collection
                           ▼
┌────────────────────────────────────────────────────────┐
│         NotificationsScreen & WorkItemsScreen          │
│  - Assigned Tab: strictly 10 items                     │
│  - Mentioned Tab: strictly 10 items                    │
│  - Tap card -> open Work Item Detail sheet             │
└────────────────────────────────────────────────────────┘
```

### Proposed Changes Across Modules

1. **`AzureDevOpsRepository.kt`**:
   - In `syncOrganizationNotifications`:
     - Add WIQL queries to search work items by history:
       `SELECT [System.Id] FROM WorkItems WHERE [System.History] CONTAINS '$userToken' ORDER BY [System.ChangedDate] DESC`
     - Combine IDs found via history search with candidate recently changed work items.
     - Automatically query comments for candidate items in parallel until at least 10 distinct mentions are found (or up to 100 candidate items max).
     - Support Azure DevOps `data-vss-mention` HTML attribute in `commentMentionsIdentity`.
2. **`AzureDevOpsViewModel.kt`**:
   - Ensure `assignedNotifications` and `mentionedNotifications` flows emit at most 10 items:
     `state.notifications.filter { it.category == NotificationCategory.ASSIGNED }.take(10)`
     `state.notifications.filter { it.category == NotificationCategory.MENTIONED }.take(10)`
3. **`NotificationsScreen.kt`**:
   - Ensure tab counts and rendered list adhere strictly to the 10-item cap.

---

## 5. Verification Plan

- Run `compile_applet` to ensure successful compilation.
- Verify that sync logic fetches multiple batches of comments and WIQL history when fewer than 10 mentions exist.
- Verify that both tabs on the Notifications screen display strictly up to 10 items.
