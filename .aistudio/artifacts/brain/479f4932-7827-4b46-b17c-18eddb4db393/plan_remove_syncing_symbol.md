# Remove Syncing Symbol Next To Refresh Button Plan

Remove the syncing indicator symbols next to/in the refresh and action headers across the application (`WorkItemsScreen.kt` and `NotificationsScreen.kt`) to keep the top action bar clean and focused strictly on the refresh and notification controls.

## Critical Changes
1. **Remove Syncing Symbol in WorkItems Header (`WorkItemsScreen.kt`)**:
   - Remove the `background_sync_indicator` `Surface` block containing `CircularProgressIndicator` and `Icons.Default.Sync` located in the top header row next to the notification and settings buttons.
   - Clean up the unused `import androidx.compose.material.icons.filled.Sync`.
2. **Remove Syncing Symbol in Notifications Header (`NotificationsScreen.kt`)**:
   - Update `notifications_refresh_button` so that it directly and consistently renders the clean `Icons.Default.Refresh` icon rather than swapping to a syncing `CircularProgressIndicator` spinner during background synchronization.
3. **Keep Tests & Build Green**:
   - Verify complete compilation and execute all Robolectric/unit tests.
