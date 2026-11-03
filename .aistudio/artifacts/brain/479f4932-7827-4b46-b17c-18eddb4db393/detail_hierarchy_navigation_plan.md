# Parent & Child Detail Navigation with History Stack

Enable seamless in-sheet hierarchy navigation so users can open any linked parent or child work item directly from the detail view, inspect or edit its fields, and return to the previous work item via a history stack or close the sheet immediately.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following design decisions have been confirmed based on your preferences:

- **Confirmed Decision 1 — Back vs. Close Controls**: When viewing a parent or child item inside the detail sheet, the header displays **both** a **Back** button (top-left arrow to return to the previous work item in the stack) and a **Close** button (top-right `X` icon to dismiss the entire detail sheet and return to the board list).
- **Confirmed Decision 2 — Clean Header Layout**: The detail header remains minimal and uncluttered with just the standard **"Detail"** title, the top-left Back arrow, and the top-right Close icon, without extra breadcrumb text or previous item IDs.

---

## 1. Overview & Core Concept

- **What It Does**: Introduces a stateful navigation backstack inside the Work Item Detail experience. Users can tap on a linked **Parent** row or any **Child** row to open that item's full detail view, view or modify its status, assignee, description, tags, comments, or hierarchy links, and press **Back** to step back to the exact item they came from—or tap **Close** to exit to the main board at any time.
- **Target Audience / Persona**: Azure Boards users managing multi-level backlogs (Epics, Features, User Stories, Tasks, Bugs) who frequently traverse up and down the hierarchy while triaging or updating related items.
- **Key Value**: Eliminates the friction of losing context when inspecting a child or parent item. All edits made to the child or parent are immediately reflected on the previous item when stepping back.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Opening a Child or Parent from Detail**:
     - While viewing Work Item **A**, the user taps either the linked **Parent** card or any row in the **Children** list.
     - Work Item **A** is pushed onto the detail navigation history stack, and the detail view transitions smoothly to Work Item **B** (the tapped parent or child), fetching its latest comments and hierarchy details if not already cached.
  2. **Viewing or Editing the Target Item**:
     - Inside Work Item **B**, the user can change its State, Assignee, Area/Iteration path, Title, Description, Tags, Parent/Children links, or post a Discussion comment.
     - All updates synchronize immediately with the in-memory work item store and remote Azure Boards API.
  3. **Stepping Back to the Previous Item**:
     - The user taps the top-left **Back** arrow (or uses the Android system back gesture).
     - The detail sheet pops Work Item **A** from the history stack, resolving its freshest state from the store so any changes made to **B** (such as child status badge updates or title changes) are immediately visible in **A**'s detail view.
  4. **Closing the Sheet Directly**:
     - At any depth in the navigation stack, tapping the top-right **Close (`X`)** button (or swiping down the bottom sheet) clears the history stack and closes the detail sheet directly back to the board list.
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Clean, modern utilitarian workspace consistent with the existing neumorphic light theme (`NeumorphicBg`, `SurfaceCard`, `NeumorphicTeal`).
  - *Header Action Bar*:
    - Top-left: `40.dp` circular/icon button with `KeyboardArrowLeft` (`Back` action — steps back to previous item if history exists, or closes the sheet if at root).
    - Title: `"Detail"` in `18.sp` bold `TextPrimary`.
    - Top-right: `40.dp` icon button with `Close` (`X`) icon aligned to the trailing edge of the header bar to dismiss the sheet immediately regardless of backstack depth.
  - *Interactive Child Rows*:
    - Each `ChildWorkItemStatusRow` card is clickable across its main body (type icon, `#ID`, and title) with Material ripple feedback to open that child item's detail view, while keeping its inline status selector pill and unlink (`X`) button independently interactive.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: ID-Backed History Stack with Live Store Lookup**
  - *Chosen Approach*: Store the navigation stack as a sequence of visited work items resolved against the live `_allWorkItems` flow whenever popping back.
  - *Why*: If a user navigates from Parent → Child, changes the Child's state or title, and presses Back, resolving against the live work item state guarantees the Parent's detail view immediately reflects the updated Child without stale snapshots.
  - *Alternatives Considered*: Storing static `WorkItem` copies on the stack, which could display outdated field values if modified during the nested session.
- **Decision 2: System Back Gesture Integration inside ModalBottomSheet**
  - *Chosen Approach*: Intercept Android system back presses (`BackHandler(enabled = canNavigateBack)`) inside the detail view when the history stack is non-empty so hardware/gesture back pops to the previous item instead of dismissing the entire bottom sheet.
  - *Why*: Matches standard Android navigation expectations so users don't accidentally close the sheet when intending to return to the previous work item.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌──────────────────────────────────────────────────────────────────────┐
│                        WorkItemsViewModel                            │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                  WorkItemHierarchyDelegate                     │  │
│  │  • _navigationHistory: MutableStateFlow<List<WorkItem>>        │  │
│  │  • _selectedWorkItem: MutableStateFlow<WorkItem?>              │  │
│  │  • openWorkItemFromDetail(targetItem) -> pushes current item   │  │
│  │  • navigateBackInDetail() -> pops & resolves latest WorkItem   │  │
│  │  • dismissDetail() -> clears history & selectedWorkItem        │  │
│  └───────────────┬────────────────────────────────┬───────────────┘  │
└──────────────────┼────────────────────────────────┼──────────────────┘
                   │ StateFlow                      │ Actions
                   ▼                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│                       WorkItemDetailSheet                            │
│  • BackHandler(enabled = hasHistory) -> onNavigateBack()             │
│  • ModalBottomSheet(onDismissRequest = onDismissSheet)               │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                     FigmaItemDetailView                        │  │
│  │  ┌──────────────────────────────────────────────────────────┐  │  │
│  │  │ Header: [ < Back ]  "Detail"                 [ X Close ] │  │  │
│  │  └──────────────────────────────────────────────────────────┘  │  │
│  │  • ParentWorkItemStatusRow (onClick -> onOpenRelatedItem)      │  │
│  │  • ChildWorkItemStatusRow  (onClick -> onOpenRelatedItem)      │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

- **Data Model & State**:
  - **History Stack State**: Maintained in the hierarchy state holder as a stack of previously viewed `WorkItem` entries.
  - **Root Selection vs. Nested Navigation**:
    - Opening an item from the main board list or notifications resets the history stack and sets the item as root.
    - Opening a parent or child from inside the detail sheet pushes the current `_selectedWorkItem` onto the history stack before selecting the target item.
    - If a linked parent or child is currently only a stub (e.g., not yet loaded in `_allWorkItems`), opening it triggers an on-demand fetch of the full work item from Azure DevOps REST API and merges it into `_allWorkItems` so all fields can be viewed and edited.
- **Interactive Component & State Mapping**:
  - **Top-Left Back Button (`detail_top_back_button`)**: Invokes `onBack` which checks if the history stack has items; if so, pops the top item, looks up its latest state in `_allWorkItems`, and activates it (re-binding its parent, children, and comments). If the stack is empty, dismisses the sheet.
  - **Top-Right Close Button (`detail_top_close_button`)**: Invokes `onCloseSheet` which clears the history stack and closes the detail sheet immediately.
  - **Child Row Click (`child_work_item_row_<id>`)**: Tapping the row body invokes `onNavigateToChild(child)` to push the current item and display the child's detail view.
  - **Parent Row Click (`parent_work_item_row_<id>`)**: Tapping the row body invokes `onNavigateToParent(parent)` to push the current item and display the parent's detail view.
