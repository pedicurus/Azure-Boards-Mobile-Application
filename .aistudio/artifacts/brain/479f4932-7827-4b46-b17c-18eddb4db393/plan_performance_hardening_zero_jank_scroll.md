# Implementation Plan: Performance Hardening (Task 1 — Zero-Jank Scroll & Memory Footprint Optimization)

## Overview
Reduce heap memory pressure and eliminate per-frame allocations during work item list scrolling by converting eager derived string properties on work item models into on-demand/lazy properties, memoizing work-item-type state lookups in the hierarchy delegate, and hoisting fallback state list filtering outside the `LazyColumn` item loop.

## Key Decisions

### Decision 1: On-Demand & Lazy Derived Fields on Work Item Model
- **Decision**: Convert eager `@Transient` string-normalization fields on the work item data model into computed getters and lazy properties:
  - Change `descriptionLower` to a computed getter (`get() = description?.lowercase().orEmpty()`) so large HTML/markdown descriptions are not duplicated and lowercased on the heap at construction time for every loaded item.
  - Convert `normAreaPathLower`, `normIterationPathLower`, and `tagsLower` to on-demand/lazy properties (`by lazy(LazyThreadSafetyMode.NONE)` or computed `get()`) so they are only materialized when facet filtering or search inspects them.
- **Rationale**: Constructing thousands of work items during initial cache hydration or background streaming previously allocated duplicate lowercase copies of every description, area path, iteration path, and tag list upfront, inflating heap usage and GC pause times.

### Decision 2: Concurrent Cache for Type-to-States Resolution
- **Decision**: Add `statesForTypeCache = ConcurrentHashMap<String, List<String>>()` in the hierarchy delegate to memoize `getStatesForType(project, workItemType, currentState)` using the normalized key `"${project.orEmpty().lowercase()}|${workItemType.trim().lowercase()}|${currentState.orEmpty().trim().lowercase()}"`, and automatically invalidate the cache whenever `processDelegate.projectProcessMap` updates or `reset()` is invoked.
- **Rationale**: Every visible card in the list queries `getStatesForWorkItem(item)` during composition. Without memoization, each card repeatedly scans the process map or iterates over all loaded work items, causing O(N) work per visible card during scroll.

### Decision 3: Hoisted Fallback State Filtering in Work Items List
- **Decision**: Precompute `cleanFallbackStates` once above the `LazyColumn` via `remember(availableStates) { availableStates.filterNot { it.equals("All", ignoreCase = true) } }` and pass `getStatesForWorkItem(item).ifEmpty { cleanFallbackStates }` to each card.
- **Rationale**: Avoids allocating a filtered list on every item composition when fallback states are needed and preserves referential stability for card state options.

## Architecture & Data Flow

```
+-------------------------------------------------------------------+
|                        WorkItem Data Model                        |
|  - descriptionLower          -> computed getter (zero heap retention)
|  - normAreaPathLower         -> lazy / computed on demand         |
|  - normIterationPathLower    -> lazy / computed on demand         |
|  - tagsLower                 -> lazy / computed on demand         |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                     WorkItemHierarchyDelegate                     |
|  - statesForTypeCache: ConcurrentHashMap<String, List<String>>    |
|  - Key: "project|workItemType|currentState" (lowercased/trimmed)  |
|  - Invalidated on: processDelegate.projectProcessMap change       |
|                    or hierarchyDelegate.reset()                   |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                         WorkItemsScreen                           |
|  - Hoisted: cleanFallbackStates = remember(availableStates)       |
|  - LazyColumn items:                                              |
|      availableStates = getStatesForWorkItem(item)                 |
|                        .ifEmpty { cleanFallbackStates }           |
+-------------------------------------------------------------------+
```

## Verification Plan
- Run the unit test suite to verify work item model property behavior, hierarchy state resolution caching, and cache invalidation when process maps change or reset is called.
- Compile the application to confirm zero build or serialization issues with Moshi codegen.
