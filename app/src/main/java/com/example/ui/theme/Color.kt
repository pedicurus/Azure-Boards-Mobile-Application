package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Brand Colors
val AzureDevOpsBlue = Color(0xFF0078D4) // Official Azure DevOps Brand Blue
val NeumorphicTeal = Color(0xFF0078D4) // Official Azure DevOps Brand Blue
val NeumorphicTealLight = Color(0xFF2B88D8) // Light Azure DevOps Blue
val NeumorphicBg = Color(0xFFEEF2F5) // Crisp light-grey base background
val NeumorphicTextSecondary = Color(0xFF5E6F8F) // Soft slate-blue typography

// Neutrals & Surface Accents
val SurfaceCard = Color(0xFFFFFFFF)
val SurfaceBorder = Color(0xFFE2E8F0)
val TextPrimary = Color(0xFF0F172A)
val CardHighlight = Color(0xFFF8FAFC)

// Official Azure Boards Work Item Type Colors
// Ref: Azure DevOps official design system work item type palette
val AzureTypeBug = Color(0xFFCC293D) // Official Azure DevOps Bug Red
val AzureTypeTask = Color(0xFFF2CB1D) // Official Azure DevOps Task Yellow / Gold
val AzureTypeUserStory = Color(0xFF009CCC) // Official Azure DevOps User Story Cyan / Blue
val AzureTypeFeature = Color(0xFF773B93) // Official Azure DevOps Feature Purple / Violet
val AzureTypeEpic = Color(0xFFE06A3B) // Official Azure DevOps Epic Orange
val AzureTypeIssue = Color(0xFFB4009E) // Official Azure DevOps Issue Plum / Magenta
val AzureTypeTestCase = Color(0xFF008272) // Official Azure DevOps Test Case Teal

// Backward compatibility aliases
val TypeBug = AzureTypeBug
val TypeTask = AzureTypeTask
val TypeUserStory = AzureTypeUserStory
val TypeFeature = AzureTypeFeature
val TypeEpic = AzureTypeEpic
val TypeIssue = AzureTypeIssue

// Official Azure Boards State Category Colors
// Ref: Azure DevOps 4 core state categories (Proposed, In Progress, Resolved, Completed)
val AzureStateProposed = Color(0xFF6E7C87) // Official Azure DevOps Proposed / New (Gray / Slate)
val AzureStateActive = Color(0xFF007ACC) // Official Azure DevOps Active / In Progress (Azure Blue)
val AzureStateResolved = Color(0xFF28A745) // Official Azure DevOps Resolved (Green)
val AzureStateClosed = Color(0xFF339933) // Official Azure DevOps Closed / Done / Completed (Green)
val AzureStateRemoved = Color(0xFF8A8886) // Official Azure DevOps Removed (Dark Gray)

// Backward compatibility aliases
val StateNew = AzureStateProposed
val StateActive = AzureStateActive
val StateResolved = AzureStateResolved
val StateClosed = AzureStateClosed
val StateDone = AzureStateClosed
