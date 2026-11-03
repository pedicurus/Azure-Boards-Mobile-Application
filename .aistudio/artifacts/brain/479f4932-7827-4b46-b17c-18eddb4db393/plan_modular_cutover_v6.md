# Modular Architecture Cutover, Delegate Extraction & Performance Hardening Plan

This refactoring plan completes the transition to a fully modular architecture by removing legacy ViewModel code, extracting all remaining business logic from the Work Items ViewModel into its five domain delegates, isolating state collection inside Compose navigation destinations to eliminate root recompositions, and applying security backup exclusions and compile-time Moshi/Compose stability optimizations.

## User Review & Critical Decisions

> [!IMPORTANT]
> All architectural and scope decisions have been confirmed through our interactive planning questions and are reflected below.

- **Confirmed Decision 1 (Repository Cutover)**: Keep a slim, zero-duplication delegator façade (`AzureDevOpsRepository`) for backwards compatibility while wiring all domain sub-repositories (`AzureAccountRepository`, `AzureWorkItemRepository`, `AzureWorkItemCrudRepository`, `AzureProcessMetadataRepository`, `AzureClassificationRepository`, `AzureCommentsRepository`, and `AzureNotificationsRepository`) in the dependency container.
- **Confirmed Decision 2 (Deep ViewModel Delegate Extraction)**: Move the full streaming/delta sync orchestration, optimistic CRUD & parent-child hierarchy mutations, comment discussion workflows, and reactive filter/facet pipelines into the five feature delegates so `WorkItemsViewModel` serves strictly as a concise (<400 lines) coordinator.
- **Confirmed Decision 3 (Focused 4-Pillar Scope)**: Focus strictly on the 4 core architectural, navigation, security, and performance pillars without splitting the UI/Screen files in this pass.

---

## 1. Overview & Core Concept

- **What It Does**: Eliminates dead legacy ViewModel code, extracts over 2,200 lines of streaming, CRUD, hierarchy, and filtering logic from the Work Items ViewModel into focused feature delegates, scopes Compose state observation to individual navigation routes, and hardens data security and serialization performance.
- **Target Audience / Persona**: Engineering teams and product managers using Azure Boards on Android who expect instant board filtering, zero UI jank during search or background sync, and strict protection of Personal Access Tokens (PATs) and cached work item data.
- **Key Value**:
  - **Zero Duplicate Monoliths**: Removes the unused legacy ViewModel façade and keeps the repository coordinator as a pure forwarding layer over the 7 domain repositories.
  - **True Delegate Separation**: `WorkItemsViewModel` becomes a lightweight state and action coordinator (<400 lines) backed by self-contained feature delegates.
  - **Isolated Navigation Recomposition**: Typing in the search bar or changing board filters only recomposes the Work Items screen rather than the entire navigation host.
  - **Compile-Time Serialization & Compose Skippability**: Removes reflection-based `KotlinJsonAdapterFactory()` in favor of KSP-generated Moshi adapters, marks UI states and domain models `@Immutable`, and prevents cloud/device backup of sensitive preferences and work item caches.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Zero-Jank Search & Filtering**: When a user types a search query or switches project, type, state, area, or iteration filters, only the active destination observes those state updates—eliminating top-level `NavHost` recompositions and keeping frame times smooth.
  2. **Seamless Connection & Progressive Sync**: During initial connection, `ConnectionRoute` observes only lightweight connection status and initial loading indicators, transitioning smoothly to `WorkItemsRoute` once authenticated.
  3. **Optimistic Work Item & Discussion Updates**: Creating work items, linking/unlinking child items, editing fields, and posting Markdown `@mention` comments continue to update the board and detail sheet instantaneously while syncing in the background through dedicated delegates.
- **Visual Identity & Theme**:
  - Preserves the existing Material 3 and Neumorphic Teal board aesthetic, dynamic process state badges, and RTL Persian/English typographic support while improving frame stability via `@Immutable` state contracts.
- **Interactive Feedback & Motion**:
  - Fade transitions between `Connection` and `WorkItems` routes remain uninterrupted by background work item streaming or notification badge updates.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Slim Repository Façade + Direct Sub-Repository Wiring**
  - *Chosen Approach*: Wire all 7 specialized domain repositories in the manual dependency container and keep `AzureDevOpsRepository` as a pure, stateless forwarding façade with zero duplicated business logic.
  - *Why*: Eliminates over 4,000 lines of duplicate networking/parsing code while maintaining 100% compatibility with existing callers and unit tests.
  - *Alternatives Considered*: Deleting `AzureDevOpsRepository` outright was considered, but keeping a thin forwarding façade avoids unnecessary churn across test suites while achieving full modular separation.
- **Decision 2: Full Business Logic Extraction into Feature Delegates**
  - *Chosen Approach*: Expand the 5 feature delegates (`WorkItemFilterDelegate`, `WorkItemStreamDelegate`, `WorkItemCrudDelegate`, `WorkItemHierarchyDelegate`, `WorkItemProcessDelegate`) to own their respective coroutines, state flows, and repository interactions, shrinking `WorkItemsViewModel` to under 400 lines.
  - *Why*: Previously, the delegates only held primitive `MutableStateFlow` properties while `WorkItemsViewModel` still contained 2,600+ lines of orchestration. Moving the actual workflows into the delegates makes each domain independently testable and easy to reason about.
- **Decision 3: Destination-Scoped State Collection in Navigation**
  - *Chosen Approach*: Collect `workItemsUiState` and `notificationsUiState` inside `composable<AzureRoute.WorkItems>` instead of at the top of `AzureBoardsNavHost`.
  - *Why*: Collecting rapidly changing board states at the `NavHost` root forces the entire navigation graph to re-evaluate on every keystroke or streaming batch.
- **Decision 4: Compile-Time KSP Moshi Adapters, `@Immutable` Models & Backup Exclusions**
  - *Chosen Approach*: Remove `KotlinJsonAdapterFactory()` from `AzureDevOpsClient` and `WorkItemLocalStore` (relying on `@JsonClass(generateAdapter = true)` KSP adapters), annotate `ConnectionUiState`, `WorkItemsUiState`, `NotificationsUiState`, `WorkItem`, `WorkItemComment`, `AppNotificationItem`, and `OrganizationAccount` with `@Immutable`, and configure `backup_rules.xml` and `data_extraction_rules.xml` to exclude sensitive shared preferences (`azure_boards_prefs.xml` and `azure_boards_keystore_fallback_prefs.xml`) and local caches (`azure_work_items_cache`).
  - *Why*: Eliminates runtime reflection overhead during large work item batch deserialization, enables Compose smart recomposition skipping, and prevents encrypted keys or cached board data from leaking to cloud backups.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌────────────────────────────────────────────────────────────────────────────┐
│                        Compose Navigation Graph                            │
│  ┌──────────────────────────────────┐  ┌────────────────────────────────┐  │
│  │ composable<AzureRoute.Connection>│  │ composable<AzureRoute.WorkItems│  │
│  │ • Collects connectionUiState     │  │ • Collects workItemsUiState    │  │
│  │ • Collects isInitialLoading      │  │ • Collects notificationsUiState│  │
│  └─────────────────┬────────────────┘  └───────────────┬────────────────┘  │
└────────────────────┼───────────────────────────────────┼───────────────────┘
                     │                                   │
                     ▼                                   ▼
┌─────────────────────────────────────┐  ┌───────────────────────────────────┐
│        ConnectionViewModel          │  │   WorkItemsViewModel (<400 LOC)   │
│  • Account switching & credentials  │  │  • Lightweight state coordinator  │
└────────────────────┬────────────────┘  └───────────────┬───────────────────┘
                     │                                   │
                     │         ┌─────────────────────────┼────────────────────┐
                     │         ▼                         ▼                    ▼
                     │  ┌──────────────────────┐ ┌────────────────────┐ ┌────────────────────┐
                     │  │WorkItemFilterDelegate│ │WorkItemStreamDeleg.│ │WorkItemCrudDelegate│
                     │  │• Search & facet flows│ │• Connect & refresh │ │• Create & patch    │
                     │  │• Filtered items flow │ │• Delta & bg stream │ │• Optimistic sync   │
                     │  └──────────┬───────────┘ └─────────┬──────────┘ └─────────┬──────────┘
                     │             │                       │                      │
                     │             ▼                       ▼                      ▼
                     │  ┌────────────────────────────────┐ ┌─────────────────────────────────┐
                     │  │   WorkItemHierarchyDelegate    │ │     WorkItemProcessDelegate     │
                     │  │ • Parent/child link & unlink   │ │ • Process hierarchy & backlogs  │
                     │  │ • Discussion comments & @ment. │ │ • Area & iteration trees        │
                     │  └────────────────┬───────────────┘ └─────────────────┬───────────────┘
                     │                   │                                   │
                     └───────────────────┼───────────────────────────────────┘
                                         ▼
┌────────────────────────────────────────────────────────────────────────────┐
│            AppContainer & Slim AzureDevOpsRepository Delegator             │
├──────────────────────┬─────────────────────────┬───────────────────────────┤
│AzureAccountRepository│ AzureWorkItemRepository │AzureWorkItemCrudRepository│
│ • EncryptedTokenStore│ • WIQL & Delta Sync     │ • Create / Patch / Link   │
├──────────────────────┼─────────────────────────┼───────────────────────────┤
│AzureProcessMetadata  │AzureClassificationRepo  │  AzureCommentsRepository  │
│ • States & Categories│ • Area/Iteration Trees  │  & NotificationsRepository│
└──────────────────────┴─────────────────────────┴───────────────────────────┘
```

### Data Model & State Strategy

1. **Compose Stability (`@Immutable`)**:
   - Annotate `ConnectionUiState`, `WorkItemsUiState`, and `NotificationsUiState` as `@Immutable` so Compose treats the state snapshots as stable and skips recomposing unchanged subtrees.
   - Annotate `WorkItem`, `WorkItemComment`, `AppNotificationItem`, and `OrganizationAccount` as `@Immutable`.
2. **KSP Moshi Serialization (Zero Reflection)**:
   - Remove `.addLast(KotlinJsonAdapterFactory())` from `Moshi.Builder()` in both `AzureDevOpsClient` and `WorkItemLocalStore` so Moshi uses the compile-time KSP-generated adapters for all `@JsonClass(generateAdapter = true)` models.
3. **Android Auto-Backup & Data Extraction Hardening**:
   - Configure both `backup_rules.xml` (`<full-backup-content>`) and `data_extraction_rules.xml` (`<cloud-backup>` and `<device-transfer>`) to exclude `sharedpref` (`azure_boards_prefs.xml` and `azure_boards_keystore_fallback_prefs.xml`) and `file` (`azure_work_items_cache`), preventing sensitive credentials, fallback encryption keys, or cached work items from being extracted or backed up to the cloud:
     ```xml
     <exclude domain="sharedpref" path="azure_boards_prefs.xml"/>
     <exclude domain="sharedpref" path="azure_boards_keystore_fallback_prefs.xml"/>
     <exclude domain="file" path="azure_work_items_cache"/>
     ```

### Interactive Component & State Mapping

| Component / Layer | Responsibility After Cutover | Key State / Handler Wiring |
| :--- | :--- | :--- |
| **Legacy ViewModel Cleanup** | Delete unused `AzureDevOpsViewModel` class | All screens and activities use `ConnectionViewModel`, `WorkItemsViewModel`, and `NotificationsViewModel` |
| **`WorkItemFilterDelegate`** | Owns search query, facet filters, project/type auto-selection, and the reactive `filteredWorkItems`, `availableProjects`, `availableTypes`, `availableStates`, `projectAssignees`, `projectFrequentTags`, `projectAreas`, `projectIterations`, `hasMoreItems`, and `totalWorkItemsCount` flows | Exposes derived `StateFlow`s and filter mutation handlers (`setProject`, `setTypeFilter`, `setStateFilter`, `setSearchQuery`, etc.) |
| **`WorkItemStreamDelegate`** | Owns `connect`, `refreshWorkItems`, `switchOrganization`, `saveOrganizationFromSettings`, `loadMoreWorkItems`, `startIterativeBackgroundStream`, `observeOnDemandServerSearch`, and local snapshot restoration/persistence | Coordinates `workItemRepository` and `processDelegate` on `CoroutineScope` |
| **`WorkItemCrudDelegate`** | Owns `updateWorkItem`, `createWorkItem`, dirty item tracking, debounced JSON Patch sync (`syncWorkItemToServer`), and assigned notification cleanup on reassignment | Coordinates `crudRepository` and mutates the shared `_allWorkItems` flow |
| **`WorkItemHierarchyDelegate`** | Owns `selectWorkItem`, `selectedWorkItemChildren`, `addCommentToWorkItem`, `addExistingChildWorkItem`, `addChildWorkItem`, `removeChildWorkItem`, `getStatesForType`, and `getAllowedChildTypesForType` | Coordinates `commentsRepository`, `crudRepository`, and `notificationsRepository` |
| **`WorkItemsViewModel`** | Slim (<400 lines) lifecycle owner combining delegate flows into `WorkItemsUiState` and forwarding UI actions to delegates | Zero duplicate internal state; delegates all streaming, CRUD, hierarchy, and filter logic |
| **`AzureBoardsNavHost`** | Collects `connectionUiState` (and lightweight `isLoading` for connection screen) at root; collects `workItemsUiState` and `notificationsUiState` strictly inside `composable<AzureRoute.WorkItems>` | Prevents search/filter state emissions from recomposing `NavHost` or `ConnectionScreen` |
