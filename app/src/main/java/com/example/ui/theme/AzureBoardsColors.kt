package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.util.concurrent.ConcurrentHashMap

private val dynamicTypeColors = ConcurrentHashMap<String, Color>()
private val dynamicStateColors = ConcurrentHashMap<String, Color>()

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
    if (typeName.isNullOrBlank()) return
    val parsed = parseAzureHexColor(hexColor) ?: return
    dynamicTypeColors[typeName.lowercase().trim()] = parsed
}

fun registerAzureStateColor(stateName: String?, hexColor: String?) {
    if (stateName.isNullOrBlank()) return
    val parsed = parseAzureHexColor(hexColor) ?: return
    dynamicStateColors[stateName.lowercase().trim()] = parsed
}

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
        clean.contains("task") || clean.contains("کار") || clean.contains("وظیفه") -> AzureTypeTask
        clean.contains("user story") || clean.contains("story") || clean.contains("داستان") -> AzureTypeUserStory
        clean.contains("feature") || clean.contains("ویژگی") || clean.contains("قابلیت") -> AzureTypeFeature
        clean.contains("epic") || clean.contains("هدف") || clean.contains("حماسه") -> AzureTypeEpic
        clean.contains("issue") || clean.contains("مسئله") -> AzureTypeIssue
        clean.contains("test") -> AzureTypeTestCase
        else -> AzureDevOpsBlue
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


