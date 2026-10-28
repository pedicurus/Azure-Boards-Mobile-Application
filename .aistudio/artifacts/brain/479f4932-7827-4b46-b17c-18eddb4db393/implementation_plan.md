# Implementation Plan: Create New Items Button & Full Create Work Item Flow

## 1. Overview & Goals
Replace the **Summary** section (`Summary` header, `Remaining Items`, and `Completed Items` cards) on the Home screen with a prominent **Create New Items** button positioned between the **Organization Name** header row and the **Project Name** selector row. Tapping this button opens a full-screen **Create New Item** page that mirrors the existing Work Item Detail layout while enforcing the two required fields (**Work Item Type** and **Title**) and supporting all detail attributes (**State**, **Assignee**, **Tags**, **Description**, and **Children**) with automatic parent saving when adding a child work item.

---

## 2. Home Screen Updates (`WorkItemsScreen.kt`)
- **Remove Summary Section**:
  - Remove the `"Summary"` section heading and the two metric cards (`Remaining Items` and `Completed Items`) from the Home screen header area.
  - Clean up unused `remainingCount` and `completedCount` state collections in `WorkItemsScreen`.
- **Add "Create New Items" Button**:
  - Place a full-width, Azure DevOps Blue (`#0078D4`) primary button labeled **"Create New Items"** (with a leading `+` icon and `testTag("create_new_items_button")`) directly between the Organization header row (`HomeOrganizationHeader` + Refresh/Settings icons) and the Project/Assignee selector row (`HomeProjectDropdown` + `HomeAssigneeDropdown`).
  - Tapping **Create New Items** opens the **Create New Item** full-screen view, pre-selecting the currently active project and defaulting the Work Item Type to the active type filter (or the top-level work item type for the project).

---

## 3. Create New Item Page & Detail Parity (`WorkItemComponents.kt`)
- **Unified Create / Detail Experience (`FigmaCreateWorkItemView`)**:
  - Match the rounded sheet design and visual hierarchy of `FigmaItemDetailView`, including bidirectional English/Persian (RTL/LTR) text support.
  - **Top Bar**: Back navigation (`< Create New Item`) and a primary **Save / Create** action button (`create_work_item_save_button`). Also register a `BackHandler` to return to the Home board cleanly.
  - **1. Required Work Item Type Selector (First Essential Field)**:
    - Render a horizontal **Capsule Selector** at the top of the Create page listing the project's enabled work item types ordered from biggest to smallest in the hierarchy.
    - Selecting a type dynamically updates the available **States** (defaulting to the initial state for that type, e.g., `New` / `To Do` / `اهداف`) and the allowed **Child Work Item Types** for the Children section.
  - **2. Required Title Input (Second Essential Field)**:
    - Prominent, multi-line RTL/LTR-aware title field (`create_work_item_title_input`) with clear placeholder and inline validation feedback if the user attempts to save or add a child before entering a title.
  - **3. Metadata Row (State & Assignee)**:
    - Interactive **State** badge dropdown selector (`create_state_selector`) populated with the enabled states for the chosen Work Item Type.
    - Interactive **Assignee** pill dropdown selector (`create_assignee_selector`) populated with `Unassigned` and all known project/organization members.
  - **4. Tags Section**:
    - Identical to the Detail page: displays attached tag chips with remove (`×`) buttons and a `+` button (`create_add_tag_button`) opening the frequent tags dropdown + `"Create new tag..."` dialog.
  - **5. Description Section**:
    - Editable, auto-directional (RTL/LTR) description card (`create_description_input`) matching the Detail screen.
  - **6. Children Section & Auto-Save Parent Logic**:
    - Displayed whenever the selected Work Item Type supports child work items in the project's hierarchy (`allowedChildTypes.isNotEmpty()`).
    - When the user taps the `+` button (`create_add_child_button`) to add a child item:
      - If **Title** is blank, prompt the user inline to enter the parent Title first.
      - Open the **Add Child Work Item** dialog (allowing the user to enter Child Title, Child Type, and Initial State).
      - Upon confirming the dialog, if the parent work item has not been saved to Azure Boards yet, **automatically save/create the parent work item on the server first** to obtain its real Azure DevOps `#ID`, transition the screen seamlessly into the saved parent item, and immediately create and link the new child work item (`System.LinkTypes.Hierarchy-Reverse`) under that parent.

---

## 4. Data & ViewModel Layer (`AzureDevOpsRepository.kt` & `AzureDevOpsViewModel.kt`)
- **Repository (`createWorkItemOnServer`)**:
  - Add `createWorkItemOnServer` in `AzureDevOpsRepository` using `POST /{organization}/{project}/_apis/wit/workitems/${type}?api-version=7.0` (`application/json-patch+json`).
  - Include patch operations for `/fields/System.Title`, `/fields/System.Description` (if non-blank), `/fields/System.AssignedTo` (with fallback from email/identity to display name if needed), and `/fields/System.Tags` (if non-empty).
  - If the user selected a non-default initial `/fields/System.State`, apply a follow-up patch update (just like child item creation) so Azure Boards state transition rules are respected.
- **ViewModel (`createWorkItem` & Auto-Save for Child Creation)**:
  - Add `createWorkItem(...)` in `AzureDevOpsViewModel` that creates the work item on the server, inserts it into `_allWorkItems` and `serverSyncedItems`, and invokes an `onCreated(WorkItem)` callback.
  - Support both:
    - **Standard Save**: Saves the new work item and returns to the Home list (or updates the board immediately).
    - **Auto-Save on Add Child**: Saves the parent work item first, switches the active item to the newly created `WorkItem` (with its real `#ID`), and immediately executes `addChildWorkItem(createdParent, childTitle, childType, childState)`.

---

## 5. Verification Plan
- Compile the project using `compile_applet` to ensure zero build or type errors.
- Run the unit and Robolectric test suite (`gradle :app:testDebugUnitTest`) and verify the Home screen button placement and Create New Item workflow.
