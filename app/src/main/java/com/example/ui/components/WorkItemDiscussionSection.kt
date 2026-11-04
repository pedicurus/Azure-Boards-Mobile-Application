package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.WorkItemComment
import com.example.ui.theme.NeumorphicTeal
import com.example.ui.theme.NeumorphicTextSecondary
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.TextPrimary
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DetailDiscussionSection(
    workItemId: Int,
    fallbackRtlTitle: String,
    comments: List<WorkItemComment>,
    isLoadingComments: Boolean,
    indexedMentionPeople: List<MentionCandidate>,
    commentFieldValue: TextFieldValue,
    onCommentFieldValueChange: (TextFieldValue) -> Unit,
    matchingMentionPeople: List<MentionCandidate>,
    onAddComment: (commentText: String, onResult: (Result<WorkItemComment>) -> Unit) -> Unit,
    onExpandRequest: () -> Unit
) {
    var showAllComments by remember(workItemId) { mutableStateOf(false) }
    var isPostingComment by remember(workItemId) { mutableStateOf(false) }
    var isCommentFocused by remember(workItemId) { mutableStateOf(false) }
    val commentFocusRequester = remember { FocusRequester() }
    val commentBringIntoViewRequester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    val isMentionBarVisible = matchingMentionPeople.isNotEmpty()

    LaunchedEffect(isCommentFocused, isImeVisible, isMentionBarVisible, commentFieldValue.text.length) {
        if (isCommentFocused || isMentionBarVisible) {
            onExpandRequest()
            try {
                delay(45)
                commentBringIntoViewRequester.bringIntoView()
                delay(140)
                commentBringIntoViewRequester.bringIntoView()
                delay(120)
                commentBringIntoViewRequester.bringIntoView()
            } catch (_: Exception) {}
        }
    }

    Spacer(modifier = Modifier.height(22.dp))
    HorizontalDivider(color = SurfaceBorder, thickness = 1.dp)
    Spacer(modifier = Modifier.height(18.dp))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("detail_discussion_header")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Forum,
                contentDescription = null,
                tint = NeumorphicTeal,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Discussion",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            if (comments.isNotEmpty()) {
                Surface(
                    color = Color(0xFFEEF2F8),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                ) {
                    Text(
                        text = comments.size.toString(),
                        color = Color(0xFF0F172A),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }

        if (isLoadingComments) {
            CircularProgressIndicator(
                color = NeumorphicTeal,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(16.dp)
                    .testTag("detail_discussion_loading")
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    val isCommentInputRtl = remember(commentFieldValue.text, fallbackRtlTitle) {
        isAlmostPersian(commentFieldValue.text.ifBlank { fallbackRtlTitle })
    }
    Surface(
        color = Color(0xFFF8FAFC),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (matchingMentionPeople.isNotEmpty()) NeumorphicTeal else SurfaceBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(commentBringIntoViewRequester)
            .testTag("detail_discussion_composer_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (isCommentInputRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                OutlinedTextField(
                    value = commentFieldValue,
                    onValueChange = onCommentFieldValueChange,
                    placeholder = {
                        Text(
                            text = "Add a comment... Type @ to mention people",
                            color = NeumorphicTextSecondary,
                            fontSize = 13.sp,
                            textAlign = if (isCommentInputRtl) TextAlign.Right else TextAlign.Left,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textAlign = if (isCommentInputRtl) TextAlign.Right else TextAlign.Left,
                        textDirection = if (isCommentInputRtl) TextDirection.Rtl else TextDirection.Ltr
                    ),
                    minLines = 2,
                    maxLines = 5,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeumorphicTeal,
                        unfocusedBorderColor = SurfaceBorder,
                        focusedContainerColor = SurfaceCard,
                        unfocusedContainerColor = SurfaceCard,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = NeumorphicTeal
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(commentFocusRequester)
                        .onFocusChanged { focusState ->
                            isCommentFocused = focusState.isFocused
                            if (focusState.isFocused) {
                                onExpandRequest()
                            }
                        }
                        .testTag("detail_comment_input")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    onClick = {
                        val text = commentFieldValue.text
                        val cursor = commentFieldValue.selection.end.coerceIn(0, text.length)
                        val needsSpace = cursor > 0 && !text[cursor - 1].isWhitespace()
                        val prefix = if (needsSpace) " @" else "@"
                        val updatedText = buildString(text.length + prefix.length) {
                            append(text, 0, cursor)
                            append(prefix)
                            append(text, cursor, text.length)
                        }
                        val newCursor = cursor + prefix.length
                        onCommentFieldValueChange(
                            TextFieldValue(
                                text = updatedText,
                                selection = TextRange(newCursor)
                            )
                        )
                        commentFocusRequester.requestFocus()
                    },
                    color = SurfaceCard,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SurfaceBorder),
                    modifier = Modifier.testTag("detail_insert_mention_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AlternateEmail,
                            contentDescription = "Mention someone",
                            tint = NeumorphicTeal,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Mention",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                val canSubmitComment = commentFieldValue.text.trim().isNotEmpty() && !isPostingComment
                Button(
                    onClick = {
                        val textToSubmit = commentFieldValue.text.trim()
                        if (textToSubmit.isNotEmpty() && !isPostingComment) {
                            isPostingComment = true
                            onCommentFieldValueChange(TextFieldValue(""))
                            onAddComment(textToSubmit) {
                                isPostingComment = false
                            }
                        }
                    },
                    enabled = canSubmitComment,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(
                        horizontal = 14.dp,
                        vertical = 7.dp
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeumorphicTeal,
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFE2E8F0),
                        disabledContentColor = Color(0xFF94A3B8)
                    ),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("detail_post_comment_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Post comment",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Comment",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    val knownPeopleNames = remember(indexedMentionPeople) {
        indexedMentionPeople.map { it.displayName }
    }

    if (comments.isEmpty()) {
        Surface(
            color = Color(0xFFF8FAFC),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, SurfaceBorder),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("detail_discussion_empty")
        ) {
            Text(
                text = if (isLoadingComments) "Loading discussion..." else "No comments yet. Start the discussion above.",
                color = NeumorphicTextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }
    } else {
        val displayedComments = remember(comments, showAllComments) {
            if (showAllComments) comments else comments.take(3)
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("detail_discussion_section")
        ) {
            displayedComments.forEach { comment ->
                DiscussionCommentCard(
                    comment = comment,
                    knownPeopleNames = knownPeopleNames
                )
            }

            if (!showAllComments && comments.size > 3) {
                OutlinedButton(
                    onClick = { showAllComments = true },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SurfaceBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFFF8FAFC),
                        contentColor = NeumorphicTeal
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("detail_more_comments_button")
                ) {
                    Text(
                        text = "More Comments (${comments.size - 3})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
