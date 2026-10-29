# Iterative Background Streaming, On-Device Persistence & Project-Wide Search Plan

## 1. Root Causes of Current Lags & Missing Search Results

### Why You Still Feel Lags
1. **Large Blocking Initial Hydration**: Currently, `connect()` keeps `isLoading = true` while running multiple WIQL queries, hydrating up to 400 work items + all missing child items at once, and fetching process/category metadata before rendering the main list.
2. **Scroll-Dependent Loading**: Remaining `Proposed` and `Completed/Resolved/Removed` items are held in `unloadedProposedIdsByOrg` and `unloadedOtherIdsByOrg` and only fetched when you manually scroll to the bottom of the list.
3. **No On-Device Cache**: Work items and project process/category metadata are stored only in memory. Every time you open the app or switch organizations, the app re-downloads everything from Azure DevOps from scratch.

### Why Search Misses Existing Azure Items
1. **Active Filter Chips Hide Search Results**: By default, the Home screen automatically selects the highest-level work item type chip (e.g., `Epic` or `Feature`). In `AzureDevOpsViewModel.filteredWorkItems`, `matchesType`, `matchesState`, and `matchesAssignee` are still enforced during search—meaning if you search for a `Task`, `Bug`, or `User Story` by title or ID while `Epic` is selected, the item is fetched from the server and immediately hidden by the `matchesType` filter.
2. **WIQL LongText Search Syntax**: Azure DevOps WIQL requires `CONTAINS WORDS` rather than `CONTAINS` for HTML `LongText` fields like `[System.Description]`, and direct numeric ID lookups (`getWorkItems(ids = ...)`) should run alongside WIQL text queries so ID searches never fail.

---

## 2. Proposed Architecture & Solutions

### A. On-Device Persistent Cache (Instant Launch & Zero Initial Wait)
- **Local Storage Layer (`WorkItemLocalCache` / Room + JSON metadata cache)**:
  - Persist all fetched `WorkItem` records, project lists, and project process/category metadata (`ProjectProcessMetadata`, `ProjectCategoryMetadata`) per organization on the device.
  - **Instant Startup (`0ms` Blocking Load)**: When opening the app or switching organizations, immediately hydrate `_workItems`, `_projectsByOrg`, `_projectProcessMap`, and `_projectCategoryMap` from local device storage on `Dispatchers.IO`.
  - If cached items exist on device, `isLoading` is immediately `false` so you can browse, filter, and search your cached items right away without waiting for network calls.
  - Whenever an item is created, edited, reordered, or newly synced, the local device cache is updated automatically.

### B. Iterative, Incremental Background Streaming (No Scroll Required)
- **Continuous Small-Batch Streaming Pipeline**:
  - Instead of waiting for a giant batch or requiring you to scroll, the app will run a non-blocking background sync coroutine (`startIncrementalSyncJob`) that streams items iteratively in small batches (**40–50 items per batch**):
    1. **Delta Check First (When Cache Exists)**: Query items changed since the last cached `[System.ChangedDate]` watermark (`WHERE [System.ChangedDate] >= '...'`) plus reconcile current work item IDs in the project/organization so any newly created or externally updated items are synced first in a single lightweight pass.
    2. **Priority Wave 1 (`InProgress` + Active Hierarchy)**: Stream all `InProgress` items in small batches of 40–50 items, emitting each batch immediately to the UI `StateFlow` and saving to device storage.
    3. **Priority Wave 2 (`Proposed` Items)**: Automatically continue streaming `Proposed` items in background batches of 50 items (with a short `yield()` / `50ms` breather between batches so the UI thread and animations stay at a locked 60fps) **even if the user does not scroll**.
    4. **Priority Wave 3 (`Completed` / `Resolved` / `Removed` Items)**: First hydrate the 5 most recently changed items per type and state for immediate chip browsing, then continue trickling any remaining uncached items into the local device cache in the background until 100% of the project's work items are saved on the device.
- **Subtle Non-Blocking Sync Status Indicator**:
  - Replace full-screen blocking spinners (when cached data is already present) with a sleek, non-intrusive status pill in the header showing live incremental sync progress (e.g., *"Syncing 120 / 340..."* -> *"Saved on device"*).

### C. Reliable Project-Wide Search (Local Full-Cache + Resilient Server WIQL)
- **Search All Types, States, and Assignees in Current Project**:
  - When the search bar has a query (`searchQuery.isNotBlank()`), `filteredWorkItems` will **search across all work item types, states, and assignees within the currently selected project**, bypassing the active Type, State, and Assignee chip filters so matching items of any type or state are always shown.
  - Search result cards will clearly display their **Work Item Type badge** and **State badge** so you can immediately distinguish Epics, Features, User Stories, Tasks, and Bugs in the search results.
- **Two-Tier Search Execution**:
  1. **Instant On-Device Search (0ms latency)**: Immediately matches against all locally cached items in the current project across **ID, Title, Description, Tags, Assigned To, Work Item Type, and State**, ranked by relevance (exact ID match -> title starts-with -> title contains -> tag/description match).
  2. **Parallel Server-Side Search Fallback (`searchWorkItemsOnServer`)**:
     - Runs in parallel (debounced at `250ms`) so even if an item hasn't finished streaming into the local cache yet, it is fetched live from Azure DevOps.
     - If the query is numeric (e.g., `1234` or `#1234`), directly calls `getWorkItems(ids = "1234")` in parallel with WIQL so exact ID lookup is guaranteed.
     - Uses resilient WIQL clauses scoped to the current project:
       - `[System.Title] CONTAINS '...'`
       - `[System.Tags] CONTAINS '...'`
       - `[System.Description] CONTAINS WORDS '...'` (with automatic fallback to Title/Tags query if the Azure DevOps server instance does not index `Description` for full-text search).
     - Any newly discovered items from server search are immediately merged into `_workItems` and persisted into the on-device cache.

---

## 3. Files to Create & Modify

1. **`/app/src/main/java/com/example/data/local/WorkItemLocalStore.kt` (New)**:
   - Fast, thread-safe on-device persistence per organization for `WorkItem` lists, project lists, process/category metadata, and per-organization sync watermarks (`lastSyncedChangedDate`), using Moshi JSON serialization on `Dispatchers.IO` with atomic file writes.
2. **`/app/src/main/java/com/example/data/repository/AzureDevOpsRepository.kt`**:
   - Add `streamWorkItemsIncrementally(...)` / iterative batch hydration (`BATCH_SIZE = 50`) that yields between batches and invokes an `onBatchLoaded` callback after every batch.
   - Add delta sync query support (`fetchUpdatedWorkItemsSince(...)`) so subsequent app launches only download new/modified items while keeping existing cached items on device.
   - Upgrade `searchWorkItemsOnServer(...)` to scope to the selected project, support direct numeric ID lookup, and use `CONTAINS WORDS` with safe fallback so WIQL never fails on description queries.
3. **`/app/src/main/java/com/example/ui/AzureDevOpsViewModel.kt`**:
   - Load cached work items and metadata from `WorkItemLocalStore` immediately on startup and organization switch (`isLoading = false` as soon as cache is displayed).
   - Run the iterative background sync loop automatically without requiring user scroll, persisting each batch to `WorkItemLocalStore` and updating a `syncStatusText` / `isBackgroundSyncing` state.
   - Update `filteredWorkItems` so active search queries (`cleanQuery.isNotEmpty()`) search across **all types, states, and assignees in the current project**, ranked by match relevance.
4. **`/app/src/main/java/com/example/ui/screens/WorkItemListScreen.kt` & `/app/src/main/java/com/example/MainActivity.kt`**:
   - Display a subtle, non-blocking background sync badge in the header when incremental background loading or delta sync is active.
   - When `searchQuery.isNotBlank()`, show a contextual banner/subtitle (*"Showing matches across all types & states in [Project]"*) so it is clear why items from all types/states appear.
