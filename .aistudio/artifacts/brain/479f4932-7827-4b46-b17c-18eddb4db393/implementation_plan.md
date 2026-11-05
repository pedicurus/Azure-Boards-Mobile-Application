# Reliable `@Mentions` Discovery Across All Work Item Types & Custom States

## Goal
Ensure the **Notifications → Mentioned** tab reliably discovers and loads work items where the user is `@mentioned` right from initial sync—without requiring the user to first filter by that item's **Type** and **State** (e.g., `Ready to Review`) on the Home page.

---

## Why Selecting Type & State on the Home Page Loaded the Missed Mention

1. **Startup Hydration Only Loads Standard `InProgress` and `Proposed` States (`AzureWorkItemRepository.kt`)**
   - On initial load, `fetchWorkItems` runs two prioritized WIQL queries for standard active states (`Active`, `In Progress`, `Committed`, `Doing`) and proposed states (`New`, `Proposed`, `To Do`, `Open`, `Approved`), hydrating only the first **60 items** (`INITIAL_FAST_HYDRATE_LIMIT = 60`).
   - Work items in custom workflow states (such as **`Ready to Review`** on `#10395`) do not match either startup query and are placed at the back of `unloadedOtherIdsByOrg`.

2. **Selecting Type & State on Home Runs a Targeted Server Query (`WorkItemStreamDelegate.kt`)**
   - When you selected the item's **Type** and **State** on the Home screen, `onStateFilterSelected` triggered `fetchNextWorkItemBatch` with a targeted WIQL query (`WHERE [System.State] = 'Ready to Review' AND [System.WorkItemType] = '...'`).
   - That immediately hydrated the work item into `_allWorkItems` (`knownWorkItems`), allowing the notifications sync / detail view to inspect its comments and detect your `@mention`.

3. **Why `syncOrganizationNotifications` Missed the Item Before Home Filtering (`AzureNotificationsRepository.kt`)**
   - **Silent WIQL Failure on `CONTAINS WORDS` with Multi-Word / GUID Tokens:** Line 513 queries `[System.History] CONTAINS WORDS '$cleanToken'`. In Azure DevOps WIQL, `CONTAINS WORDS` only accepts a single word and returns `HTTP 400 Bad Request` when given a full display name with spaces (e.g., `"First Last"`) or a hyphenated GUID. Because it is wrapped in `runCatching`, the query silently fails and returns 0 IDs.
   - **Missing `@RecentMentions` WIQL Macro:** Azure Boards supports the native `@RecentMentions` macro (`SELECT [System.Id] FROM WorkItems WHERE [System.Id] IN (@RecentMentions)`), which directly returns work items where the authenticated user was recently `@mentioned` regardless of state or type, but `syncOrganizationNotifications` does not currently query it.
   - **31-Day Fallback Query Includes Items With Zero Comments:** Line 524 queries `WHERE [System.ChangedDate] >= @Today - 31` across the entire organization without filtering by `[System.CommentCount] > 0`. In an active organization, 60+ commentless items modified recently saturate the `.take(60)` hydration cap (`missingMentionIds`) and `.take(75)` comment-fetch cap (`candidateItemsList`), pushing commented items in custom states out of the scan window.

---

## Proposed Changes

### 1. Query `@RecentMentions` & Fix `[System.History]` WIQL Queries (`AzureNotificationsRepository.kt`)
- **Add Native `@RecentMentions` WIQL Query:**
  - Execute `SELECT [System.Id] FROM WorkItems WHERE [System.Id] IN (@RecentMentions) AND [System.State] <> 'Removed'` first so Azure Boards directly returns work items where `@Me` was mentioned, regardless of their workflow state or type.
- **Fix `[System.History]` Token Queries:**
  - Use `[System.History] CONTAINS '$cleanToken'` for phrases, display names, GUIDs, and `@` tokens (and only use `CONTAINS WORDS` for single-word alphanumeric tokens without spaces or hyphens).
  - Add `AND [System.State] <> 'Removed'` to history queries.

### 2. Filter Fallback Candidates by `[System.CommentCount] > 0` (`AzureNotificationsRepository.kt`)
- Update the recent-activity fallback WIQL query to require `[System.CommentCount] > 0`:
  ```sql
  SELECT [System.Id] FROM WorkItems
  WHERE [System.ChangedDate] >= @Today - 45
    AND [System.State] <> 'Removed'
    AND [System.CommentCount] > 0
  ORDER BY [System.ChangedDate] DESC
  ```
  (with a safe fallback to the unfiltered query if a legacy on-prem process template does not expose `System.CommentCount`).
- Parse `System.CommentCount` into `WorkItem` (or check hydrated fields) so `candidateItemsList` prioritizes work items that actually have comments (`commentCount > 0`) and items returned by `@RecentMentions` / `[System.History]` before generic `knownWorkItems`.

### 3. Preserve Priority Ordering in `candidateWorkItemIds` & Increase Hydration Coverage (`AzureNotificationsRepository.kt`)
- Keep direct mention hits (`@RecentMentions` + `[System.History]` matches) in a high-priority set separate from generic recent-changed items so they are **always hydrated first** and **always placed at the front of `candidateItemsList`** ahead of the 75-item comment-fetch cap.

### 4. Unit & Robolectric Verification (`NotificationsFilteringTest.kt`)
- Add unit tests verifying that:
  - `@RecentMentions` and `[System.History] CONTAINS` queries discover mentioned work items in custom states (e.g., `Ready to Review`) even when `knownWorkItems` is empty and no items are assigned to `@Me`.
  - Work items with comments are prioritized over commentless work items during notification sync.
