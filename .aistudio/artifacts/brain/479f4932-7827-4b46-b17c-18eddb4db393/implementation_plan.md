# Hierarchical Areas & Iterations Across Filters, Detail, and Creation

This update introduces Azure Boards **Areas** (`System.AreaPath`) and **Iterations** (`System.IterationPath`) as full hierarchical classification trees across the app—adding a collapsible **Assignee + Area + Iteration** filter bar on the Home screen, a dedicated **Area & Iteration** section before **Description** on both the **Detail** and **Create New Item** pages, and an interactive **Expandable Tree Picker** with search and path breadcrumbs.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following design and functional choices were confirmed during clarification and govern this implementation:

- **Confirmed Decision 1 — Expandable Indented Tree Picker with Search & Path Preview**: Users browse and pick Areas and Iterations using an interactive hierarchical tree modal featuring expand/collapse chevrons for parent nodes, indentation per hierarchy level, a real-time search box, and a full breadcrumb path preview.
- **Confirmed Decision 2 — Hierarchical Sub-Path Filtering (`Under` Semantics)**: Selecting a parent Area or Iteration in the Home filter bar automatically includes work items assigned to that path **and all of its child sub-paths** (matching Azure Boards `UNDER` query semantics).
- **Confirmed Decision 3 — Hide/Unhide Filters Button Beside Project Title**: A filter toggle button next to the Project title on the Home screen shows or hides the **Assignee, Area, and Iteration** filter row below the **Create New Items** button (with an active filter count badge when filters are applied while collapsed).

---

## 1. Overview & Core Concept

- **What It Does**:
  - Fetches the authoritative Area and Iteration classification trees from Azure DevOps (`_apis/wit/classificationnodes/areas?$depth=10` and `_apis/wit/classificationnodes/iterations?$depth=10`) and merges them with any Area/Iteration paths observed on loaded work items so the hierarchy is always complete even offline.
  - Combines **Assignee**, **Area**, and **Iteration** into a unified, collapsible filter section below the **Create New Items** button on the Home screen, controlled by a **Hide/Unhide Filters** button right beside the Project title.
  - Adds a dedicated **Classification (Area & Iteration)** section immediately before the **Description** section on both the **Work Item Detail** sheet and the **Create New Item** sheet, allowing instant viewing and editing of a work item's Area and Iteration with server persistence.
- **Target Audience / Persona**: Teams and project managers organizing Azure Boards backlogs across multiple product areas, sub-teams, releases, and sprints.
- **Key Value**: Brings full Azure Boards spatial and temporal hierarchy navigation to mobile without cluttering the Home screen or forms.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Home Screen Filtering & Hide/Unhide Toggle**:
     - Below the **Create New Items** button, the Project header row displays the interactive **Project selector** on the left and a **Hide/Unhide Filters** toggle pill/icon button on the right (displaying an active badge count if any Assignee, Area, or Iteration filter is active).
     - When visible, a sleek horizontal filter strip directly below the Project row houses three coordinated filter pills: **Assignee**, **Area**, and **Iteration** (above the Work Item Type and State capsules).
     - Tapping **Area** or **Iteration** opens the hierarchical **Tree Picker Dialog** with an `"All Areas"` / `"All Iterations"` option at the top and expandable tree nodes below. Selecting any parent node filters the list to items in that node or any nested child path.
  2. **Detail Page & Create New Item Page (Section Before Description)**:
     - Located after the **State & Assignee** metadata row and **before the Description section**, a structured **Area & Iteration** section presents two interactive cards/rows:
       - **Area**: Shows the leaf area name in bold alongside its compact parent breadcrumb trail (e.g., `Project › Platform › Backend`). Tapping opens the Area Tree Picker and immediately updates `System.AreaPath`.
       - **Iteration**: Shows the leaf sprint/iteration name in bold alongside its parent release/project breadcrumb (e.g., `Project › 2026 Q4 › Sprint 14`). Tapping opens the Iteration Tree Picker and immediately updates `System.IterationPath`.
  3. **Expandable Indented Tree Picker Modal**:
     - **Top Search Input**: Filters the tree in real time; when searching, matching nodes and their ancestor paths are automatically expanded.
     - **Selected Path Preview Banner**: Displays the currently selected full breadcrumb path (`Project \ Team \ Sub-Area`) with RTL/LTR text awareness.
     - **Interactive Hierarchy Tree**: Each node row has an expand/collapse chevron (if it has children) and a selectable node body indented by `depth * 18.dp`, with a folder/hierarchy icon for Areas and a calendar/sprint icon for Iterations.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Crisp neumorphic-light surfaces matching the existing Azure Boards mobile design language.
  - *Color Palette & Mood*:
    - Surface background: `#F8FAFC` / `#FFFFFF` cards with `#DCE1EF` borders.
    - Primary accent: Azure Blue `#0078D4` (`NeumorphicTeal`) for active filter pills, selected tree nodes, and active filter badges.
    - Hierarchy connectors & breadcrumbs: Muted slate `#64748B` for parent segments and `#0F172A` bold for the active leaf segment.
  - *Interactive Feedback & Motion*: Smooth `AnimatedVisibility(expandVertically() + fadeIn(), shrinkVertically() + fadeOut())` when toggling the **Assignee / Area / Iteration** filter section open or closed, and animated chevron rotation when expanding/collapsing tree branches.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1 — Expandable Tree with Compact Breadcrumb Display on Cards/Pills**:
  - *Chosen Approach*: Show the leaf node name prominently with a subtle parent path subtitle in the Detail/Create section, and open an expandable indented tree modal on tap.
  - *Why*: Deep Azure DevOps paths (e.g., `OrganizationProject\Product\Mobile\Android\Auth`) overflow single-line dropdowns on mobile screens. Showing the leaf + compact breadcrumb keeps the layout scannable while preserving full hierarchy context in the tree modal.
  - *Alternatives Considered*: Flat dropdown menus with raw backslash strings, which truncate critical leaf names on narrow viewports.
- **Decision 2 — Hybrid Classification Tree Resolution (Server Classification Nodes API + Work Item Path Synthesis)**:
  - *Chosen Approach*: Fetch the full project tree from `_apis/wit/classificationnodes` and merge it with prefix trees constructed from `areaPath` and `iterationPath` values across cached/loaded work items.
  - *Why*: Guarantees that newly created empty Areas/Sprints from Azure Boards appear in the tree while also working seamlessly offline from cached work items.
- **Decision 3 — Hierarchical Prefix Matching (`UNDER`) for Home Filters**:
  - *Chosen Approach*: Normalize backslash/slash separators and match `itemPath.equals(selectedPath, ignoreCase = true) || itemPath.startsWith("$selectedPath\\", ignoreCase = true)`.
  - *Why*: Matches standard Azure Boards `System.AreaPath UNDER '...'` behavior so selecting a team or release roll-up shows all work items across its child sub-areas or sprints.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:
```
┌───────────────────────────────────────────────────────────────────────────┐
│                         AzureDevOpsService (REST)                         │
│  • GET {org}/{project}/_apis/wit/classificationnodes?$depth=10            │
│  • PATCH /fields/System.AreaPath & /fields/System.IterationPath           │
└────────────────────────────────────┬──────────────────────────────────────┘
                                     │
                                     ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                           AzureDevOpsRepository                           │
│  • Fetches & caches Project Area/Iteration paths per Organization/Project │
│  • Builds hierarchical ClassificationTreeNode trees from paths + API      │
│  • Updates & creates WorkItems with System.AreaPath & System.IterationPath│
└────────────────────────────────────┬──────────────────────────────────────┘
                                     │
                                     ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                            AzureDevOpsViewModel                           │
│  • StateFlows: areaFilter, iterationFilter, projectAreas, projectIterations│
│  • Hierarchical UNDER filter in filteredWorkItems combine pipeline        │
└───────────────┬─────────────────────────────┬─────────────────────────────┘
                │                             │
                ▼                             ▼
┌───────────────────────────────────┐ ┌─────────────────────────────────────┐
│          WorkItemsScreen          │ │   Detail & Create Work Item Sheets  │
│  • Project Header + Hide/Unhide   │ │  • Area & Iteration Section placed  │
│    Filters Toggle Button          │ │    before Description section       │
│  • Collapsible Filter Section:    │ │  • Opens HierarchyTreePickerDialog  │
│    [Assignee] [Area] [Iteration]  │ │    for Area or Iteration selection  │
└───────────────────────────────────┘ └─────────────────────────────────────┘
```

- **Data Model & State**:
  - `ClassificationNodeResponse` / `RawClassificationNode`: Models recursive `children` from the Azure DevOps Classification Nodes REST API, normalized into canonical backslash-delimited paths (`Project\Node\SubNode`).
  - `HierarchyNodeUiModel`: Represents each node in the UI tree with `name`, `fullPath`, `parentBreadcrumb`, `depth`, and `children: List<HierarchyNodeUiModel>`.
  - `CachedOrganizationMetadata`: Extended with `areasByProject: Map<String, List<String>>` and `iterationsByProject: Map<String, List<String>>` for instant startup restoration.
- **Interactive Component & State Mapping**:
  - **Home Filter Toggle & Selection**: Clicking the filter toggle button beside the Project title flips `areFiltersVisible`; selecting an Area or Iteration updates `viewModel.setAreaFilter(path)` or `viewModel.setIterationFilter(path)` and triggers server/local filtering using hierarchical prefix matching.
  - **Detail Page Area/Iteration Editing**: Selecting a new Area or Iteration in `FigmaItemDetailView` updates `currentAreaPath` / `currentIterationPath` and invokes `onUpdateWorkItem(workItem.copy(areaPath = ..., iterationPath = ...))`, which patches `/fields/System.AreaPath` and `/fields/System.IterationPath` on Azure DevOps.
  - **Create New Item Page Area/Iteration Selection**: Defaults `currentAreaPath` and `currentIterationPath` to the selected project root (or active filter path if under the project) and sends `/fields/System.AreaPath` and `/fields/System.IterationPath` in `createWorkItemOnServer`.
