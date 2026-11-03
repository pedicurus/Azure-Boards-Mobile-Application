# Parent Work Item Section Implementation Plan

Add a dedicated **Parent Work Item** section to the work item detail view in the app. This section allows users to view the current parent work item, link an existing item as parent, or create a new parent item directly from the detail view. The section is automatically hidden for work items at the highest backlog level (e.g. Epics or top portfolio levels) based on project process settings.

---

## User Review & Critical Decisions

> [!IMPORTANT] Summary of decisions confirmed during Phase 1 interactive clarification:

- **Backlog Level Exclusion**: Exclude top backlog types dynamically based on the project process configuration and standard backlog level hierarchy (`orderedBacklogLevels`). Items at index 0 (top level, e.g., Epics) will not display the Parent section.
- **Existing Parent Replacement Confirmation**: If a work item already has an existing parent linked, attempting to link or create a new parent will prompt the user with a confirmation dialog explaining that the current parent link will be unlinked and replaced.
- **Parent Action UI**: Action buttons for "New Item" and "Existing Item" will be rendered directly inside the Parent section card on `WorkItemDetailScreen` (reusing the intuitive modal patterns from the Children section).

---

## 1. Overview & Core Concept

- **What It Does**: 
  - Displays the current linked parent work item card with ID, status badge, title, type icon, and tap-to-navigate action.
  - Hides the Parent section dynamically for work items belonging to the highest backlog level in the project process.
  - Provides two distinct action triggers when no parent exists (or to replace an existing parent):
    1. **New Item**: Modal dialog to create a new parent work item of an allowed parent type and automatically link it.
    2. **Existing Item**: Modal dialog to enter/search an existing work item ID and link it as parent.
- **Target Audience**: Agile project managers, developers, and team members managing hierarchical backlog structures in Azure DevOps.
- **Key Value**: Provides bi-directional navigation and hierarchy manipulation directly from the detail screen without having to navigate to parent items to attach child links.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Viewing Detail Page**:
   - The app checks if `canHaveParent(workItem)` is true. If false (item is at top backlog level, e.g., Epic), the Parent section is completely hidden.
   - If true, the Parent section is rendered above or alongside the Children section.

2. **When Parent Exists**:
   - Display a parent card featuring parent item ID, type badge, state indicator, title, navigation arrow button, and an option menu (Replace Parent / Remove Link).
   - Tapping the parent card navigates seamlessly to the parent item's detail view.

3. **Adding a Parent ("New Item" / "Existing Item")**:
   - Tapping **New Parent** opens a modal dialog allowing the user to select from allowed parent work item types, input a title, choose an initial state, and confirm creation.
   - Tapping **Existing Parent** opens a modal dialog allowing the user to search or enter an existing work item ID.
   - If a parent is already linked, a confirmation dialog appears: *"This work item already has a parent (#123). Do you want to replace it?"*

### Visual Identity & Theme Alignment

- Uses Material Design 3 surface elevation (`tonalElevation = 1.dp`) and `OutlinedCard` styling.
- Status colors, type indicators, and typography match existing `WorkItemComponents.kt` visual patterns.
- Accessible touch targets (minimum 48dp) and test tags (`parent_section_card`, `add_new_parent_button`, `add_existing_parent_button`, `unlink_parent_button`).

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Dynamic Backlog Level Detection (`orderedBacklogLevels`)**
  - *Chosen Approach*: Use `repository.resolveOrderedBacklogLevels()` from `WorkItemHierarchyDelegate` to order project backlog levels. Index 0 represents top backlog levels.
  - *Why*: Handles custom process templates (Agile, Scrum, CMMI, and custom portfolio hierarchies) dynamically rather than hardcoding "Epic".
- **Decision 2: Server API Linking via `System.LinkTypes.Hierarchy-Reverse`**
  - *Chosen Approach*: To set work item B as the parent of work item A, patch work item A's relations by adding `rel: "System.LinkTypes.Hierarchy-Reverse"` pointing to B's REST URL.
  - *Why*: Standard Azure DevOps API practice ensures consistency with server-side hierarchy models.

---

## 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                        WorkItemDetailScreen / UI                       │
├────────────────────────────────────────────────────────────────────────┤
│ - Parent Work Item Section (Conditional on canHaveParent)              │
│ - New Parent Dialog / Existing Parent Picker Dialog                    │
│ - Replace Parent Confirmation Dialog                                   │
└───────────────────┬────────────────────────────────────────────────────┘
                    │
                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                       WorkItemHierarchyDelegate                        │
├────────────────────────────────────────────────────────────────────────┤
│ - canHaveParent(item): Boolean                                         │
│ - getAllowedParentTypesForWorkItem(item): List<String>                 │
│ - createParentWorkItem(child, title, parentType, initialState)         │
│ - linkExistingParentWorkItem(childId, parentId)                        │
│ - unlinkParentWorkItem(childId)                                        │
└───────────────────┬────────────────────────────────────────────────────┘
                    │
                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                    AzureWorkItemCrudRepository                         │
├────────────────────────────────────────────────────────────────────────┤
│ - createParentWorkItemOnServer(childId, title, parentType, state)      │
│ - linkExistingParentWorkItemOnServer(childId, parentId)                │
│ - unlinkParentWorkItemOnServer(childId)                                │
└────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State Strategy

- **Hierarchy helper methods in `WorkItemHierarchyDelegate.kt`**:
  - `getAllowedParentTypesForType(project, workItemType)`: Calculates allowed parent types by finding the index of `workItemType` in `orderedBacklogLevels`. If index > 0, returns `orderedBacklogLevels[index - 1]`.
  - `canHaveParentForWorkItem(item)`: Returns true if allowed parent types is not empty.
- **Repository Methods**:
  - Add parent creation/linking functions to `AzureWorkItemCrudRepository` and delegate in `AzureDevOpsRepository`.
- **UI State updates in `WorkItemComponents.kt` & `WorkItemsScreen.kt`**:
  - Pass `canHaveParent`, `getAllowedParentTypes`, `onAddParentWorkItem`, `onAddExistingParentWorkItem`, and `onRemoveParentWorkItem` parameters to detail composables.

---

## Plan Verification

- Run `compile_applet` to verify compilation across Kotlin files.
- Test hierarchy resolution logic and dialog behavior.
