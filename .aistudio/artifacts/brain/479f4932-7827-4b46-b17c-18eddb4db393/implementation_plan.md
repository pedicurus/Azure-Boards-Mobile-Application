# Azure Boards High-Performance Pipeline & UI Responsiveness Refactoring

Eliminates UI thread blocking, frame drops, and memory churn when loading thousands of work items, switching work item types or states, and filtering by Assignee, Area, or Iteration by moving all data pipelines off the Main thread, sharing pre-filtered intermediate flows, pre-normalizing hierarchy paths, throttling background sync emissions to 250ms, and preventing unnecessary Compose recompositions.

## User Review & Critical Decisions

> [!IMPORTANT]
> All architectural and throttling preferences have been confirmed in Phase 1:

- **Confirmed Decision 1 (Optimization Scope)**: Execute the **Full 5-Step Coroutine Refactoring Plan** from the performance review **plus the 4 additional bottlenecks** identified in the codebase (pre-normalized hierarchy paths, periodic/debounced disk snapshot persistence, Compose recomposition isolation for `allWorkItems`, and non-blocking cache reads).
- **Confirmed Decision 2 (Background Sync Emission Cadence)**: **Throttle UI list updates to every 250ms** during background streaming so incoming work item batches merge smoothly without flooding downstream combiners or interrupting 60fps scrolling.

---

## 1. Overview & Core Concept

- **What It Does**: Re-architects the reactive state and caching pipeline so that zero list filtering, string splitting, hierarchy expansion, sorting, or JSON serialization occurs on the Android Main (UI) thread.
- **Target Audience / Persona**: Teams and project managers working with large Azure DevOps organizations (hundreds to thousands of work items across multiple projects, nested Areas, Iterations, and custom process states).
- **Key Value**: Instantaneous (sub-16ms) response when tapping Type or State chips, opening or selecting Area/Iteration trees, typing in the search bar, or scrolling while background sync is active.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Zero-Jank Startup & Organization Switch**: Cached work items and metadata restore asynchronously off the UI thread while keeping the first frame responsive.
  2. **Instant Chip & Hierarchy Filtering**: Tapping any Work Item Type, State chip, Assignee, Area, or Iteration filter immediately updates the visual selection state on the UI thread while the background worker pool (`Dispatchers.Default`) computes the filtered list from pre-indexed fields in a single pass.
  3. **Smooth 60fps Scrolling During Background Sync**: While `startIterativeBackgroundStream` fetches 50-item batches in the background, UI list updates are coalesced and throttled to a **250ms cadence**, and full-snapshot JSON disk writes happen periodically in the background rather than after every single batch.
- **Visual Identity & Theme**:
  - Preserves the existing Azure Boards aesthetic (`#FFFFFF` to `#DFE4F1` vertical gradient canvas, `#0078D4` accent blue, `#0D101C` primary typography, `#6E7591` muted labels, and `#DCE1EF` card borders).
- **Interactive Feedback & Motion**:
  - Filter chip transitions, expandable Area/Iteration tree nodes, and bottom sheets animate smoothly at 60fps without frame skips or main-thread stalls.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Shared Intermediate Flows + `flowOn(Dispatchers.Default)` + `conflate()` + `distinctUntilChanged()`**
  - *Chosen Approach*: Introduce shared intermediate flows (`activeOrgAndProjectFlow`, `projectScopedWorkItems`, and `projectAndTypeScopedWorkItems`) running on `Dispatchers.Default` with `.conflate()` and `.distinctUntilChanged()`. All downstream flows (`filteredWorkItems`, `hasMoreItems`, `availableTypes`, `availableStates`, `projectAssignees`, `projectFrequentTags`, `projectAreas`, `projectIterations`, `remainingItemsCount`, `completedItemsCount`, `selectedWorkItemChildren`) derive from these shared flows on `Dispatchers.Default`.
  - *Why*: Previously, 12+ separate `combine` blocks each scanned the entire `_allWorkItems` list independently on `Dispatchers.Main`. Sharing pre-filtered project/type subsets on `Dispatchers.Default` cuts redundant list iterations by ~75% and completely frees the UI thread.
  - *Alternatives Considered*: Only adding `.flowOn(Dispatchers.Default)` to `filteredWorkItems`—rejected because the remaining 11 flows would still scan `_allWorkItems` on the Main thread on every batch emission.

- **Decision 2: Pre-Normalized & Pre-Lowercased Lookup Fields on `WorkItem`**
  - *Chosen Approach*: Pre-compute normalized, lowercased transient fields (`normAreaPathLower`, `normIterationPathLower`, `normTeamProjectLower`, `normTypeLower`, `normStateLower`, `normAssigneeLower`, `titleLower`) once when a `WorkItem` is constructed or hydrated, and replace `.groupBy` string allocation in "All" States mode with a single-pass top-K accumulator per `(workItemType, state)` pair.
  - *Why*: Previously, `matchesClassificationHierarchy()` split and re-joined `areaPath` and `iterationPath` strings on every item during every filter evaluation, and `.lowercase().trim()` was called tens of thousands of times per second. Pre-computing these once on background hydration turns filtering into O(1) reference/prefix comparisons with zero allocations.
  - *Alternatives Considered*: Computing normalized strings inside `filteredWorkItems`—rejected because it still allocates temporary strings and lists on every filter change.

- **Decision 3: 250ms UI Throttling (`sample`/`conflate`) & Coalesced Snapshot Persistence During Background Sync**
  - *Chosen Approach*: Throttle `_allWorkItems` emissions into UI-facing combiners to at most once every **250ms** while background streaming is active (while keeping user-initiated edits and filter clicks instantaneous), and persist the JSON snapshot to disk every 5 batches (or when the stream finishes/cancels) on `Dispatchers.IO` instead of after every single 50-item batch.
  - *Why*: Serializing 1,000+ `WorkItem` objects to JSON after every 50-item batch caused O(N²) CPU and garbage collection spikes during scrolling.
  - *Alternatives Considered*: Waiting until 100% of background items finish syncing before updating the UI—rejected because users prefer seeing progressive items appear smoothly every 250ms.

- **Decision 4: Isolate `allWorkItems` Recomposition Scope in Compose**
  - *Chosen Approach*: Expose a dedicated `totalWorkItemsCount: StateFlow<Int>` (with `.distinctUntilChanged()`) for the Settings dialog counter and avoid triggering full `WorkItemsScreen` recompositions on every `_allWorkItems` emission when `filteredWorkItems` has not changed.
  - *Why*: Collecting the raw `allWorkItems` list at the root composable forced Compose to re-evaluate `WorkItemsScreen` on every background batch even when the visible filtered list was unchanged.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Reactive Data Flow Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                     AzureDevOpsRepository (Dispatchers.IO)                   │
│  • Hydrates RawWorkItem -> WorkItem with pre-normalized lowercase fields     │
│  • Coalesced/periodic snapshot persistence (every 5 batches & on completion) │
│  • In-memory cached project lists (zero synchronous disk reads in combiners) │
└──────────────────────────────────────┬───────────────────────────────────────┘
                                       │ Batched & 250ms-throttled during sync
                                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                  Shared Base Flows (Dispatchers.Default)                     │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │ _allWorkItems (conflated / 250ms throttled during background stream)   │  │
│  └───────────────────────────────────┬────────────────────────────────────┘  │
│                                      ▼                                       │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │ projectScopedWorkItems (filtered once by resolved effective project)   │  │
│  │ + distinctUntilChanged() + flowOn(Dispatchers.Default)                 │  │
│  └───────────────┬────────────────────────────────────────┬───────────────┘  │
│                  │                                        │                  │
│                  ▼                                        ▼                  │
│  ┌───────────────────────────────────────┐  ┌─────────────────────────────┐  │
│  │ projectAndTypeScopedWorkItems         │  │ Project Metadata Flows      │  │
│  │ (filtered by workItemType)            │  │ • availableTypes            │  │
│  │ • availableStates                     │  │ • projectAssignees          │  │
│  │ • hasMoreItems                        │  │ • projectFrequentTags       │  │
│  │ • filteredWorkItems (O(1) path check, │  │ • projectAreas              │  │
│  │   top-5 terminal bucket accumulator)  │  │ • projectIterations         │  │
│  └───────────────────┬───────────────────┘  └──────────────┬──────────────┘  │
└──────────────────────┼─────────────────────────────────────┼─────────────────┘
                       │ StateFlow (distinct emissions only) │
                       ▼                                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose UI (Main Thread)                      │
│  • WorkItemsScreen observes filteredWorkItems & totalWorkItemsCount          │
│  • Detail & Create sheets receive memoized project-scoped Area/Iteration     │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Data Model & Pre-Computed Indexing Strategy

- **Pre-Computed Transient Fields on `WorkItem`**:
  - Add `@Transient` pre-computed fields on `WorkItem` (`normTeamProjectLower`, `normTypeLower`, `normStateLower`, `normAssigneeLower`, `normAreaPath`, `normAreaPathLower`, `normAreaPrefixLower`, `normIterationPath`, `normIterationPathLower`, `normIterationPrefixLower`, `titleLower`, `idString`) initialized in the `WorkItem` class body.
  - Because `@Transient` properties are excluded from Moshi JSON serialization and computed once upon object instantiation on background threads, `matchesClassificationHierarchy(item, filterNormLower, filterPrefixLower)` becomes:
    `item.normAreaPathLower == filterNormLower || item.normAreaPathLower.startsWith(filterPrefixLower)` — **zero string allocations, zero regex/splitting, O(1) execution**.
- **Top-K Bucket Accumulator in `"All"` States Mode**:
  - Replace `.groupBy { "${it.workItemType.trim().lowercase()}|${it.state.trim().lowercase()}" }.values.flatMap { group.sortedWith(...).take(5) }` with a lightweight per-bucket bounded list (`RECENT_COMPLETED_LIMIT_PER_TYPE_AND_STATE = 5`) keyed by `item.normTypeLower to item.normStateLower` so terminal items are never sorted in full.

### Interactive Component & State Mapping

- **Filter Changes (`setTypeFilter`, `setStateFilter`, `setAssigneeFilter`, `setAreaFilter`, `setIterationFilter`, `setProject`)**:
  - Immediately update the corresponding `MutableStateFlow` so filter chips and buttons reflect selection in `<1ms`.
  - Downstream `projectScopedWorkItems` -> `projectAndTypeScopedWorkItems` -> `filteredWorkItems` execute on `Dispatchers.Default` without touching the Main thread.
- **Background Sync Throttling (`startIterativeBackgroundStream`)**:
  - Accumulate incoming batches into `serverSyncedItems` and publish to `_allWorkItems` on a 250ms throttle window, while writing to `WorkItemLocalStore` every 5 batches and once in `finally` on `Dispatchers.IO`.
- **Non-Blocking Cache Restoration (`restoreOrganizationFromLocalCache`)**:
  - Cache `projectsByOrg` in memory inside `AzureDevOpsRepository` so `getCachedProjectsForOrg` never hits `SharedPreferences` or JSON parsing repeatedly inside `combine` blocks, and offload heavy snapshot deserialization to `Dispatchers.IO`.
