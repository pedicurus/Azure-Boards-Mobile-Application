# Azure Boards Architecture & Security Refactor

This plan decomposes the monolithic 3,560-line state holder into focused feature ViewModels with consolidated UI states, introduces Type-Safe Compose Navigation, secures Personal Access Tokens (PATs) with Android Keystore encryption, and migrates background notification polling to WorkManager—wired together via a lightweight manual `AppContainer`.

### User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural decisions were confirmed during Phase 1 clarification and govern the full implementation pass:

- **Confirmed Refactor Scope**:
  1. Split the monolithic ViewModel into focused feature ViewModels (`ConnectionViewModel`, `WorkItemsViewModel`, `NotificationsViewModel`) backed by structured/sealed UI states.
  2. Replace root state-switching with **Type-Safe Compose Navigation** (`@Serializable` destinations).
  3. Secure Azure DevOps PAT credentials via **Encrypted Token Storage** (Android Keystore AES-GCM encryption with seamless migration of existing saved organizations) and migrate periodic background sync to **WorkManager** (`CoroutineWorker`).
  4. Omit Gemini AI features and redundant UseCase wrappers to keep the codebase lean, focused, and fast to compile.
- **Confirmed Dependency Injection Strategy**: **Manual `AppContainer` DI** at the application level, providing shared repository, encrypted token store, and session state to ViewModel factories without annotation-processing overhead.
- **Confirmed Execution Strategy**: **Full cohesive refactor in a single pass**, preserving 100% of existing functionality (multi-organization switching, backlog hierarchy & process rules, optimistic work item edits, discussion mentions, identity confirmation, and deep-link handling).

---

### 1. Overview & Core Concept

- **What It Does**: Transforms the internal architecture of the Azure Boards client from a single god-object ViewModel into modular, testable feature layers while upgrading credential security, navigation ergonomics, and background reliability.
- **Target Audience / Persona**: Engineering teams and mobile Azure Boards users who rely on instant cached startup, multi-organization PAT security, reliable background push notifications for assignments and `@mentions`, and smooth navigation across work items and notification feeds.
- **Key Value**:
  - **Maintainability & Speed**: Eliminates the 3,560-line ViewModel bottleneck by separating Connection/Account management, Work Items/Backlog state, and Notification/Identity tracking.
  - **Security**: Protects sensitive Azure DevOps PATs at rest using Android Keystore AES-256-GCM encryption rather than plaintext preferences.
  - **Lifecycle-Safe Sync**: Replaces fragile in-ViewModel polling loops with `WorkManager` periodic workers and coordinated foreground refresh triggers.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Zero-State & Connection Flow (`ConnectionDestination`)**: Users connect an Azure DevOps organization and PAT (or pick from saved accounts). On validation, the app navigates via type-safe routing to the main board and clears the login backstack.
  2. **Board & Work Item Management Flow (`WorkItemsDestination`)**: Users browse, filter (Project, Type, State, Assignee, Area, Iteration), search, create, and edit work items with instant optimistic updates and process-aware parent/child hierarchy sheets.
  3. **Notifications & Identity Flow (`NotificationsDestination` / Modal Sheet & Deep Links)**: Users review `This Month` and `Past Months` Assigned and Mentioned items, confirm or override their detected Azure identity once per Org/PAT, and tap notifications (from the system tray or in-app feed) to deep-link directly into the target work item detail view.
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Preserves the app's signature **Tactile Neumorphic Material 3** aesthetic—soft extruded surfaces, crisp Azure state/type badges, and high-contrast typography.
  - *Color Palette & Mood*: Soft cool-gray neumorphic canvas (`#EEF2F7`), Azure primary accents (`#0078D4`), dynamic work-item type/state semantic colors hydrated from Azure DevOps process metadata.
  - *Typography & Hierarchy*: Clean, scannable hierarchy with compact metadata pills (icon + ID + date + time) and readable RTL/LTR Persian and English text support in titles, descriptions, and comments.
- **Interactive Feedback & Motion**:
  - Smooth shared-axis/fade transitions between `ConnectionDestination` and `WorkItemsDestination` in `NavHost`.
  - Predictive back navigation support across detail sheets, notification views, and settings dialogs.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Focused Feature ViewModels Coordinated via Shared Session State**
  - *Chosen Approach*: Split `AzureDevOpsViewModel` into `ConnectionViewModel` (auth, saved orgs, account switching), `WorkItemsViewModel` (work item list, filters, process hierarchy, detail & comment editing), and `NotificationsViewModel` (assigned/mentioned notifications, identity confirmation, unread counts), sharing active organization/account context through a reactive `SessionManager` inside `AppContainer`.
  - *Why*: Eliminates passing 45+ individual `StateFlow` parameters through `MainActivity` while keeping active organization switches synchronized across boards and notifications.
  - *Alternatives Considered*: Keeping a single ViewModel with helper delegates (still forces a massive 45-parameter composable signature in `MainActivity`).

- **Decision 2: Android Keystore AES-GCM Encrypted Token Store with Seamless Migration**
  - *Chosen Approach*: Implement `EncryptedTokenStore` backed by Android `KeyStore` (`AES/GCM/NoPadding`) to encrypt PAT strings before persisting in preferences, automatically migrating any existing plaintext PATs on first read.
  - *Why*: Hardware-backed Android Keystore encryption requires no deprecated third-party security-crypto wrappers, works reliably on API 24–36 and in local Robolectric JVM tests (with a graceful fallback cipher in JVM unit test environments), and ensures existing users do not lose their saved logins on upgrade.
  - *Alternatives Considered*: `androidx.security:security-crypto` (`EncryptedSharedPreferences`), which is deprecated by Google and frequently fails under Robolectric JVM tests without AndroidKeyStore hardware emulation.

- **Decision 3: WorkManager (`CoroutineWorker`) + Foreground Sync Coordinator**
  - *Chosen Approach*: Add `AzureSyncWorker` (`CoroutineWorker`) scheduled via `WorkManager` with `NetworkType.CONNECTED` constraints for background notification checks and delta syncs, replacing ad-hoc background services while keeping responsive foreground delta refresh when the app is active.
  - *Why*: Respects Doze mode and OS battery policies, survives process death, and integrates cleanly with `AppContainer`.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

#### Architecture & Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Compose Navigation Host (UI)                         │
│                                                                             │
│  ┌───────────────────────┐  ┌───────────────────────┐  ┌─────────────────┐  │
│  │ ConnectionDestination │  │ WorkItemsDestination  │  │  Notifications  │  │
│  │  (ConnectionScreen)   │  │   (WorkItemsScreen)   │  │  Screen / Sheet │  │
│  └───────────┬───────────┘  └───────────┬───────────┘  └────────┬────────┘  │
└──────────────┼──────────────────────────┼───────────────────────┼───────────┘
               │                          │                       │
               ▼                          ▼                       ▼
┌──────────────────────────┐ ┌────────────────────────┐ ┌─────────────────────┐
│   ConnectionViewModel    │ │   WorkItemsViewModel   │ │NotificationsViewMdl │
│ • ConnectionUiState      │ │ • WorkItemsUiState     │ │ • NotificationsUiSt │
│ • Saved Orgs & Defaults  │ │ • Filters & Hierarchy  │ │ • Identity Confirm  │
│ • Connect / Switch / Del │ │ • Detail & Comments    │ │ • Unread & MarkRead │
└──────────────┬───────────┘ └────────────┬───────────┘ └─────────┬───────────┘
               │                          │                       │
               └──────────────────────────┼───────────────────────┘
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Manual DI Container (AppContainer)                   │
│                                                                             │
│  ┌───────────────────────┐  ┌───────────────────────┐  ┌─────────────────┐  │
│  │    SessionManager     │  │  AzureDevOpsRepository│  │  WorkManager    │  │
│  │ (Active Org & Account │  │ (WIQL, Delta Sync,    │  │ AzureSyncWorker │  │
│  │  Reactive StateFlow)  │  │  Process & Mentions)  │  │(CoroutineWorker)│  │
│  └───────────┬───────────┘  └───────────┬───────────┘  └────────┬────────┘  │
│              │                          │                       │           │
│              ▼                          ▼                       │           │
│  ┌───────────────────────┐  ┌───────────────────────┐           │           │
│  │  EncryptedTokenStore  │  │  WorkItemLocalStore   │◄──────────┘           │
│  │ (Keystore AES-256-GCM)│  │ (Snapshots & Metadata)│                       │
│  └───────────────────────┘  └───────────────────────┘                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Data Model & State

1. **`ConnectionUiState`**:
   - Represents authentication status (`Connecting`, `Disconnected`, `Connected(organization)`), saved `OrganizationAccount` list, default organization/project mappings, loading indicator, and connection error messages.
2. **`WorkItemsUiState` & `WorkItemFilterState`**:
   - Groups work items (`filteredWorkItems`, `allWorkItems`, `totalCount`, `hasMoreItems`), filter selections (`searchQuery`, `selectedProject`, `selectedType`, `selectedState`, `selectedAssignee`, `selectedArea`, `selectedIteration`), available metadata options (`availableProjects`, `availableTypes`, `availableStates`, `availableAssignees`, `availableTags`, `availableAreas`, `availableIterations`), sync progress indicators (`isLoading`, `isPullRefreshing`, `isBackgroundSyncing`, `syncProgress`, `syncStatusText`), and active work item detail state (`selectedWorkItem`, `childWorkItems`, `comments`, `isLoadingComments`).
3. **`NotificationsUiState`**:
   - Encapsulates `assignedNotifications`, `mentionedNotifications`, `unreadCount`, `isSyncing`, and user identity state (`detectedUserIdentity`, `manualIdentityOverride`, `isIdentityConfirmed`).

#### Interactive Component & State Mapping

- **Authentication & Org Switching (`ConnectionViewModel` <-> `SessionManager`)**:
  - Connecting a new org or selecting an existing org validates credentials (or hydrates instant local snapshot), encrypts the PAT via `EncryptedTokenStore`, updates `SessionManager.activeAccount`, and triggers navigation to `WorkItemsDestination`.
  - Disconnecting or removing the last organization clears active session state and navigates cleanly back to `ConnectionDestination`.
- **Work Items Filtering, Optimistic Updates & Hierarchy (`WorkItemsViewModel`)**:
  - Observes `SessionManager.activeAccount` to load cached organization snapshots immediately on launch, followed by non-blocking background delta sync.
  - Updating any filter (`setSearchQuery`, `setProject`, `setTypeFilter`, `setStateFilter`, `setAssigneeFilter`, `setAreaFilter`, `setIterationFilter`) recomputes `filteredWorkItems` off the main thread and triggers server search when needed.
  - Editing a work item (`updateWorkItem`, `addChildWorkItem`, `removeChildWorkItem`, `addCommentToWorkItem`) applies optimistic state immediately, debounces network PATCH calls, and triggers mention evaluation.
- **Notifications, Identity Confirmation & Deep Linking (`NotificationsViewModel`)**:
  - Evaluates assigned work items and discussion `@mentions` (including self-mentions).
  - Persists identity confirmation (`confirmUserIdentity`, `saveCorrectedIdentity`) per Org + PAT fingerprint so the prompt is never repeated unless credentials change.
  - Handles system tray notification deep links (`handleNotificationDeepLink`) by switching to the target organization if needed, marking the notification read, and opening the target work item detail sheet.
