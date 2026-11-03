package com.example.data.repository

import androidx.core.text.HtmlCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Domain utility and registry managing user identity matching, `@mention` detection,
 * Markdown/HTML comment serialization, and comment timestamp formatting for Azure DevOps discussions.
 */
object AzureIdentityRegistry {

    data class KnownIdentityRecord(
        val id: String? = null,
        val displayName: String,
        val email: String? = null,
        val avatarUrl: String? = null
    )

    private val knownIdentitiesByLowerName = ConcurrentHashMap<String, KnownIdentityRecord>()
    private val knownIdentitiesByLowerEmail = ConcurrentHashMap<String, KnownIdentityRecord>()
    private val knownIdentitiesByLowerId = ConcurrentHashMap<String, KnownIdentityRecord>()

    private val BRACKETED_MENTION_REGEX = Regex("@<([^>\\r\\n]+)>")
    private val VSS_MENTION_ANCHOR_REGEX = Regex(
        "<a\\b[^>]*data-vss-mention\\s*=\\s*\"(?:version:\\d+\\.\\d+,)?([^\"]+)\"[^>]*>(.*?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun registerKnownIdentity(
        id: String?,
        displayName: String?,
        email: String?,
        avatarUrl: String? = null
    ) {
        val cleanName = displayName?.trim()?.removePrefix("@")?.trim().orEmpty()
        val cleanId = id?.trim()?.takeIf { it.isNotEmpty() }
        val cleanEmail = email?.trim()?.takeIf { it.isNotEmpty() }
        val cleanAvatar = avatarUrl?.trim()?.takeIf { it.isNotEmpty() }

        if (cleanName.isBlank() ||
            cleanName.equals("Unassigned", ignoreCase = true) ||
            cleanName.equals("All", ignoreCase = true) ||
            cleanName.equals("You", ignoreCase = true)
        ) {
            return
        }

        val nameKey = cleanName.lowercase()
        val existingByName = knownIdentitiesByLowerName[nameKey]
        val existingById = cleanId?.lowercase()?.let { knownIdentitiesByLowerId[it] }
        val existingByEmail = cleanEmail?.lowercase()?.let { knownIdentitiesByLowerEmail[it] }

        val merged = KnownIdentityRecord(
            id = cleanId ?: existingByName?.id ?: existingByEmail?.id ?: existingById?.id,
            displayName = cleanName,
            email = cleanEmail ?: existingByName?.email ?: existingById?.email ?: existingByEmail?.email,
            avatarUrl = cleanAvatar ?: existingByName?.avatarUrl ?: existingById?.avatarUrl ?: existingByEmail?.avatarUrl
        )

        knownIdentitiesByLowerName[nameKey] = merged
        merged.id?.lowercase()?.let { knownIdentitiesByLowerId[it] = merged }
        merged.email?.lowercase()?.let { knownIdentitiesByLowerEmail[it] = merged }
    }

    fun clearKnownIdentitiesForTesting() {
        knownIdentitiesByLowerName.clear()
        knownIdentitiesByLowerEmail.clear()
        knownIdentitiesByLowerId.clear()
    }

    fun resolveIdentityRecord(token: String): KnownIdentityRecord? {
        val clean = token.trim().removePrefix("@").removePrefix("<").removeSuffix(">").trim()
        if (clean.isEmpty()) return null
        val lower = clean.lowercase()

        knownIdentitiesByLowerId[lower]?.let { return it }
        knownIdentitiesByLowerName[lower]?.let { return it }
        knownIdentitiesByLowerEmail[lower]?.let { return it }

        if (clean.contains('<') && clean.endsWith('>')) {
            val namePart = clean.substringBefore('<').trim().lowercase()
            val emailPart = clean.substringAfter('<').substringBeforeLast('>').trim().lowercase()
            knownIdentitiesByLowerEmail[emailPart]?.let { return it }
            knownIdentitiesByLowerName[namePart]?.let { return it }
        }

        val emailPrefixMatch = knownIdentitiesByLowerEmail.entries.firstOrNull { (emailKey, _) ->
            emailKey.substringBefore('@') == lower
        }?.value
        if (emailPrefixMatch != null) return emailPrefixMatch

        return null
    }

    fun serializeCommentForAzureMarkdown(rawComment: String): String {
        val trimmed = rawComment.trim()
        if (trimmed.isEmpty()) return ""

        var processed = BRACKETED_MENTION_REGEX.replace(trimmed) { match ->
            val inner = match.groupValues[1].trim()
            val resolved = resolveIdentityRecord(inner)
            when {
                !resolved?.id.isNullOrBlank() -> "@<${resolved!!.id}>"
                resolved != null -> "@<${resolved.displayName}>"
                else -> "@<$inner>"
            }
        }

        if (knownIdentitiesByLowerName.isNotEmpty()) {
            val sortedRecords = knownIdentitiesByLowerName.values
                .distinctBy { it.displayName.lowercase() }
                .sortedByDescending { it.displayName.length }

            for (record in sortedRecords) {
                val escapedName = Regex.escape(record.displayName)
                val unbracketedRegex = Regex("(?<![\\w@<])@$escapedName(?![\\w>])", RegexOption.IGNORE_CASE)
                processed = unbracketedRegex.replace(processed) {
                    if (!record.id.isNullOrBlank()) "@<${record.id}>" else "@<${record.displayName}>"
                }
            }
        }

        return processed
    }

    fun serializeCommentForHtmlFallback(rawComment: String): String {
        val trimmed = rawComment.trim()
        if (trimmed.isEmpty()) return ""

        data class PlaceholderMention(val placeholder: String, val htmlReplacement: String)
        val placeholders = mutableListOf<PlaceholderMention>()
        var counter = 0

        val withPlaceholders = BRACKETED_MENTION_REGEX.replace(trimmed) { match ->
            val inner = match.groupValues[1].trim()
            val resolved = resolveIdentityRecord(inner)
            val tokenKey = "__AZ_MENTION_PLACEHOLDER_${counter++}__"
            val displayName = (resolved?.displayName ?: inner)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            val htmlTag = if (!resolved?.id.isNullOrBlank()) {
                "<a href=\"#\" data-vss-mention=\"version:2.0,${resolved!!.id}\">@$displayName</a>"
            } else {
                "@&lt;$displayName&gt;"
            }
            placeholders.add(PlaceholderMention(tokenKey, htmlTag))
            tokenKey
        }

        var escaped = withPlaceholders
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br/>")

        for (item in placeholders) {
            escaped = escaped.replace(item.placeholder, item.htmlReplacement)
        }
        return escaped
    }

    fun normalizeCommentTextFromAzure(
        rawText: String?,
        renderedText: String? = null
    ): String? {
        if (rawText.isNullOrBlank() && renderedText.isNullOrBlank()) return null

        val mentionMapByLowerId = mutableMapOf<String, String>()
        fun harvestVssAnchors(source: String?) {
            if (source.isNullOrBlank()) return
            for (match in VSS_MENTION_ANCHOR_REGEX.findAll(source)) {
                val rawId = match.groupValues[1].trim()
                val innerHtml = match.groupValues[2]
                val cleanName = HtmlCompat.fromHtml(innerHtml, HtmlCompat.FROM_HTML_MODE_COMPACT)
                    .toString()
                    .trim()
                    .removePrefix("@")
                    .trim()
                if (rawId.isNotEmpty() && cleanName.isNotEmpty()) {
                    mentionMapByLowerId[rawId.lowercase()] = cleanName
                    registerKnownIdentity(id = rawId, displayName = cleanName, email = null)
                }
            }
        }
        harvestVssAnchors(renderedText)
        harvestVssAnchors(rawText)

        val baseText = rawText?.takeIf { it.isNotBlank() } ?: renderedText.orEmpty()

        var working = VSS_MENTION_ANCHOR_REGEX.replace(baseText) { match ->
            val rawId = match.groupValues[1].trim()
            val innerHtml = match.groupValues[2]
            val cleanName = HtmlCompat.fromHtml(innerHtml, HtmlCompat.FROM_HTML_MODE_COMPACT)
                .toString()
                .trim()
                .removePrefix("@")
                .trim()
            val resolvedName = cleanName.ifEmpty {
                mentionMapByLowerId[rawId.lowercase()]
                    ?: resolveIdentityRecord(rawId)?.displayName
                    ?: rawId
            }
            "@<$resolvedName>"
        }

        working = BRACKETED_MENTION_REGEX.replace(working) { match ->
            val inner = match.groupValues[1].trim()
            val fromRendered = mentionMapByLowerId[inner.lowercase()]
            val fromDirectory = resolveIdentityRecord(inner)?.displayName
            val finalName = fromRendered ?: fromDirectory ?: inner
            "@<$finalName>"
        }

        val mentionPlaceholders = mutableListOf<Pair<String, String>>()
        var pIndex = 0
        val protectedText = BRACKETED_MENTION_REGEX.replace(working) { match ->
            val key = "__AZ_MNT_TOKEN_${pIndex++}__"
            mentionPlaceholders.add(key to match.value)
            key
        }

        val looksLikeHtml = protectedText.contains("<p", ignoreCase = true) ||
            protectedText.contains("<div", ignoreCase = true) ||
            protectedText.contains("<br", ignoreCase = true) ||
            protectedText.contains("<span", ignoreCase = true) ||
            protectedText.contains("<a ", ignoreCase = true) ||
            protectedText.contains("&lt;", ignoreCase = true) ||
            protectedText.contains("&gt;", ignoreCase = true) ||
            protectedText.contains("&amp;", ignoreCase = true) ||
            protectedText.contains("&nbsp;", ignoreCase = true)

        var cleaned = if (looksLikeHtml) {
            HtmlCompat.fromHtml(protectedText, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
        } else {
            protectedText.trim()
        }

        for ((key, originalMention) in mentionPlaceholders) {
            cleaned = cleaned.replace(key, originalMention)
        }

        cleaned = BRACKETED_MENTION_REGEX.replace(cleaned) { match ->
            val inner = match.groupValues[1].trim()
            val fromRendered = mentionMapByLowerId[inner.lowercase()]
            val fromDirectory = resolveIdentityRecord(inner)?.displayName
            val finalName = fromRendered ?: fromDirectory ?: inner
            "@<$finalName>"
        }

        return cleaned.takeIf { it.isNotBlank() }
    }

    fun formatCommentTimestamp(isoDate: String?): String {
        if (isoDate.isNullOrBlank()) return ""
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
        var parsed: Date? = null
        for (pattern in patterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US)
                parser.timeZone = TimeZone.getTimeZone("UTC")
                parsed = parser.parse(isoDate)
                if (parsed != null) break
            } catch (_: Exception) {
            }
        }
        if (parsed == null) return isoDate.take(19).replace("T", " ")
        val outFormat = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())
        return outFormat.format(parsed)
    }

    fun matchesUserIdentity(
        candidateName: String?,
        candidateEmail: String?,
        effectiveDisplayName: String?,
        detectedEmail: String?
    ): Boolean {
        val cName = candidateName?.trim()?.lowercase().orEmpty()
        val cEmail = candidateEmail?.trim()?.lowercase().orEmpty()
        val targetName = effectiveDisplayName?.trim()?.lowercase().orEmpty()
        val targetEmail = detectedEmail?.trim()?.lowercase().orEmpty()

        if (cName.isEmpty() && cEmail.isEmpty()) return false
        if (cName == "unassigned") return false

        if (targetEmail.isNotEmpty()) {
            if (cEmail.isNotEmpty() && cEmail == targetEmail) return true
            if (cName.isNotEmpty() && cName == targetEmail) return true
            val emailPrefix = targetEmail.substringBefore('@')
            if (emailPrefix.length >= 3 && cEmail.startsWith("$emailPrefix@")) return true
        }
        if (targetName.isNotEmpty() && cName.isNotEmpty()) {
            if (cName == targetName) return true
            val tokens = cName.split(' ', '-', '_', '.', '(', ')', '<', '>').filter { it.isNotEmpty() }
            if (targetName.length >= 3 && (tokens.contains(targetName) || cName.startsWith("$targetName "))) {
                return true
            }
        }
        return false
    }

    fun commentMentionsIdentity(
        commentText: String,
        effectiveDisplayName: String?,
        detectedEmail: String?,
        detectedUserId: String? = null
    ): Boolean {
        val lowerText = commentText.lowercase()
        val targetName = effectiveDisplayName?.trim()?.lowercase().orEmpty()
        val targetEmail = detectedEmail?.trim()?.lowercase().orEmpty()
        val targetId = detectedUserId?.trim()?.lowercase().orEmpty()

        if (targetId.isNotEmpty()) {
            if (lowerText.contains("@<$targetId>") ||
                lowerText.contains(targetId) ||
                (lowerText.contains("data-vss-mention") && lowerText.contains(targetId))
            ) {
                return true
            }
        }

        if (targetName.isNotEmpty()) {
            if (lowerText.contains("@<$targetName>") ||
                lowerText.contains("@$targetName") ||
                (lowerText.contains("data-vss-mention") && lowerText.contains(targetName)) ||
                lowerText.contains("@ $targetName")
            ) {
                return true
            }
            val nameParts = targetName.split(' ', '-', '_', '.').map { it.trim() }.filter { it.length >= 3 }
            for (part in nameParts) {
                if (lowerText.contains("@<$part>") ||
                    lowerText.contains("@$part") ||
                    Regex("(?<![\\w@])@<?${Regex.escape(part)}>?(?![\\w])").containsMatchIn(lowerText)
                ) {
                    return true
                }
            }
            if (lowerText.contains(targetName) && (lowerText.contains("@") || lowerText.contains("mention") || lowerText.contains("<a "))) {
                return true
            }
        }
        if (targetEmail.isNotEmpty()) {
            if (lowerText.contains("@<$targetEmail>") || lowerText.contains("@$targetEmail") || lowerText.contains(targetEmail)) {
                return true
            }
            val emailPrefix = targetEmail.substringBefore('@').trim()
            if (emailPrefix.length >= 3) {
                if (lowerText.contains("@<$emailPrefix>") ||
                    lowerText.contains("@$emailPrefix") ||
                    Regex("(?<![\\w@])@<?${Regex.escape(emailPrefix)}>?(?![\\w])").containsMatchIn(lowerText) ||
                    (lowerText.contains(emailPrefix) && (lowerText.contains("@") || lowerText.contains("mention")))
                ) {
                    return true
                }
            }
        }
        return false
    }
}
