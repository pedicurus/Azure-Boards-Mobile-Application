# Azure Boards for Android

[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2F%20Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Azure DevOps](https://img.shields.io/badge/Azure%20DevOps-REST%20API-0078D4?logo=azuredevops&logoColor=white)](https://learn.microsoft.com/en-us/rest/api/azure/devops/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

A modern, fast, and feature-rich native Android client for **Microsoft Azure Boards**, built entirely with **Jetpack Compose** and **Material Design 3**. Connect securely using Personal Access Tokens (PAT) to manage work items, track progress, engage in discussions, and receive push notifications on your device when work items are assigned to you or you are `@mentioned`.

---

## ✨ Features

### 📋 Work Item Management
- **Full Hierarchy Support**: Browse and navigate Work Items across all active backlog levels:
  - Portfolio Backlogs (e.g., Epics, Features)
  - Requirement Backlogs (e.g., User Stories, Product Backlog Items)
  - Iteration Backlogs (e.g., Tasks, Bugs)
- **Parent & Child Hierarchy Links**:
  - **In-Sheet Hierarchy Navigation & History Stack**: Tap any linked Parent or Child row inside the detail sheet to open its full detail view, view or edit its fields, and press the top-left **Back** button (or system back gesture) to return to the previous work item with all updates reflected live, or tap the top-right **Close (`X`)** button to close the sheet immediately.
  - **Dedicated Parent Section**: View linked parent details (ID, status badge, title, type symbol, and single-tap navigation). Parent section is automatically hidden for top-level backlog items (e.g., Epics) based on project process template settings.
  - **Add or Replace Parent**: Link an existing work item ID or create a brand new parent item of allowed type, with an automatic replacement confirmation dialog if a parent is already linked.
  - **Unlink Parent**: Easily remove parent links (`System.LinkTypes.Hierarchy-Reverse`) without deleting either work item.
  - **Child Item Management**: Create new child items, tap any child row to open its details, or link/unlink existing children with automated cycle and ancestor validation.
- **Rich Work Item Details**:
  - **Full-Screen Detail & Create Experience**: Dedicated full-screen pages with pinned header bars (`< Back` and `X` close controls) so vertical scrolling is completely isolated from accidental swipe dismissal.
  - **Keyboard-Aware Auto-Scrolling**: Focusing or typing in **Description** or **Discussion Comment** automatically resizes the scroll viewport above the software keyboard and brings the active text field into clear view.
  - Edit titles, descriptions, assignees, states, area paths, iteration paths, and tags.
  - Official Azure Boards type colors and vector iconography.
  - Real-time optimistic UI updates with resilient background synchronization.

### 🔍 Advanced Multi-Dimensional Filtering & Search
- **Instant Keyword & ID Search**: Search work items by title, numeric ID (`#123`), description, tags, or assignee with zero-lag background filtering.
- **Work Item Type Filter**: Ordered automatically from highest backlog level to lowest (e.g., Epic → Feature → Story → Task).
- **State Filtering**: Quick-filter by Proposed, Active/In Progress, Resolved, Closed/Done, or Removed states.
- **Area & Iteration Hierarchy Trees**: Interactive tree picker for nested classification paths (e.g., `Project\Team\FeatureArea`).
- **Assignee Filter**: Search assignees with real-time autocompletion and "Unassigned" item support.
- **Collapsible Filter Section**: Toggle between dense viewing and comprehensive filter controls.

### 💬 Discussions & Comments
- **Chronological Discussion Feed**: Read work item discussions and notes, ordered newest-first.
- **Add Comments**: Post updates and discussions with instant optimistic previews.
- **Floating Above-Keyboard @Mention Suggestions**: Floating suggestion bar docked above the virtual keyboard showing team member chips with avatar initials, email previews, and instant 1-character filtering without screen clutter or keyboard occlusion.
- **Rich Text & Mention Formatting**: Full HTML rendering with highlighted `@mentions` and author details.

### 🔔 Smart Notifications Center & Device Push Alerts
- **Dedicated Notifications Screen**: Accessible via the Bell icon beside Settings in the top header.
- **Assigned & Mentioned Sections**:
  - **Assigned**: View the 10 most recent work items assigned to you.
  - **Mentioned**: View the 10 most recent discussion comments where your name or email was `@mentioned`.
- **Streamlined Header & Refresh Controls**: Fast on-demand sync via dedicated Refresh action and pull-to-refresh without obtrusive syncing symbols cluttering the header bar.
- **System Push Notifications**:
  - Android 13+ runtime notification support with dedicated Notification Channels for **Assigned Work Items** and **Discussion Mentions**.
  - Periodic background sync via AndroidX `WorkManager` alongside live checks during active sessions.
  - **Deep-Link Support**: Tapping any system notification launches the app directly into the corresponding work item's detail sheet.
- **Seamless Identity & Zero-Configuration Notifications**: Automatically discovers your authenticated Azure DevOps profile directly from your Personal Access Token (PAT) credentials and executes native server-side `@Me` queries, providing effortless assignment and mention tracking without unnecessary verification dialogs or manual override cards.

### 🏢 Multi-Organization Support & Touch Feedback
- **Manage Multiple Organizations**: Connect to multiple Azure DevOps organizations with independent PAT credentials.
- **Quick Switching**: Switch active organizations in a single tap from the header dropdown or Settings dialog with real-time progress indicators.
- **Default Organization & Project**: Set default organization and default project preferences in Settings for instant cold starts.

### ⚡ Performance & Offline Caching
- **Offloaded Concurrency**: Heavy list filtering, regex matching, and grouping run on `Dispatchers.Default` for a smooth 60fps UI.
- **Iterative Background Streaming**: Unloaded work items stream in non-blocking batches with UI update conflation and 250ms throttling.
- **Zero-Wait Cold Starts**: Local snapshot caching restores your work items and project metadata instantly on app launch before network verification.

---

## 🛠️ Architecture & Tech Stack

```
app/src/main/java/com/example/
├── data/
│   ├── api/             # Retrofit interfaces & Azure DevOps REST client
│   ├── local/           # Atomic file-based JSON persistence & preferences
│   ├── model/           # Moshi-serializable domain models & WIQL requests
│   └── repository/      # Unified data repository, caching, and notification sync
├── notifications/       # Notification channels, push helper, and JobService
├── ui/
│   ├── components/      # Reusable Compose widgets, sheets, and cards
│   ├── screens/         # WorkItemsScreen, NotificationsScreen, ConnectionScreen, Settings
│   ├── theme/           # Material 3 colors, typography, Azure Boards color tokens
│   └── AzureDevOpsViewModel.kt  # Unidirectional reactive StateFlows & UI state
└── MainActivity.kt      # Edge-to-edge entry point & deep link intent handler
```

- **Architecture**: Clean Architecture + MVVM (Model-View-ViewModel) + Repository Pattern
- **UI Framework**: Jetpack Compose (100% Kotlin) with Material Design 3
- **Asynchronous Operations**: Kotlin Coroutines & `StateFlow`
- **Networking**: Retrofit 2, OkHttp 3, Moshi (JSON serialization)
- **Background Scheduling**: Android `JobScheduler` (`JobService`)
- **Compatibility**: Android minSdk 24 (Android 7.0+) up to targetSdk 36 (Android 15+)

---

## 🚀 Getting Started

### Prerequisites
1. Android Studio Ladybug / Meerkat (or newer) with Android SDK 34+.
2. An active **Azure DevOps** account (cloud or server).
3. A **Personal Access Token (PAT)** from Azure DevOps with:
   - **Work Items**: `Read & write`
   - **Project and Team**: `Read`

### Installation & Build

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-username/azure-boards-android.git
   cd azure-boards-android
   ```

2. **Open in Android Studio**:
   - Open Android Studio and select **Open**, then select the project root folder.
   - Wait for Gradle sync to complete.

3. **Build and Run**:
   - Connect an Android device or launch an emulator (API 24+).
   - Click **Run 'app'** or run via command line:
     ```bash
     gradle assembleDebug
     ```

### Configuration & Connecting
1. On first launch, enter your **Azure DevOps Organization**:
   - Example: `my-org` or `https://dev.azure.com/my-org`
2. Enter your **Personal Access Token (PAT)**.
3. Check **Remember credentials on this device** and tap **Connect**.
4. The application will immediately cache and display your projects and work items.

---

## 🔒 Security & Privacy

- **Direct Connections**: Network requests communicate directly with official Azure DevOps APIs (`https://dev.azure.com/{organization}/_apis/...`).
- **Local Credentials**: Authentication tokens (PAT) and cached metadata are stored locally on the user's device and are never sent to third-party tracking servers.
- **Principle of Least Privilege**: The application requests only `INTERNET`, `POST_NOTIFICATIONS`, and `ACCESS_NETWORK_STATE` permissions.

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
