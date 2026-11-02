# Implementation Plan: Azure DevOps Markdown `@<member>` Mentions Refactoring

## Background & Root Cause
Currently, mentioning someone in a work item discussion does not trigger an actual mention in Azure DevOps because:
1. **Autocomplete Format**: Selecting a candidate in the discussion composer inserts plain `@Display Name` instead of the Azure DevOps bracketed syntax `@<Display Name>`.
2. **HTML Escaping & Missing Format Parameter**: `AzureDevOpsRepository.addWorkItemCommentOnServer` escapes `<` and `>` into `&lt;` and `&gt;`, replaces newlines with `<br/>`, and posts to the comments endpoint without `format=markdown`.
3. **Missing Identity Resolution**: Azure DevOps Markdown comments (`?format=markdown&api-version=7.1-preview.4`) require `@<USER_GUID>` (or `@<Display Name>` / `@<email>` when matched against known project identities) to register a real mention and send notifications, and raw Markdown comments fetched back from Azure DevOps contain `@<USER_GUID>` unless resolved via `renderedText` or the member directory.

---

## Proposed Changes

### 1. Capture & Index Azure DevOps User Identity GUIDs (`WorkItemModels.kt` & `AzureDevOpsRepository.kt`)
- **Models (`WorkItemModels.kt`)**:
  - Add `val assignedToId: String? = null` to `WorkItem`.
  - Add `val authorId: String? = null` to `WorkItemComment`.
  - Add `@param:Json(name = "renderedText") val renderedText: String? = null` and `@param:Json(name = "format") val format: String? = null` to `RawWorkItemComment`.
  - Add `@param:Json(name = "mentions") val mentions: List<RawCommentMention>? = null` to `RawWorkItemComment` for server-returned mention metadata.
- **Identity Directory (`AzureDevOpsRepository.kt`)**:
  - Extract and index identity records (`id`, `displayName`, `uniqueName`, `imageUrl`) from `System.AssignedTo`, `System.CreatedBy`, `System.ChangedBy`, and discussion comment `createdBy` across loaded work items and comments.
  - Populate `assignedToId` on `WorkItem` and `authorId` on `WorkItemComment`.

### 2. Refactor Comment Submission & Fetching for Markdown (`AzureDevOpsApi.kt` & `AzureDevOpsRepository.kt`)
- **API Endpoints (`AzureDevOpsApi.kt`)**:
  - Update `addWorkItemComment` to pass `@Query("format") format: String = "markdown"` and `@Query("api-version") apiVersion: String = "7.1-preview.4"`.
  - Update `getWorkItemComments` to pass `@Query("\$expand") expand: String = "renderedText"` and `@Query("api-version") apiVersion: String = "7.1-preview.4"` so Azure DevOps returns both raw Markdown text and resolved `renderedText`.
- **Markdown Payload Serialization (`AzureDevOpsRepository.kt`)**:
  - In `addWorkItemCommentOnServer`:
    - Preserve raw Markdown formatting (do **not** escape `<` and `>` into `&lt;` / `&gt;` or convert `\n` to `<br/>`).
    - Parse all `@<member>` tokens (matching display name or email/uniqueName in the known identity directory) and resolve them to `@<USER_GUID>` when the user's Azure DevOps GUID is known, or preserve `@<member>` so Azure Boards Markdown processing handles it natively.
- **Markdown Mention Decoding (`AzureDevOpsRepository.kt`)**:
  - In `mapRawCommentToDomain`:
    - Resolve any `@<GUID>` tokens in `raw.text` back to `@<Display Name>` using `raw.renderedText` (`data-vss-mention` display text) and the indexed identity directory, so the app never displays raw GUIDs and mention detection in notifications continues to match the current user accurately.

### 3. Update Discussion Composer & Mention Badge Rendering (`WorkItemComponents.kt` & `NotificationsScreen.kt`)
- **Composer Autocomplete (`WorkItemComponents.kt`)**:
  - Support triggering the `@` autocomplete menu when the user types either `@query` or `@<query`.
  - When a member is selected from the autocomplete popup, insert `@<Display Name> ` into the comment input box.
  - Keep `@<Display Name>` visible while editing in the input field.
- **Clean `@Display Name` Pill Rendering (`WorkItemComponents.kt` & `NotificationsScreen.kt`)**:
  - Update `formatCommentWithMentions` and `highlightMentionsInNotificationText` so that posted comments and notification previews strip the surrounding `<` and `>` brackets (as well as any resolved `@<GUID>` tokens) and render a clean `@Display Name` highlighted pill badge.

---

## Verification Plan
- **Unit Tests**:
  - Add unit tests in `MentionFormattingTest.kt` verifying:
    1. Autocomplete token extraction for both `@` and `@<` prefixes and insertion of `@<Display Name> `.
    2. Outgoing comment serialization from `@<Display Name>` to `@<USER_GUID>` in Markdown mode without HTML-escaping angle brackets.
    3. Incoming comment normalization from `@<USER_GUID>` + `renderedText` to human-readable display names and clean `@Display Name` badge rendering.
    4. Notification mention detection (`commentMentionsIdentity`) when comments contain `@<Display Name>` or `@<USER_GUID>`.
- **Build Check**:
  - Run `gradle :app:testDebugUnitTest` and `compile_applet` to confirm zero build or test regressions.
