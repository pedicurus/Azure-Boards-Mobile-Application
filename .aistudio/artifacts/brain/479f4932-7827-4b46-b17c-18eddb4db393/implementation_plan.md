# 3-Character Type-to-Search Autocomplete for Assignee & Tags

Replace long static dropdown menus for **Assignee** and **Tags** with responsive 3-character type-to-search autocomplete selectors across the **Home**, **Detail**, and **Create New Item** screens so users never have to scroll through all existing options.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following product and UX decisions were confirmed during interactive planning:

- **Confirmed Decision 1 — Scope Across Screens**: The 3-character type-to-search autocomplete applies to **Assignee** and **Tags** across the **Home page**, **Work Item Detail sheet**, and **Create New Item sheet**.
- **Confirmed Decision 2 — Assignee Default Options**: Static options (`All` and `Unassigned` on the Home filter, and `Unassigned` on Detail / Create pages) remain visible immediately before typing, while individual people appear only after typing **at least 3 characters**.
- **Tags Autocomplete & Custom Tag Creation**: In the Detail and Create New Item sheets, tapping the `+` tag button opens a search input dialog/popover where typing **3+ characters** displays matching existing project tags to select, and also lets the user add the typed text as a new tag if it does not already exist.

---

## 1. Overview & Core Concept

- **What It Does**: Converts Assignee and Tag selection from full-list dropdown menus into lightweight, search-driven pickers that wait until the user types at least 3 characters before displaying matching suggestions.
- **Target Audience / Persona**: Azure Boards users working in projects with dozens or hundreds of team members and tags who want fast, clutter-free filtering and editing.
- **Key Value**: Eliminates visual overload and long scrolling lists while keeping quick reset options (`All`, `Unassigned`) one tap away.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Home Page Assignee Filter**:
   - Tapping the Assignee filter chip opens a clean search-enabled picker.
   - Immediately visible at the top: a search input field (`Type 3+ characters to search assignee...`) and the quick-select options **`All`** and **`Unassigned`** (plus the currently selected assignee if one is active).
   - Once the user types **3 or more characters**, matching assignees from the project (case-insensitive substring match, supporting both English and Persian/RTL text) appear below for one-tap selection.

2. **Detail & Create Page Assignee Selector**:
   - Tapping the Assignee pill in the Detail sheet or Create New Item sheet opens the Assignee search picker.
   - Immediately visible: the search input field and **`Unassigned`** (plus the current assignee if assigned).
   - Typing **3+ characters** reveals matching project assignees to assign to the work item.

3. **Detail & Create Page Tags Selector**:
   - Existing tags on the work item remain displayed as removable pill chips (`×`).
   - Tapping the `+` button opens a unified **Add / Search Tag** dialog with a text field (`Type at least 3 characters...`).
   - Before 3 characters are typed, no long list of existing tags is shown—only a subtle prompt indicating to type at least 3 characters.
   - Once **3+ characters** are typed:
     - Matching existing project tags (excluding already-attached tags, or showing a checkmark if selected) are listed as selectable suggestions.
     - Tapping any suggested tag immediately attaches it to the work item.
     - If the typed tag doesn't match an existing tag (or the user taps **Add**), the user can still add the custom tag directly.

### Visual Identity & Theme
- **Aesthetic Direction**: Clean, high-contrast neumorphic card surfaces consistent with the app's existing light slate & purple/teal palette.
- **Color Palette & Mood**:
  - Primary Surface: `#FFFFFF` (`SurfaceCard`) with `#E4E9F2` / `#CBD5E1` borders
  - Home Accent: `#6A5AE0` (`AccentPurple`)
  - Detail / Create Accent: `#00677E` (`NeumorphicTeal`)
  - Muted Helper Text: `#64748B` (`TextMuted` / `NeumorphicTextSecondary`)
- **Typography & RTL Support**: Automatic bidirectional text alignment (`isAlmostPersian`) so typing Persian or English names/tags aligns and filters naturally.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Unified Search-and-Create Flow for Tags**
  - *Chosen Approach*: Merge the old full-list tag dropdown and separate "+ New Tag" dialog into a single search-first dialog/sheet triggered by the `+` button.
  - *Why*: Users no longer have to scroll past all existing tags to reach "+ New Tag" or hunt for an existing tag visually; typing 3+ characters both filters existing tags and allows creating a new tag in one step.
- **Decision 2: Preserving Quick Reset Options (`All` / `Unassigned`)**
  - *Chosen Approach*: Always show `All` and `Unassigned` on the Home filter (and `Unassigned` on Detail/Create) even when the search query has fewer than 3 characters.
  - *Why*: Clearing an assignee filter or unassigning a work item is a frequent action that shouldn't require typing.

---

## 4. Technical Architecture & Data Strategy

### Architecture & Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         WorkItemViewModel State                         │
│     availableAssignees: List<String>  │  availableTags: List<String>    │
└───────────────────┬──────────────────────────────────┬──────────────────┘
                    │                                  │
        ┌───────────▼───────────┐          ┌───────────▼───────────┐
        │   WorkItemsScreen     │          │  WorkItemComponents   │
        │     (Home Page)       │          │ (Detail & Create UI)  │
        └───────────┬───────────┘          └───────────┬───────────┘
                    │                                  │
                    ▼                                  ▼
┌────────────────────────────────────┐  ┌─────────────────────────────────┐
│      HomeAssigneeDropdown          │  │  Assignee & Tag Search Pickers  │
│  • Input: searchQuery              │  │  • Assignee: "Unassigned" +     │
│  • Static: "All", "Unassigned"     │  │    matches when query.len >= 3  │
│  • Dynamic: matches when len >= 3  │  │  • Tags: matches when len >= 3  │
│                                    │  │    + direct custom tag creation │
└────────────────────────────────────┘  └─────────────────────────────────┘
```

### Interactive Component & State Mapping

1. **3-Character Threshold Logic**:
   - Let `trimmedQuery = query.trim()`.
   - When `trimmedQuery.length < 3`:
     - **Assignee Picker**: Shows only the static options (`All` and `Unassigned` on Home; `Unassigned` on Detail/Create) plus a helper hint `"Type at least 3 characters to search people"`.
     - **Tag Picker**: Shows a helper hint `"Type at least 3 characters to see matching tags"` and hides the full tag catalog.
   - When `trimmedQuery.length >= 3`:
     - Filters `availableAssignees` / `projectTagOptions` using `option.contains(trimmedQuery, ignoreCase = true)`.
     - Displays matching options in a bounded scrollable list; if no existing option matches, shows `"No matching assignees found"` (or for tags, allows adding `trimmedQuery` as a new tag).
