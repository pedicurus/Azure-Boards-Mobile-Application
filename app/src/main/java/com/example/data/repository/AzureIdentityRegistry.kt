package com.example.data.repository

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Domain utility and registry managing user identity matching, `@mention` detection,
 * and comment timestamp formatting for Azure DevOps discussions.
 */
object AzureIdentityRegistry {

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
