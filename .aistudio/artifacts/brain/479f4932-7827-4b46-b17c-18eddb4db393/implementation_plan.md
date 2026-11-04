# High-Performance Modular Refactoring & Gesture Engine Overhaul

This refactoring eliminates UI frame drops, input lag, and gesture unresponsiveness by breaking apart monolithic UI views into state-isolated modular components, moving two-stage bottom sheet animations out of the Compose recomposition phase into the layout/draw phases, and decoupling keystroke edits from global work-item list recalculations.

### User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural and UX decisions incorporate your confirmed priorities from our review:

- **Confirmed Decision 1 — Primary Performance Target (Detail Bottom Sheet & Gestures)**: Sheet drag, fling, and settle animations will run strictly in the layout and draw phases (`offset { }` and `graphicsLayer { }`) with zero recomposition per frame. When the Detail sheet is fully expanded, swiping down from the upper 1/4–1/3 of the screen will reliably capture the gesture and close the sheet completely, while lower-screen touches remain locked to smooth content scrolling.
- **Confirmed Decision 2 — Modular Component Architecture**: Monolithic screen and sheet views will be split into small, focused feature modules (under 400 lines each) with dead and duplicated UI helpers removed.
- **Confirmed Decision 3 — Isolated Subcomponent State & Debounced Persistence**: Each section of the Work Item Detail and Create sheets (Title, Metadata Pills, Area/Iteration Hierarchy, Description, Tags, Parent/Children Links, and Discussion/Mentions) will manage its own local UI state so typing or toggling a picker only recomposes that specific subcomponent rather than the entire screen or global work-item store.

---

### 1. Overview & Core Concept

- **What It Does**: Transforms the Azure Boards client into a lightweight, modular, high-frame-rate application where board browsing, filtering, two-stage sheet gestures, inline editing, and `@mention` discussions respond instantaneously without UI stutter.
- **Target Audience / Persona**: Engineering leads, product managers, and developers managing large Azure DevOps backlogs on mobile who rely on rapid one-handed gestures, instant filtering, and lag-free text editing.
- **Key Value**:
  - **Zero-Recomposition Sheet Gestures**: Dragging the Detail or Create sheet up or down mutates only hardware-accelerated layer transforms and layout offsets—never re-running Composable functions during 60/120Hz touch events.
  - **Zero Keystroke Cascading**: Editing a work item title, description, or comment no longer clones the entire in-memory backlog or serializes snapshots to disk on every character typed.
  - **Predictable Two-Stage Sheet Physics**: Preserves the two-stage opening flow (half-open preview that expands into a locked full-screen view on upward scroll or input focus) and guarantees that a downward swipe in the upper screen zone smoothly closes the sheet completely.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Two-Stage Sheet Open & Upper-Zone Swipe Dismiss**:
     - Tapping a work item card smoothly slides the Detail sheet up to its Stage 1 preview height (`62%` of screen height).
     - Scrolling upward or focusing any text input (`Title`, `Description`, or `Comment`) smoothly expands the sheet to Stage 2 (full height) and automatically scrolls the active input above the software keyboard.
     - Once fully expanded in Stage 2, scrolling anywhere in the lower `2/3` of the screen scrolls the work item content without accidentally pulling the sheet down.
     - Swiping downward starting in the upper `1/4–1/3` of the screen (even when touching over the header, title, or top metadata area) cleanly intercepts the downward drag and closes the Detail sheet completely. Tapping `Back` or `X` also closes or steps back immediately.
  2. **Lag-Free Inline Editing & `@Mention` Autocomplete**:
     - Tapping the title or typing in the description updates local field state at native frame rate, committing changes to the repository after a quiet debounce window or when editing finishes.
     - Typing `@` in the Discussion composer displays a pre-indexed floating mention bar docked above the keyboard without scanning the backlog on the UI thread.
  3. **Streamlined Home Board & Facet Filtering**:
     - Switching organizations, projects, work item types, states, assignees, areas, or iterations updates only the affected filter bar and lazy list items via stable keys.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Clean, high-craft utilitarian workspace tailored for dense engineering metadata and bilingual (LTR English + RTL Persian/Arabic) readability.
  - *Color Palette & Mood*: Crisp light surface hierarchy (`#FFFFFF` elevated cards over `#EEF2F8` / `#DFE4F1` soft slate backdrops), Azure Blue primary accents (`#0078D4`), Teal interactive highlights (`#0D9488`), and semantic Azure Boards work-item type and state badges.
  - *Typography & Hierarchy*: Bold, high-contrast headers with automatic per-field bidirectional (`LTR`/`RTL`) script detection (`isAlmostPersian`), compact metadata pills, and scannable breadcrumb subtitles.
  - *Component Styling & Layout*: Elevated rounded bottom sheets (`28.dp` top corners in Stage 1 transitioning to `0.dp` flush full-screen in Stage 2), `12.dp–16.dp` bordered cards, and accessible `48.dp` minimum touch targets.

- **Interactive Feedback & Motion**:
  - Physics-driven spring settling (`Spring.DampingRatioNoBouncy`, `Spring.StiffnessMediumLow`) when expanding or snapping the sheet, paired with a smooth `200ms` slide-out on complete dismissal.
  - Dynamic backdrop scrim opacity driven directly in `graphicsLayer` proportional to sheet offset.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Layout/Draw-Phase Sheet Offset vs. State-Driven Recomposition**
  - *Chosen Approach*: Drive the two-stage sheet container using a lambda-based `Modifier.offset { IntOffset(0, offsetPx.roundToInt()) }` and `Modifier.graphicsLayer { alpha = ... }` backed by a single float state / `Animatable` provider, without reading `offsetPx` inside the Composable body.
  - *Why*: Reading `currentOffsetPx` inside the Composable body previously forced Compose to re-execute the sheet container and its children on every touch event. Deferring the read to the layout and draw phases reduces recompositions during dragging to zero.
  - *Alternatives Considered*: Standard Material 3 `ModalBottomSheet` was passed over because it does not support locking dismissal to the upper screen region when fully expanded while keeping lower-screen gestures strictly bound to content scrolling.

- **Decision 2: Local Editor Buffer + Debounced Commit vs. Immediate Global State Mutation**
  - *Chosen Approach*: Isolate text editing state (`Title`, `Description`, `Comment`) inside dedicated leaf Composables and debounce outbound `onUpdateWorkItem` calls (`450ms` after typing pauses or immediately on focus loss / sheet close).
  - *Why*: Previously, every keystroke mutated `_allWorkItems`, triggering 10+ downstream `combine` flows, re-filtering the entire project backlog, rebuilding the `@mention` directory, and writing JSON snapshots to disk on every character.
  - *Alternatives Considered*: Mutating `_allWorkItems` immediately on every keystroke was the primary cause of severe typing lag on large projects.

- **Decision 3: Precomputed Background Mention Index & Lookup Map vs. Passing `allWorkItems` to UI Sheets**
  - *Chosen Approach*: Compute the `@mention` people directory and ID-to-WorkItem lookup table on `Dispatchers.Default` inside the ViewModel/Delegate layer, passing only lightweight, `@Immutable` domain slices and an ID lookup callback to the Detail and Create sheets.
  - *Why*: Eliminates passing `allWorkItems` (hundreds or thousands of items) as a Composable parameter and removes O(N) UI-thread loops when opening the Detail sheet or typing `@`.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌────────────────────────────────────────────────────────────────────────────┐
│                        ViewModel & Delegate Layer                          │
│                                                                            │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────┐  │
│  │ WorkItemFilter       │  │ WorkItemHierarchy    │  │ WorkItemCrud     │  │
│  │ Delegate             │  │ Delegate             │  │ Delegate         │  │
│  │ (Board Slice State)  │  │ (Detail Slice State  │  │ (Debounced Patch │  │
│  │                      │  │  & Mention Index)    │  │  & Disk Persist) │  │
│  └──────────┬───────────┘  └──────────┬───────────┘  └────────┬─────────┘  │
└─────────────┼─────────────────────────┼───────────────────────┼────────────┘
              │                         │                       │
              ▼                         ▼                       ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                         WorkItemsScreen Coordinator                        │
├───────────────────────────────────┬────────────────────────────────────────┤
│       Home Board Sub-Views        │     TwoStageBottomSheetContainer       │
│  ┌─────────────────────────────┐  │  (Zero-Recomposition Layout/Draw Drag  │
│  │ • HomeTopHeaderBar          │  │   + Upper-Screen Swipe-Down Dismiss)   │
│  │ • HomeProjectAndFilterBar   │  │                   │                    │
│  │ • HomeTypeAndStateCapsules  │  │                   ▼                    │
│  │ • HomeInlineSearchBar       │  │  ┌──────────────────────────────────┐  │
│  │ • WorkItemsLazyList         │  │  │   Modular Detail Subcomponents   │  │
│  │   (Stable Keys + Card)      │  │  │ • DetailTopBar & TitleEditor     │  │
│  └─────────────────────────────┘  │  │ • DetailStateAndAssigneeRow      │  │
│                                   │  │ • ClassificationHierarchySection │  │
│                                   │  │ • IsolatedDescriptionEditor      │  │
│                                   │  │ • DetailTagsSection              │  │
│                                   │  │ • DetailParentAndChildrenSection │  │
│                                   │  │ • DetailDiscussionSection        │  │
│                                   │  │ • SharedPickerDialogs            │  │
│                                   │  └──────────────────────────────────┘  │
└───────────────────────────────────┴────────────────────────────────────────┘
```

- **Data Model & State**:
  - **Split UI State Slices**: Replace the monolithic 33-input `combine` flow with focused `@Immutable` state holders:
    1. `BoardListUiState`: Organization header, active filters, facet options, sync flags, and paginated `workItems` list.
    2. `DetailSheetUiState`: `selectedWorkItem`, `parentWorkItem`, `childWorkItems`, `allowedChildTypes`, `allowedParentTypes`, `availableStatesForSelected`, `comments`, `isLoadingComments`, and pre-indexed `mentionCandidates`.
  - **O(1) Work Item Lookup**: Maintain an indexed `Map<Int, WorkItem>` in the state layer so linking an existing parent/child by ID performs an instant O(1) lookup without passing `allWorkItems` through Compose parameters.

- **Interactive Component & State Mapping**:
  - **`TwoStageBottomSheetContainer`**:
    - *Stage 1 (Preview)*: Opens at `38%` top offset (`62%` visible height). Dragging up expands to `0f` (Stage 2) and transfers residual upward delta directly into `contentScrollState`. Dragging down past threshold or flinging downward closes the sheet completely.
    - *Stage 2 (Fully Expanded)*: Touches starting in the upper `1/3` (`down.position.y <= screenHeightPx * 0.35f`) monitor pointer movement on `PointerEventPass.Initial`; as soon as a downward vertical gesture (`dy > touchSlop` and `abs(dy) > abs(dx) * 0.7f`) is detected, the container consumes the pointer event before any child clickable or scroll modifier can steal it, smoothly tracks the finger downward, and animates to `screenHeightPx` (`onDismiss()`) upon release. Touches starting in the lower `2/3` immediately yield to `contentScrollState` so scrolling never moves or closes the sheet.
    - *Keyboard Auto-Expand & Scroll*: Focusing `Title`, `Description`, or `Comment` invokes `requestExpand()` and triggers a single coordinated `BringIntoViewRequester` after keyboard inset stabilization.
  - **`IsolatedDescriptionEditor` & `DetailTitleEditor`**:
    - Hold local `TextFieldValue` / `String` state keyed by `workItem.id`. Emit debounced updates (`450ms`) and flush any pending change immediately on focus loss or sheet dismissal.
  - **`DetailParentAndChildrenSection` & `SharedPickerDialogs`**:
    - Encapsulate dialog visibility states (`AddChildDialog`, `AddParentDialog`, `AssigneeSearchDialog`, `TagSearchAndAddDialog`, `HierarchyTreePickerDialog`) locally within their respective subcomponents so opening or typing in a dialog never recomposes the rest of the Detail or Create sheet.
