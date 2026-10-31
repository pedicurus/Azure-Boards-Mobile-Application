package com.example.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.concurrent.ConcurrentHashMap

private val dynamicTypeColors = ConcurrentHashMap<String, Color>()
private val dynamicTypeColorHex = ConcurrentHashMap<String, String>()
private val dynamicTypeIcons = ConcurrentHashMap<String, String>()
private val dynamicStateColors = ConcurrentHashMap<String, Color>()
private val dynamicStateColorHex = ConcurrentHashMap<String, String>()

private fun parseAzureHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val cleaned = hex.trim().removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    return try {
        val argb = if (cleaned.length == 6) {
            ("FF$cleaned").toLong(16)
        } else {
            cleaned.toLong(16)
        }
        Color(argb)
    } catch (e: Exception) {
        null
    }
}

fun registerAzureTypeColor(typeName: String?, hexColor: String?) {
    if (typeName.isNullOrBlank() || hexColor.isNullOrBlank()) return
    val parsed = parseAzureHexColor(hexColor) ?: return
    val key = typeName.lowercase().trim()
    dynamicTypeColors[key] = parsed
    dynamicTypeColorHex[key] = hexColor.trim().removePrefix("#")
}

fun registerAzureTypeIcon(typeName: String?, iconId: String?) {
    if (typeName.isNullOrBlank() || iconId.isNullOrBlank()) return
    val key = typeName.lowercase().trim()
    val cleanIcon = iconId.trim().lowercase()
    dynamicTypeIcons[key] = cleanIcon
}

fun registerAzureStateColor(stateName: String?, hexColor: String?) {
    if (stateName.isNullOrBlank() || hexColor.isNullOrBlank()) return
    val parsed = parseAzureHexColor(hexColor) ?: return
    val key = stateName.lowercase().trim()
    dynamicStateColors[key] = parsed
    dynamicStateColorHex[key] = hexColor.trim().removePrefix("#")
}

fun getRegisteredTypeColorsHex(): Map<String, String> = dynamicTypeColorHex.toMap()
fun getRegisteredTypeIcons(): Map<String, String> = dynamicTypeIcons.toMap()
fun getRegisteredStateColorsHex(): Map<String, String> = dynamicStateColorHex.toMap()

/**
 * Resolves the official Azure Boards Work Item Type color.
 * Supports live colors fetched from Azure DevOps REST API, standard English names, and Persian equivalents.
 */
fun getAzureTypeColor(type: String?): Color {
    if (type.isNullOrBlank()) return AzureDevOpsBlue
    val clean = type.lowercase().trim()
    dynamicTypeColors[clean]?.let { return it }
    return when {
        clean.contains("bug") || clean.contains("باگ") || clean.contains("خطا") -> AzureTypeBug
        clean.contains("user story") || clean.contains("story") || clean.contains("داستان") -> AzureTypeUserStory
        clean.contains("feature") || clean.contains("ویژگی") || clean.contains("قابلیت") -> AzureTypeFeature
        clean.contains("epic") || clean.contains("هدف") || clean.contains("حماسه") -> AzureTypeEpic
        clean.contains("task") || clean.contains("کار") || clean.contains("وظیفه") -> AzureTypeTask
        clean.contains("issue") || clean.contains("مسئله") -> AzureTypeIssue
        clean.contains("test") -> AzureTypeTestCase
        else -> AzureDevOpsBlue
    }
}

/**
 * Resolves the official Azure Boards Work Item Type symbol (icon) defined in the process/project,
 * with fallback to standard Azure Boards symbols for English and Persian work item types.
 */
fun getAzureTypeVectorIcon(type: String?): ImageVector {
    val cleanType = type?.lowercase()?.trim().orEmpty()
    val iconId = dynamicTypeIcons[cleanType]?.lowercase()?.trim()

    if (!iconId.isNullOrBlank()) {
        when (iconId) {
            "icon_crown" -> return Icons.Default.WorkspacePremium
            "icon_trophy" -> return Icons.Default.EmojiEvents
            "icon_book" -> return Icons.AutoMirrored.Filled.MenuBook
            "icon_clipboard" -> return Icons.Default.AssignmentTurnedIn
            "icon_list" -> return Icons.AutoMirrored.Filled.ListAlt
            "icon_insect" -> return Icons.Default.BugReport
            "icon_ribbon" -> return Icons.Default.Bookmark
            "icon_flame" -> return Icons.Default.Whatshot
            "icon_traffic_cone" -> return Icons.Default.ReportProblem
            "icon_chat_bubble" -> return Icons.AutoMirrored.Filled.Chat
            "icon_check_box" -> return Icons.Default.CheckBox
            "icon_code_review", "icon_code_response", "icon_review", "icon_response" -> return Icons.Default.Code
            "icon_test_plan", "icon_test_suite", "icon_test_case", "icon_test_step", "icon_test_parameter" -> return Icons.Default.Science
            "icon_star" -> return Icons.Default.Star
            "icon_head" -> return Icons.Default.Person
            "icon_key" -> return Icons.Default.Key
            "icon_airplane", "icon_parachute" -> return Icons.Default.Flight
            "icon_car" -> return Icons.Default.DirectionsCar
            "icon_diamond" -> return Icons.Default.Diamond
            "icon_asterisk" -> return Icons.Default.AutoAwesome
            "icon_database_storage" -> return Icons.Default.Storage
            "icon_government" -> return Icons.Default.AccountBalance
            "icon_gavel" -> return Icons.Default.Gavel
            "icon_paint_brush" -> return Icons.Default.Brush
            "icon_palette" -> return Icons.Default.Palette
            "icon_gear" -> return Icons.Default.Settings
            "icon_lifecycle" -> return Icons.Default.Loop
            "icon_chart" -> return Icons.Default.BarChart
            "icon_sticky_note" -> return Icons.Default.StickyNote2
            "icon_gift" -> return Icons.Default.CardGiftcard
            "icon_oscilloscope" -> return Icons.Default.Timeline
            "icon_umbrella" -> return Icons.Default.Umbrella
            "icon_heart", "icon_peace" -> return Icons.Default.Favorite
            "icon_lightbulb", "icon_broken_lightbulb" -> return Icons.Default.Lightbulb
            "icon_megaphone" -> return Icons.Default.Campaign
            "icon_alarm_clock" -> return Icons.Default.Alarm
            "icon_shield" -> return Icons.Default.Security
            "icon_flag" -> return Icons.Default.Flag
        }
    }

    return when {
        cleanType.contains("bug") || cleanType.contains("باگ") || cleanType.contains("خطا") -> Icons.Default.BugReport
        cleanType.contains("user story") || cleanType.contains("story") || cleanType.contains("داستان") -> Icons.AutoMirrored.Filled.MenuBook
        cleanType.contains("product backlog") || cleanType.contains("backlog item") || cleanType.contains("requirement") || cleanType.contains("نیازمندی") -> Icons.AutoMirrored.Filled.ListAlt
        cleanType.contains("feature") || cleanType.contains("ویژگی") || cleanType.contains("قابلیت") -> Icons.Default.EmojiEvents
        cleanType.contains("epic") || cleanType.contains("هدف") || cleanType.contains("حماسه") || cleanType.contains("initiative") -> Icons.Default.WorkspacePremium
        cleanType.contains("task") || cleanType.contains("کار") || cleanType.contains("وظیفه") || cleanType.contains("اقدام") -> Icons.Default.AssignmentTurnedIn
        cleanType.contains("impediment") || cleanType.contains("مانع") || cleanType.contains("risk") || cleanType.contains("ریسک") -> Icons.Default.ReportProblem
        cleanType.contains("issue") || cleanType.contains("مسئله") -> Icons.Default.Flag
        cleanType.contains("test") || cleanType.contains("تست") -> Icons.Default.Science
        else -> Icons.Default.Bookmark
    }
}

/**
 * Helper to ensure any foreground color on a solid background has strong contrast.
 */
fun getContrastingTextForSolidBg(bgColor: Color): Color {
    return if (bgColor.luminance() > 0.38f) {
        Color(0xFF111827) // High-contrast dark ink for light/medium backgrounds (#B2B2B2, #F2CB1D, etc.)
    } else {
        Color.White
    }
}

/**
 * Helper to darken a state/type color when used as text on a light tinted badge background,
 * preserving its hue while guaranteeing WCAG AA/AAA readability.
 */
private fun ensureReadableTextOnLightPill(baseColor: Color): Color {
    val maxChannel = maxOf(baseColor.red, baseColor.green, baseColor.blue)
    val minChannel = minOf(baseColor.red, baseColor.green, baseColor.blue)
    val saturation = if (maxChannel == 0f) 0f else (maxChannel - minChannel) / maxChannel

    // If the color is a light/medium neutral gray (e.g., Azure's #B2B2B2 for Proposed/New states),
    // use a crisp dark slate so it is clearly legible on light gray pills.
    if (saturation < 0.15f && baseColor.luminance() > 0.25f) {
        return Color(0xFF2D3748)
    }

    // If the color is bright (yellow, lime, cyan, light blue, orange), scale down RGB channels
    // so text has strong contrast against the 16% tinted pill background.
    val lum = baseColor.luminance()
    if (lum > 0.26f) {
        val factor = (0.52f / (lum + 0.35f)).coerceIn(0.38f, 0.75f)
        return Color(
            red = (baseColor.red * factor).coerceIn(0f, 1f),
            green = (baseColor.green * factor).coerceIn(0f, 1f),
            blue = (baseColor.blue * factor).coerceIn(0f, 1f),
            alpha = 1f
        )
    }
    return baseColor
}

/**
 * Text color for chips / badges when selected with the type's official solid background.
 */
fun getAzureTypeTextColor(type: String?): Color {
    val bgColor = getAzureTypeColor(type)
    return getContrastingTextForSolidBg(bgColor)
}

/**
 * Resolves the official Azure Boards State category color.
 * Prioritizes live state colors fetched from Azure DevOps process definitions.
 */
fun getAzureStateColor(state: String?): Color {
    if (state.isNullOrBlank()) return AzureDevOpsBlue
    val clean = state.lowercase().trim()
    if (clean == "all") return AzureDevOpsBlue
    dynamicStateColors[clean]?.let { return it }
    return when {
        // Active / In Progress (Azure DevOps Blue)
        clean.contains("progress") || clean.contains("active") || clean.contains("doing") ||
        clean.contains("committed") || clean.contains("انجام") || clean.contains("فعال") ||
        clean.contains("در راه") -> AzureStateActive

        // Completed / Done / Closed (Azure DevOps Green)
        clean.contains("done") || clean.contains("close") || clean.contains("resolved") ||
        clean.contains("completed") || clean.contains("تکمیل") || clean.contains("بسته") || clean.contains("پایان") -> AzureStateClosed

        // Proposed / New / To Do (Azure DevOps Slate/Gray)
        clean.contains("new") || clean.contains("to do") || clean.contains("todo") ||
        clean.contains("proposed") || clean.contains("امروز") || clean.contains("جدید") ||
        clean.contains("اهداف") || clean.contains("کارها") -> AzureStateProposed

        // Removed / Canceled / منتفی
        clean.contains("remove") || clean.contains("منتفی") || clean.contains("لغو") -> AzureStateRemoved
        else -> AzureStateActive
    }
}

/**
 * Text color for state chips when selected with a solid state color background.
 */
fun getAzureStateTextColor(state: String?): Color {
    val bgColor = getAzureStateColor(state)
    return getContrastingTextForSolidBg(bgColor)
}

/**
 * Background color for work item status pill badges, tinted directly from the Azure state color.
 */
fun getAzureStateBadgeBg(state: String?): Color {
    val stateColor = getAzureStateColor(state)
    val maxChannel = maxOf(stateColor.red, stateColor.green, stateColor.blue)
    val minChannel = minOf(stateColor.red, stateColor.green, stateColor.blue)
    val saturation = if (maxChannel == 0f) 0f else (maxChannel - minChannel) / maxChannel
    return if (saturation < 0.15f && stateColor.luminance() > 0.25f) {
        Color(0xFFE8ECF4) // Crisp soft slate-gray pill background for gray states (#B2B2B2)
    } else {
        stateColor.copy(alpha = 0.16f)
    }
}

/**
 * Text color for work item status pill badges, matching the Azure state color with guaranteed contrast.
 */
fun getAzureStateBadgeText(state: String?): Color {
    val stateColor = getAzureStateColor(state)
    return ensureReadableTextOnLightPill(stateColor)
}


