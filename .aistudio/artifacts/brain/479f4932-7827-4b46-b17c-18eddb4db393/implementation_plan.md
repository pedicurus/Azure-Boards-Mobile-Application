# Multi-Organization Login, Credential Management & Default Organization/Project Settings

Design a modern Azure DevOps Login page with a "Remember and save" option, support multiple organizations with full management in Settings, enforce a single Default Organization and a single Default Project in Settings, scope projects strictly to their parent organization, and hide dropdown arrows whenever only a single organization or project exists.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following product and UX decisions were confirmed and govern this implementation:

- **One Default Organization & One Default Project**: In Settings, when multiple organizations exist, the user selects which organization is their **Default Organization**, and then selects which project under that Default Organization is their **Default Project**. This guarantees the app always has **one Default Organization** and **one Default Project** that open automatically on startup.
- **Switching Organizations & Projects on Home**: On the Home screen, users can switch the currently viewed organization by tapping the `{Organization}` header (when 2+ organizations exist) and switch the currently viewed project by tapping the `{Project}` header (when 2+ projects exist under the viewed organization).
- **Settings Organization & PAT Management**: Inside Settings, users can add new organizations, edit an organization's Personal Access Token (PAT) or name/URL, remove organizations, and set the Default Organization and its Default Project.
- **Unchecked "Remember and Save" Behavior**: Logging in with the Remember checkbox unchecked keeps the organization and PAT in memory for the current app session only without persisting them to disk.
- **Smart Single-Item Headers (No Meaningless Dropdowns)**: If there is only 1 organization, no dropdown icon is shown beside `{Organization}`. If there is only 1 project under the current organization, no dropdown icon is shown beside `{Project}`.

---

## 1. Overview & Core Concept

- **What It Does**: Provides a dedicated Azure DevOps Login screen where users enter an Organization name or URL and a Personal Access Token (PAT), with a checkbox to remember and save their credentials. Once connected, users can add and manage multiple organizations in Settings, designate a single **Default Organization** and a single **Default Project** (under that default organization) for startup, and browse work items with projects strictly scoped to the selected organization.
- **Target Audience / Persona**: Developers and project managers working across one or more Azure DevOps organizations who want predictable startup defaults and clean credential management.
- **Key Value**: Guarantees a deterministic startup state (one Default Organization + one Default Project), eliminates cross-organization project clutter, and removes redundant dropdown icons when an organization or project list has only one item.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Login Page**:
     - Displays the Azure DevOps blue emblem, title, and a clean login card over the `#FFFFFF` to `#DFE4F1` gradient background.
     - **Organization Name or URL Field**: Accepts either a short organization name (e.g., `Pedicurus`) or a full URL (`https://dev.azure.com/Pedicurus`).
     - **Personal Access Token (PAT) Field**: Password-masked input with a visibility toggle icon.
     - **"Remember and save credentials" Checkbox**: Checked by default. When checked, credentials are saved to local storage upon successful connection; when unchecked, credentials are held in session memory only.
  2. **Home Screen Header (`{Organization}`) & Project Header (`{Project}`)**:
     - **Organization Header**: Shows the Azure DevOps blue icon followed by `{Organization}`.
       - When `organizations.size > 1`: Renders a dropdown arrow icon next to the organization name; tapping opens a dropdown menu to switch the active organization on the Home screen.
       - When `organizations.size <= 1`: Hides the dropdown arrow icon and disables the dropdown menu.
     - **Project Header**: Shows `{Project}` above the Work Item Types bar, displaying only projects belonging to the currently viewed organization.
       - When `availableProjects.size > 1`: Renders the circular dropdown arrow icon next to the project name; tapping opens the project switcher menu.
       - When `availableProjects.size <= 1`: Hides the dropdown arrow icon and disables the dropdown menu.
  3. **Settings Dialog (Default Organization, Default Project & Credential Management)**:
     - **Default Organization & Credential Management Section**:
       - Lists all connected/saved organizations.
       - If multiple organizations exist, users tap an organization (or its default radio/check selector) to set it as the **Default Organization** (marked with a `"Default"` badge/checkmark). If only one organization exists, it is automatically the Default Organization.
       - Each organization row includes actions to **Edit PAT / URL** (pencil/key icon) or **Remove** (delete icon), plus an **"+ Add Organization"** button to add another organization with Organization name/URL, PAT, and "Remember and save" checkbox.
     - **Default Project Section (Scoped to the Default Organization)**:
       - Displays the projects belonging to the selected **Default Organization**.
       - Users select which project under the Default Organization is their **Default Project** (if the Default Organization has only one project, it is automatically selected as the Default Project).
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Clean, high-contrast Material 3 cards matching the Home and Detail screens.
  - *Color Palette & Mood*: `#FFFFFF` to `#DFE4F1` vertical background gradient, `#FFFFFF` card surfaces with `#DCE1EF` borders, Azure DevOps Blue (`#0078D4`) brand emblem, and `#613BE7` / `#0078D4` interactive accents.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Single Default Organization + Single Default Project Hierarchy**
  - *Chosen Approach*: Store one global `defaultOrganization` and one global `defaultProject` (which belongs to `defaultOrganization`). In Settings, selecting a different Default Organization immediately updates the Default Project list in Settings to show that organization's projects (auto-selecting its first project if none was chosen yet).
  - *Why*: Ensures there is always exactly one unambiguous Default Organization and one Default Project that load when the user opens the app.
- **Decision 2: Strict Per-Organization Project Scoping**
  - *Chosen Approach*: Cache and resolve project lists strictly per organization (`projectsByOrganization[orgName.lowercase()]`) from the Azure DevOps Projects API (`/_apis/projects`) and that organization's work items.
  - *Why*: Guarantees that switching organizations on the Home screen or selecting a Default Organization in Settings shows only the projects under that specific organization.
- **Decision 3: Session-Only vs. Persisted Organization Accounts**
  - *Chosen Approach*: Maintain an in-memory session account list alongside persisted accounts in `SharedPreferences`. Accounts added with "Remember and save" checked are written to `SharedPreferences`; accounts added with it unchecked live only in the current session.
  - *Why*: Lets users log in temporarily without leaving credentials on disk while supporting full multi-organization persistence when checked.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌──────────────────────────────────────────────────────────────────────┐
│                         AzureDevOpsRepository                        │
│  ├─ Saved Org Accounts (SharedPreferences)                           │
│  │    └─ OrganizationAccount(organization, pat, isRemembered)        │
│  ├─ Single Default Organization: pref_default_organization           │
│  ├─ Single Default Project:      pref_default_project                │
│  └─ Per-Org Cached Projects:     pref_cached_projects_<org_lower>    │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│                         AzureDevOpsViewModel                         │
│  ├─ organizations: StateFlow<List<OrganizationAccount>>              │
│  ├─ selectedOrganization: StateFlow<String> (Currently viewed org)   │
│  ├─ defaultOrganization: StateFlow<String?> (Single default org)     │
│  ├─ defaultProject: StateFlow<String?> (Single default project)      │
│  ├─ defaultOrgProjects: StateFlow<List<String>> (For Settings UI)    │
│  ├─ availableProjects: StateFlow<List<String>> (For viewed org)      │
│  ├─ connect / addOrganization(orgInput, patInput, remember)          │
│  ├─ switchOrganization(orgName) (Switches viewed org on Home)        │
│  ├─ setDefaultOrganization(orgName) (Sets default org & its project) │
│  ├─ setDefaultProject(projectName) (Sets default project)            │
│  ├─ updateOrganizationCredentials(oldOrg, newOrg, newPat, remember)  │
│  └─ removeOrganization(orgName)                                      │
└───────────────┬──────────────────────────────────────┬───────────────┘
                │                                      │
                ▼                                      ▼
┌───────────────────────────────┐      ┌───────────────────────────────┐
│        ConnectionScreen       │      │        WorkItemsScreen        │
│  ├─ Org Name / URL Input      │      │  ├─ Header: [Icon] {Org} [▾?] │
│  ├─ PAT Input (Show/Hide)     │      │  │   (▾ only if orgs.size > 1)│
│  ├─ [x] Remember & Save       │      │  ├─ Project: {Project} [▾?]   │
│  └─ Sign In / Connect Button  │      │  │   (▾ only if projs.size>1) │
└───────────────────────────────┘      │  └─ Settings Dialog:          │
                                       │      ├─ Default Organization  │
                                       │      │   (Edit PAT / Remove / │
                                       │      │    + Add Organization) │
                                       │      └─ Default Project       │
                                       │          (Under Default Org)  │
                                       └───────────────────────────────┘
```

- **Data Model & State**:
  - `OrganizationAccount(val organization: String, val pat: String, val isRemembered: Boolean = true)`
  - `defaultOrganization: String?`: The single default organization loaded on startup.
  - `defaultProject: String?`: The single default project (belonging to `defaultOrganization`) loaded on startup.
  - `projectsByOrg: Map<String, List<String>>`: Maps each organization name (case-insensitive) to its own list of projects.
- **Interactive Component & State Mapping**:
  - **Login Submit (`onConnect(org, pat, remember)`)**: Validates credentials against Azure DevOps API. On success, adds/updates the organization account (persisting to disk only if `remember == true`), sets it as the default organization/project if none exist yet, and navigates to `WorkItemsScreen`.
  - **Organization Header (`WorkItemsScreen`)**: Shows `{Organization}`. Only renders the dropdown arrow and opens the organization switcher menu when `organizations.size > 1`.
  - **Project Header (`WorkItemsScreen`)**: Shows `{Project}` for the currently viewed organization. Only renders the dropdown arrow and opens the project switcher menu when `availableProjects.size > 1`.
  - **Settings Dialog**:
    - Allows selecting the **Default Organization** (when `organizations.size > 1`) and managing each organization (Edit PAT/URL, Remove, or `+ Add Organization`).
    - Displays the projects belonging to the **Default Organization** so the user can select their single **Default Project**.
