package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.WorkItem
import com.example.data.model.WorkItemComment
import com.example.data.repository.AzureDevOpsRepository
import com.example.ui.theme.NeumorphicTeal
import com.example.ui.theme.NeumorphicTextSecondary
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.TextPrimary

@Immutable
internal data class MentionCandidate(
    val userId: String? = null,
    val displayName: String,
    val email: String?,
    val lowerName: String,
    val lowerEmail: String?,
    val initial: String
)

@Immutable
internal data class MentionToken(
    val startIndex: Int,
    val endIndex: Int,
    val query: String
)

internal fun buildMentionPeopleIndex(
    availableAssignees: List<String>,
    allWorkItems: List<WorkItem>,
    comments: List<WorkItemComment>
): List<MentionCandidate> {
    data class MutablePersonEntry(
        var userId: String?,
        val displayName: String,
        var email: String?
    )
    val byLowerName = LinkedHashMap<String, MutablePersonEntry>()

    fun register(rawId: String?, rawName: String?, rawEmail: String?, rawAvatar: String? = null) {
        val cleanName = rawName?.trim().orEmpty()
        if (cleanName.isBlank() ||
            cleanName.equals("Unassigned", ignoreCase = true) ||
            cleanName.equals("All", ignoreCase = true) ||
            cleanName.equals("You", ignoreCase = true)
        ) {
            return
        }
        val cleanId = rawId?.trim()?.takeIf { it.isNotBlank() }
        val cleanEmail = rawEmail?.trim()?.takeIf { it.isNotBlank() }
        AzureDevOpsRepository.registerKnownIdentity(
            id = cleanId,
            displayName = cleanName,
            email = cleanEmail,
            avatarUrl = rawAvatar
        )

        val key = cleanName.lowercase()
        val existing = byLowerName[key]
        if (existing == null) {
            val resolvedRecord = AzureDevOpsRepository.resolveIdentityRecord(cleanName)
            byLowerName[key] = MutablePersonEntry(
                userId = cleanId ?: resolvedRecord?.id,
                displayName = cleanName,
                email = cleanEmail ?: resolvedRecord?.email
            )
        } else {
            if (existing.userId == null && cleanId != null) {
                existing.userId = cleanId
            }
            if (existing.email == null && cleanEmail != null) {
                existing.email = cleanEmail
            }
        }
    }

    for (name in availableAssignees) {
        register(null, name, null)
    }
    for (item in allWorkItems) {
        register(item.assignedToId, item.assignedToName, item.assignedToEmail, item.assignedToAvatar)
    }
    for (comment in comments) {
        register(comment.authorId, comment.authorName, comment.authorEmail, comment.authorAvatar)
    }

    return byLowerName.values
        .map { entry ->
            val firstChar = entry.displayName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            MentionCandidate(
                userId = entry.userId,
                displayName = entry.displayName,
                email = entry.email,
                lowerName = entry.displayName.lowercase(),
                lowerEmail = entry.email?.lowercase(),
                initial = firstChar
            )
        }
        .sortedBy { it.lowerName }
}

internal fun extractActiveMentionToken(textFieldValue: TextFieldValue): MentionToken? {
    val text = textFieldValue.text
    if (text.isEmpty()) return null
    val cursor = textFieldValue.selection.end.coerceIn(0, text.length)
    if (cursor == 0) return null

    var idx = cursor - 1
    while (idx >= 0) {
        val ch = text[idx]
        if (ch == '\n' || ch == '\r' || ch == '>') {
            return null
        }
        if (ch == '@') {
            val validBoundary = idx == 0 ||
                text[idx - 1].isWhitespace() ||
                text[idx - 1] in listOf('(', '[', '{', ',', ';', ':', '-', '/')
            if (!validBoundary) return null

            val rawQuery = text.substring(idx + 1, cursor)
            if (rawQuery.contains('>')) return null
            val query = rawQuery.removePrefix("<")
            if (query.contains("  ") || query.length > 35) return null
            return MentionToken(
                startIndex = idx,
                endIndex = cursor,
                query = query
            )
        }
        idx--
    }
    return null
}

internal fun formatCommentWithMentions(
    text: String,
    knownPeopleNames: List<String>
): AnnotatedString {
    if (!text.contains('@')) {
        return AnnotatedString(text)
    }
    val mentionStyle = SpanStyle(
        color = NeumorphicTeal,
        fontWeight = FontWeight.Bold,
        background = NeumorphicTeal.copy(alpha = 0.12f)
    )
    val sortedNames = knownPeopleNames.sortedByDescending { it.length }

    return buildAnnotatedString {
        var searchIndex = 0
        while (searchIndex < text.length) {
            val atIndex = text.indexOf('@', searchIndex)
            if (atIndex < 0) {
                append(text.substring(searchIndex))
                break
            }

            if (atIndex > searchIndex) {
                append(text.substring(searchIndex, atIndex))
            }

            if (atIndex + 1 < text.length && text[atIndex + 1] == '<') {
                val closeBracketIndex = text.indexOf('>', atIndex + 2)
                val newlineIndex = text.indexOf('\n', atIndex + 2)
                if (closeBracketIndex > atIndex + 2 && (newlineIndex < 0 || closeBracketIndex < newlineIndex)) {
                    val innerToken = text.substring(atIndex + 2, closeBracketIndex).trim()
                    val resolvedName = AzureDevOpsRepository
                        .resolveIdentityRecord(innerToken)
                        ?.displayName
                        ?: innerToken.removePrefix("@").trim()
                    withStyle(mentionStyle) {
                        append("@$resolvedName")
                    }
                    searchIndex = closeBracketIndex + 1
                    continue
                }
            }

            val afterAt = text.substring(atIndex + 1)
            val matchedKnownName = sortedNames.firstOrNull { candidateName ->
                afterAt.startsWith(candidateName, ignoreCase = true) &&
                    (afterAt.length == candidateName.length ||
                        !afterAt[candidateName.length].isLetterOrDigit())
            }

            if (matchedKnownName != null) {
                val actualSlice = text.substring(atIndex, atIndex + 1 + matchedKnownName.length)
                withStyle(mentionStyle) {
                    append(actualSlice)
                }
                searchIndex = atIndex + 1 + matchedKnownName.length
            } else {
                var tokenEnd = atIndex + 1
                while (tokenEnd < text.length &&
                    (text[tokenEnd].isLetterOrDigit() || text[tokenEnd] in listOf('_', '.', '-'))
                ) {
                    tokenEnd++
                }
                if (tokenEnd > atIndex + 1) {
                    withStyle(mentionStyle) {
                        append(text.substring(atIndex, tokenEnd))
                    }
                    searchIndex = tokenEnd
                } else {
                    append('@')
                    searchIndex = atIndex + 1
                }
            }
        }
    }
}

@Composable
internal fun DiscussionCommentCard(
    comment: WorkItemComment,
    knownPeopleNames: List<String>,
    modifier: Modifier = Modifier
) {
    val isRtl = remember(comment.text) { isAlmostPersian(comment.text) }
    val initial = remember(comment.authorName) {
        comment.authorName.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "U"
    }
    val annotatedBody = remember(comment.text, knownPeopleNames) {
        formatCommentWithMentions(comment.text, knownPeopleNames)
    }

    Surface(
        color = Color(0xFFF8FAFC),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SurfaceBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("discussion_comment_card_${comment.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(NeumorphicTeal.copy(alpha = 0.14f))
                    ) {
                        Text(
                            text = initial,
                            color = NeumorphicTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = comment.authorName,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = comment.formattedDate,
                    color = NeumorphicTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            CompositionLocalProvider(
                LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                Text(
                    text = annotatedBody,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Start,
                    style = TextStyle(
                        textDirection = if (isRtl) TextDirection.Rtl else TextDirection.Ltr
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("discussion_comment_text_${comment.id}")
                )
            }
        }
    }
}

@Composable
internal fun FloatingMentionSuggestionsBar(
    activeMentionToken: MentionToken?,
    matchingMentionPeople: List<MentionCandidate>,
    onSelectCandidate: (MentionCandidate) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = activeMentionToken != null && matchingMentionPeople.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Surface(
            color = SurfaceCard,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 8.dp,
            border = BorderStroke(1.5.dp, NeumorphicTeal),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("floating_mention_suggestions_bar")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AlternateEmail,
                            contentDescription = null,
                            tint = NeumorphicTeal,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (activeMentionToken?.query?.trim().isNullOrEmpty()) {
                                "Mention someone"
                            } else {
                                "Matching \"@${activeMentionToken?.query?.trim()}\""
                            },
                            color = NeumorphicTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Tap to insert",
                        color = NeumorphicTextSecondary,
                        fontSize = 11.sp
                    )
                }
                HorizontalDivider(
                    color = SurfaceBorder,
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 2.dp)
                ) {
                    matchingMentionPeople.forEach { candidate ->
                        Surface(
                            onClick = { onSelectCandidate(candidate) },
                            color = Color(0xFFF1F5F9),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, NeumorphicTeal.copy(alpha = 0.35f)),
                            modifier = Modifier.testTag("floating_mention_chip_${candidate.displayName}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(NeumorphicTeal.copy(alpha = 0.16f))
                                ) {
                                    Text(
                                        text = candidate.initial,
                                        color = NeumorphicTeal,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = candidate.displayName,
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (!candidate.email.isNullOrBlank()) {
                                        Text(
                                            text = candidate.email,
                                            color = NeumorphicTextSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
