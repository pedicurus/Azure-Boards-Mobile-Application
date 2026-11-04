# Implementation Plan: Performance Hardening (Task 2 — Search Debounce & Streaming JSON Persistence)

## Overview
Eliminate redundant multi-level work item filtering and sorting on rapid keystrokes by debouncing the search query pipeline (while keeping clear/reset instantaneous), and eliminate large intermediate `String` heap allocations during local cache reads and writes by streaming Moshi JSON serialization and deserialization directly through Okio buffered sinks and sources.

## User Review & Critical Decisions

> [!IMPORTANT]
> All requirements for Task 2 are fully specified with no ambiguity:
> - **Confirmed Decision 1 (Adaptive Search Debounce)**: `_searchQuery` remains immediate for UI text field binding, while `filteredWorkItems` consumes `debouncedSearchQuery` (`0L` delay when blank, `180L` when non-blank).
> - **Confirmed Decision 2 (Streaming Moshi JSON Persistence)**: All three local cache domains (snapshots, metadata, notifications) stream directly to/from disk via Okio `sink().buffer()` and `source().buffer()` without intermediate `String` allocations.

## 1. Overview & Core Concept
- **What It Does**: Optimizes search responsiveness and disk cache memory efficiency across the work item filtering and persistence layers without altering any UI layouts or unrelated business logic.
- **Target Audience / Persona**: Users searching across large Azure DevOps backlogs and switching or syncing organizations with thousands of cached work items.
- **Key Value**: Prevents CPU spikes and frame drops while typing search queries and eliminates multi-megabyte JSON string allocations during background snapshot persistence and startup cache hydration.

## 2. User Experience & Visual Design
- **Key User Flows**:
  - **Typing a Search Query**: The search bar updates immediately as characters are typed (`searchQuery` StateFlow), while background list filtering and ranking wait `180ms` for typing pauses before recomputing `filteredWorkItems`.
  - **Clearing the Search Query**: Tapping clear or deleting the query to blank (`query.isBlank()`) bypasses the debounce window (`0ms` delay) so the full unfiltered board restores instantaneously.
  - **Background Cache Persistence & Startup Hydration**: Snapshots, process metadata, and notification states stream directly between Moshi adapters and file descriptors with atomic `.tmp`-to-target file replacement.
- **Visual Identity & Theme**: Unchanged per task constraints (strictly no UI layout or visual modifications).

## 3. Key Product Decisions & Trade-Offs
- **Decision 1: Conditional Debounce (`0ms` on blank, `180ms` on non-blank) on Search Pipeline**
  - *Chosen Approach*: Introduce `private val debouncedSearchQuery = _searchQuery.debounce { query -> if (query.isBlank()) 0L else 180L }.distinctUntilChanged()` (with `@OptIn(FlowPreview::class)`) and wire `debouncedSearchQuery` into `filteredWorkItems` (and `totalWorkItemsCount` so the count badge stays synchronized with the displayed filtered items).
  - *Why*: Multi-criteria filtering and comparator ranking across thousands of work items is CPU-intensive on every keystroke; a `180ms` debounce coalesces rapid typing into a single evaluation while `0ms` on blank preserves instant clear feedback.
  - *Alternatives Considered*: Debouncing `_searchQuery` directly was rejected because it would lag the `TextField` cursor and input characters in Compose.
- **Decision 2: Okio Buffered Streaming for Moshi Persistence**
  - *Chosen Approach*: Replace `toJson(value)` + `File.writeText(...)` and `File.readText(...)` + `fromJson(String)` across `saveSnapshot`/`loadSnapshotSync`, `saveMetadata`/`loadMetadataSync`, and `saveNotificationsState`/`loadNotificationsStateSync` with `file.sink().buffer().use { sink -> adapter.toJson(sink, value) }` and `file.source().buffer().use { source -> adapter.fromJson(source) }`. When `tempFile.renameTo(targetFile)` returns `false`, stream directly to `targetFile.sink().buffer()` before deleting `tempFile`.
  - *Why*: Serializing thousands of work items into a single contiguous `String` allocates several megabytes on the heap per save/load cycle, risking `OutOfMemoryError` or GC thrashing during progressive background sync.

## 4. Technical Architecture & Data Strategy

```
┌──────────────────────────────────────────────────────────────────────┐
│                        WorkItemFilterDelegate                        │
│                                                                      │
│  _searchQuery (MutableStateFlow<String>) ──► searchQuery (UI Input)  │
│        │                                                             │
│        ▼                                                             │
│  debouncedSearchQuery                                                │
│  .debounce { if (it.isBlank()) 0L else 180L }                        │
│  .distinctUntilChanged()                                             │
│        │                                                             │
│        ▼                                                             │
│  filteredWorkItems (StateFlow<List<WorkItem>>)                       │
│  (Multi-field search + relevance ranking on Dispatchers.Default)     │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│                          WorkItemLocalStore                          │
│                                                                      │
│  Write Path (saveSnapshot / saveMetadata / saveNotificationsState):  │
│    Model ──► Moshi Adapter ──► tempFile.sink().buffer()              │
│                                      │                               │
│                                      ▼                               │
│                        Atomic renameTo(targetFile)                   │
│                        (fallback: targetFile.sink().buffer())        │
│                                                                      │
│  Read Path (loadSnapshotSync / loadMetadataSync /                    │
│             loadNotificationsStateSync):                             │
│    targetFile.source().buffer() ──► Moshi Adapter ──► In-Memory Map  │
└──────────────────────────────────────────────────────────────────────┘
```

## Verification Plan
- Add and run unit tests verifying `WorkItemLocalStore` streaming round-trip persistence for snapshots, metadata, and notifications, as well as `WorkItemFilterDelegate` search debounce and instant blank reset behavior.
- Update `README.md` to reflect the streaming JSON persistence and debounced search pipeline optimizations.
- Run `compile_applet` and `gradle :app:testDebugUnitTest` to confirm zero build or test regressions.
