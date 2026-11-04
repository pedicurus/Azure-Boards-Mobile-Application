package com.example.ui.components

/**
 * Work Item UI components are modularized into dedicated files within `com.example.ui.components`:
 * - [WorkItemSharedAtoms.kt]: Shared badges, Persian RTL detection, hierarchy breadcrumbs,
 *   [ClassificationHierarchySection], [WorkItemCard], [ParentWorkItemStatusRow], [ChildWorkItemStatusRow]
 * - [TwoStageBottomSheetContainer.kt]: [AnimatedTwoStageSheetContainer] (zero-recomposition layout/draw-phase sheet drag & upper-zone swipe dismiss)
 * - [WorkItemDetailSheet.kt]: [WorkItemDetailSheet] and [FigmaItemDetailView]
 * - [WorkItemCreateSheet.kt]: [WorkItemCreateSheet] and [FigmaCreateWorkItemView]
 * - [WorkItemHierarchySection.kt]: [DetailParentAndChildrenSection] and parent/child dialogs
 * - [WorkItemDiscussionSection.kt]: [DetailDiscussionSection] comment composer and list
 * - [WorkItemMentionComponents.kt]: `@mention` people indexing, token parser, and [FloatingMentionSuggestionsBar]
 * - [WorkItemPickerDialogs.kt]: [AssigneeSearchDialog], [TagSearchAndAddDialog], and [HierarchyTreePickerDialog]
 */
