# Plan: Removal of Identity Override and Verification UI

## Overview
This plan removes the "Your Identity (For Notifications)" settings card, manual identity override inputs, and identity confirmation prompts from the application. Azure DevOps natively identifies the authenticated user from the Personal Access Token (PAT) connection data and evaluates `@Me` queries server-side, making manual identity verification and override mechanisms obsolete and confusing.

## Modifications
1. **AzureBoardsSettingsDialog.kt**:
   - Remove Section 4 ("Your Identity (For Notifications)"), including the "Override" toggle, manual text field, team member picker list, and "Save Identity" button.
   - Clean up parameters (`detectedUserIdentity`, `manualIdentityOverride`, `availableAssignees`, `onManualIdentityOverrideChange`).

2. **NotificationsScreen.kt**:
   - Remove the "Are you ...?" confirmation card and the "Correct Your Identity" dialog.
   - Remove `onOpenIdentitySettings`, `isIdentityConfirmed`, `onConfirmIdentity`, `onSaveCorrectedIdentity`.
   - Update header subtitle to cleanly display the organization without an interactive identity prompt.

3. **WorkItemsScreen.kt & AzureNavigation.kt**:
   - Remove obsolete identity parameters and handlers wired between ViewModel and screens.

4. **NotificationsViewModel.kt & UiStates.kt**:
   - Simplify `NotificationsUiState` to remove `manualIdentityOverride`, `detectedUserIdentity`, and `isIdentityConfirmed`.
   - Remove `setManualIdentityOverride`, `confirmUserIdentity`, and `saveCorrectedIdentity` methods.

5. **Data & Repository Layer (`AzureDevOpsRepository.kt`, `AzureNotificationsRepository.kt`, `WorkItemCrudDelegate.kt`)**:
   - Rely directly on `detectedDisplayName` and `detectedEmail` obtained automatically from Azure DevOps `connectionData`.
   - Remove obsolete override and manual identity mutation methods.

6. **Tests & README**:
   - Update unit/screen tests in `NotificationsFilteringTest.kt`.
   - Update `README.md` to reflect the seamless PAT-based identity and notification model.
