# Azure Boards Architecture Refactor — Plan Deviations Remediation

Addressing the three architectural deviations identified in the initial refactor: migrating from String routes to Kotlinx Serialization `@Serializable` type-safe navigation, replacing platform `JobScheduler` with an AndroidX `WorkManager` `CoroutineWorker` (15-minute periodic sync with network constraints), and modularizing the monolithic `WorkItemsViewModel` (~2,856 lines) and `AzureDevOpsRepository` (~4,426 lines) into domain-focused repositories and feature delegate handlers.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural decisions were confirmed during Phase 1 clarification:
> - **Scope & Execution**: Address all three deviations in one comprehensive, cohesive implementation plan.
> - **Background Sync Engine**: Adopt AndroidX `WorkManager` with a 15-minute periodic interval constrained to active network connectivity (`NetworkType.CONNECTED`), replacing `JobScheduler` and `AzureNotificationJobService`.
> - **Monolith Decomposition**: Modularize `WorkItemsViewModel` and `AzureDevOpsRepository` into domain-focused repositories (Work Items, Process Metadata, Classification, Comments, Notifications, Account Management) and feature delegate handlers (Filter, Stream, CRUD, Hierarchy).

---

## 1. Overview & Core Concept

- **What It Delivers**:
  1. **Strict Type-Safe Navigation**: Compile-time route safety across Compose Navigation 2.8+ using `@Serializable` data objects without raw String routes or path interpolation.
  2. **Reliable Modern Background Sync**: AndroidX `WorkManager` `CoroutineWorker` for robust notification polling and snapshot refresh with battery and network constraints, surviving app kills and device reboots.
  3. **Modular Domain Architecture**: Elimination of multi-thousand-line monolithic classes by establishing decoupled, single-responsibility repositories and ViewModel feature delegates while maintaining clean backward compatibility through façade composition.
- **Target Persona**: Azure DevOps mobile users who need instant responsiveness, reliable background mention notifications, and a resilient, maintainable codebase adhering to modern Android standards.
- **Key Value**: Dramatically faster compile times, simpler testability, zero risk of route runtime crashes, guaranteed background sync reliability, and clean separation of concerns.

---

## 2. User Experience & Visual Design

- **User Flows Preserved & Enhanced**:
  - **Connection Flow**: Seamless authentication, PAT validation, and instant transition to the Work Items screen via type-safe route destinations.
  - **Work Items & Hierarchy**: Instant filter feedback, progressive background streaming indicator, and inline parent-child work item creation.
  - **Background Notifications**: Every 15 minutes, `AzureNotificationSyncWorker` queries active organizations in the background with network constraints. Newly assigned items or `@mentions` trigger native Android heads-up notifications with direct deep-action readiness.
  - **Identity Confirmation**: Clean banner asking "Are you [Name]?" on notification page, saving response without re-prompting.
- **Visual Polish & Material 3 Alignment**:
  - Unchanged visual aesthetic: high-contrast typography, distinctive state badge pills, dynamic dark/light surface elevations, smooth spring animations, and edge-to-edge window insets.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Kotlinx Serialization for Navigation**
  - *Chosen Approach*: Define `@Serializable` sealed interface `AzureRoute` with data objects `Connection` and `WorkItems`.
  - *Why*: Eliminates route typos and string concatenation bugs; fully supported in AndroidX Navigation Compose 2.8.9.
  - *Alternatives Considered*: Retaining String-based `AzureDestination` was rejected as it directly conflicts with modern Jetpack Compose standards.
- **Decision 2: AndroidX WorkManager vs. JobScheduler**
  - *Chosen Approach*: Implement `AzureNotificationSyncWorker : CoroutineWorker` enqueued via `PeriodicWorkRequestBuilder` (15 min, `NetworkType.CONNECTED`). Remove `AzureNotificationJobService` from `AndroidManifest.xml`.
  - *Why*: WorkManager handles API level differences automatically, guarantees battery-friendly execution through Doze mode, and integrates seamlessly with Kotlin Coroutines.
  - *Alternatives Considered*: Keeping `JobScheduler` was rejected due to lack of standard backoff policies and deprecated APIs on newer Android versions.
- **Decision 3: Domain Repositories + Feature Delegates vs. Micro-ViewModels**
  - *Chosen Approach*: Break `AzureDevOpsRepository` into 6 domain-focused repositories with a backward-compatible façade, and break `WorkItemsViewModel` into 4 dedicated delegates (`WorkItemFilterDelegate`, `WorkItemStreamDelegate`, `WorkItemCrudDelegate`, `WorkItemHierarchyDelegate`).
  - *Why*: Maintains `WorkItemsViewModel` as the single unified source of truth for Compose UI while shrinking file sizes from ~2,800 lines to clean, maintainable classes (<400 lines each).
  - *Alternatives Considered*: Complete split into multiple separate ViewModels was avoided because the Work Items screen has deeply interconnected states (filtering affects hierarchy, CRUD updates local stream cache).

---

## 4. Technical Architecture & Data Strategy

### System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Compose UI Layer (Jetpack Compose)                    │
│                                                                             │
│   ┌──────────────────────────┐             ┌────────────────────────────┐   │
│   │     ConnectionScreen     │             │      WorkItemsScreen       │   │
│   └─────────────▲────────────┘             └─────────────▲──────────────┘   │
└─────────────────┼────────────────────────────────────────┼──────────────────┘
                  │                                        │
┌─────────────────┼────────────────────────────────────────┼──────────────────┐
│   Navigation    │                                        │                  │
│   Type-Safe     ▼                                        ▼                  │
│   NavHost     @Serializable AzureRoute.Connection    @Serializable AzureRoute.WorkItems
└─────────────────┬────────────────────────────────────────┬──────────────────┘
                  │                                        │
┌─────────────────┼────────────────────────────────────────┼──────────────────┐
│   ViewModel     │                                        │                  │
│   Layer         ▼                                        ▼                  │
│          ConnectionViewModel                    WorkItemsViewModel          │
│                                           ┌──────────────┼──────────────┐   │
│                                           ▼              ▼              ▼   │
│                                       FilterDel.    StreamDel.      CrudDel.│
│                                                          │              │   │
│                                                          ▼              │   │
│                                                    HierarchyDel.        │   │
└─────────────────┬────────────────────────────────────────┼──────────────┴───┘
                  │                                        │
┌─────────────────┼────────────────────────────────────────┼──────────────────┐
│   DI & Session  ▼                                        ▼                  │
│              SessionManager  ◄───────────────►  AppContainer                │
└─────────────────┬────────────────────────────────────────┼──────────────────┘
                  │                                        │
┌─────────────────┼────────────────────────────────────────┼──────────────────┐
│   Domain        ▼                                        ▼                  │
│   Repositories  AzureDevOpsRepository (Façade & Composition)                │
│   ┌───────────────────┬───────────────────┬───────────────────┐             │
│   │ AzureAccountRepo  │ AzureWorkItemRepo │ AzureCrudRepo     │             │
│   ├───────────────────┼───────────────────┼───────────────────┤             │
│   │ AzureProcessRepo  │ AzureClassifRepo  │ AzureNotifRepo    │             │
│   └───────────────────┴───────────────────┴───────────────────┘             │
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │
┌─────────────────────────────────┼───────────────────────────────────────────┐
│   Background Sync Engine        ▼                                           │
│   AndroidX WorkManager: AzureNotificationSyncWorker (15-min periodic)       │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Modular Components Breakdown

#### 1. Navigation Serialization (`AzureNavigation.kt`)
- `AzureRoute`:
  ```kotlin
  @Serializable
  sealed interface AzureRoute {
      @Serializable data object Connection : AzureRoute
      @Serializable data object WorkItems : AzureRoute
  }
  ```
- `AzureBoardsNavHost` updated to:
  ```kotlin
  NavHost(navController = navController, startDestination = AzureRoute.Connection) {
      composable<AzureRoute.Connection> { ConnectionScreen(...) }
      composable<AzureRoute.WorkItems> { WorkItemsScreen(...) }
  }
  ```

#### 2. AndroidX WorkManager Integration
- Add `androidx-work-runtime-ktx` to `libs.versions.toml` and `app/build.gradle.kts`.
- `AzureNotificationSyncWorker`:
  - Extends `CoroutineWorker(context, workerParams)`.
  - In `doWork()`, calls `repository.syncOrganizationNotifications()` across remembered accounts and posts Android notifications via `NotificationHelper`.
- `AzureSyncScheduler`:
  - Enqueues `PeriodicWorkRequestBuilder<AzureNotificationSyncWorker>(15, TimeUnit.MINUTES)` with `Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()`.
  - Replaces `JobScheduler` and removes `AzureNotificationJobService`.

#### 3. Domain Repositories Decomposition (`com.example.data.repository`)
- **`AzureAccountRepository`**: Saved organizations, default organization, default project preferences, credentials encryption.
- **`AzureWorkItemRepository`**: Fast hydration, batch streaming, paging, WIQL execution, delta sync queries, and snapshot local storage.
- **`AzureWorkItemCrudRepository`**: JSON Patch building, work item creation, updates, and parent-child link manipulation.
- **`AzureProcessMetadataRepository`**: Process work item types, states, colors, icons, and backlog hierarchies.
- **`AzureClassificationRepository`**: Area and Iteration node trees, hierarchical path expansion, and filtering.
- **`AzureCommentsRepository`**: Discussion threads, HTML comment rendering, and comment additions.
- **`AzureNotificationsRepository`**: User identity detection, comment mention parsing, notification caching, and unread diffing.
- **`AzureDevOpsRepository`**: Clean composition façade coordinating the sub-repositories to preserve external contracts for existing tests and ViewModels.

#### 4. ViewModel Feature Delegates Decomposition (`com.example.ui.viewmodel.delegates`)
- **`WorkItemFilterDelegate`**: Search query matching, project/type/state/assignee/area/iteration multi-dimensional filtering, and fast classification path checking.
- **`WorkItemStreamDelegate`**: Paged background streaming coordination, delta sync interval scheduling, and memory cache hydration.
- **`WorkItemCrudDelegate`**: Debounced field updates, JSON patch dispatch, optimistic local state mutations, and conflict tracking.
- **`WorkItemHierarchyDelegate`**: Allowed child types computation, parent-child link validation, and child item tree resolution.
- **`WorkItemsViewModel`**: Slim coordinator exposing `WorkItemsUiState`, binding delegates to `SessionManager` and Coroutine scopes.

---

## 5. Verification & Test Plan

1. **Gradle Build & Catalog Sync**:
   - Verify `androidx.work:work-runtime-ktx` and `kotlinx-serialization-json` compile cleanly via `compile_applet`.
2. **Local JVM Testing (Robolectric)**:
   - Run existing test suite: `gradle :app:testDebugUnitTest`.
   - Add unit tests for `AzureRoute` serialization, `AzureNotificationSyncWorker` constraints, and domain repository delegates.
3. **End-to-End Functional Verification**:
   - Screen navigation between Connection and Work Items using `@Serializable` routes.
   - Background sync worker scheduling upon organization connection.
   - Work item searching, filtering, creation, debounced field edits, and parent-child linking.
