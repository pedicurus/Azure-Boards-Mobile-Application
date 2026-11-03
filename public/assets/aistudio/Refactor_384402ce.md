We want to fully commit to the new modular architecture and delete dead/monolithic code:

1. Clean up Repository Layer:
   - Wire AppContainer to use the new domain repositories (AzureWorkItemRepository, AzureNotificationsRepository, AzureProcessMetadataRepository, etc.).
   - Either delete AzureDevOpsRepository.kt or turn it into a strict, lightweight façade (<100 lines) that only forwards calls to the new sub-repositories, eliminating the 4,000 lines of duplicate logic.

2. Clean up ViewModel Layer:
   - Delete AzureDevOpsViewModel.kt (the old legacy facade).
   - Ensure WorkItemsViewModel fully relies on its delegates (WorkItemFilterDelegate, WorkItemStreamDelegate, WorkItemCrudDelegate, etc.) rather than keeping internal duplicate state.

3. Fix the Root Navigation Recomposition:
   - In AzureNavigation.kt, move the collection of workItemsUiState and notificationsUiState inside composable<AzureRoute.WorkItems> so that typing or filtering doesn't recompose the entire NavHost.

4. Apply the Security & Performance Fixes:
   - Exclude sensitive prefs and cache in backup_rules.xml and data_extraction_rules.xml.
   - Add @Immutable to the state classes in UiStates.kt and WorkItem in WorkItemModels.kt.
   - Remove KotlinJsonAdapterFactory() in AzureDevOpsApi.kt and WorkItemLocalStore.kt to use the KSP-generated adapters.

### 1. The Monoliths That Were Refactored

| Original Monolith File | Size | What Was Extracted / Created To Replace It | Current Status in Repo |
| :--- | :--- | :--- | :--- |
| **`AzureDevOpsViewModel.kt`** | 3,561 lines | • `ConnectionViewModel.kt` (244 lines)<br>• `NotificationsViewModel.kt` (339 lines)<br>• `WorkItemsViewModel.kt` | ⚠️ **Legacy Façade**: Reduced to 157 lines, but completely unused by `MainActivity.kt`. **Safe to delete**. |
| **`AzureDevOpsRepository.kt`** | 4,426 lines | • `AzureAccountRepository.kt` (244 lines)<br>• `AzureWorkItemRepository.kt` (552 lines)<br>• `AzureWorkItemCrudRepository.kt` (660 lines)<br>• `AzureNotificationsRepository.kt` (811 lines)<br>• `AzureProcessMetadataRepository.kt` (1,134 lines)<br>• `AzureClassificationRepository.kt` (125 lines)<br>• `AzureCommentsRepository.kt` (217 lines)<br>• `AzureIdentityRegistry.kt` (127 lines)<br>• `AzureWorkItemParser.kt` (208 lines) | ⚠️ **Unfinished Cutover**: The sub-repositories were created, but `AzureDevOpsRepository.kt` is still 4,180 lines because the code inside it wasn't deleted. |
| **`WorkItemsViewModel.kt`** | 2,856 lines | • `WorkItemFilterDelegate.kt` (85 lines)<br>• `WorkItemStreamDelegate.kt` (71 lines)<br>• `WorkItemCrudDelegate.kt` (54 lines)<br>• `WorkItemHierarchyDelegate.kt` (135 lines)<br>• `WorkItemProcessDelegate.kt` (136 lines) | ⚠️ **Partial Cutover**: Still 2,632 lines. It created the delegates, but only wired `filterDelegate`, leaving the rest of the stream/CRUD/hierarchy logic inside the ViewModel. |
| **`AzureNotificationJobService.kt`** | ~80 lines | • `AzureNotificationSyncWorker.kt` (33 lines)<br>• `AzureSyncWorker.kt` (WorkManager integration) | ✅ **Deleted**: Successfully removed and replaced with AndroidX WorkManager. |
| **`AzureNavigation.kt`** (String routing) | ~110 lines | • Type-safe `@Serializable sealed interface AzureRoute` (`Connection`, `WorkItems`) | ✅ **Modernized**: String routes were eliminated and replaced with Kotlinx Serialization. |

---

### 2. Files You Can Delete or Clean Up Right Now

#### 🗑️ File 1: Delete `app/src/main/java/com/example/ui/AzureDevOpsViewModel.kt`
* **Why**: `MainActivity.kt` now directly injects and uses `ConnectionViewModel`, `WorkItemsViewModel`, and `NotificationsViewModel`. `AzureDevOpsViewModel.kt` is dead code.

#### ✂️ File 2: Cut Down `AzureDevOpsRepository.kt` (From 4,180 lines to ~80 lines)
* **Why**: All its methods have already been copied into the dedicated sub-repositories (`AzureAccountRepository`, `AzureWorkItemRepository`, `AzureNotificationsRepository`, etc.).
* **Action**: Instead of keeping 4,180 lines of duplicate code, make `AzureDevOpsRepository` a clean delegator:
  ```kotlin
  class AzureDevOpsRepository(context: Context, tokenStore: EncryptedTokenStore) {
      val accountRepo = AzureAccountRepository(context, tokenStore, localStore)
      val workItemRepo = AzureWorkItemRepository(...)
      val notificationsRepo = AzureNotificationsRepository(...)
      // Forward external calls directly to the sub-repositories
  }
  ```
  *(Or delete it completely and inject the specific sub-repositories into `AppContainer`)*.

---

### 3. Monoliths Remaining on the UI/Screen Side (FYI)

If you plan to refactor further in the future, these UI files are the next largest monoliths in the app:
1. **`ui/components/WorkItemComponents.kt`** (5,085 lines) – holds virtually all board cards, chips, dialogs, and bottom sheets in one file.
2. **`ui/screens/WorkItemsScreen.kt`** (1,863 lines) – the main board screen and gestures.
3. **`ui/screens/NotificationsScreen.kt`** (1,230 lines) – the notifications feed and identity modal.