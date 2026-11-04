package com.example.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Reads plain text from Android's system ClipboardManager without using deprecated Compose LocalClipboardManager.
 */
fun readClipboardText(context: Context): String? {
    val systemClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = systemClipboard?.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    val text = clip.getItemAt(0)?.coerceToText(context)?.toString()
    return text?.takeIf { it.isNotEmpty() }
}

/**
 * Writes plain text to Android's system ClipboardManager without using deprecated Compose LocalClipboardManager.
 * When [isSensitive] is `true`, marks the [ClipDescription] with [ClipDescription.EXTRA_IS_SENSITIVE] so the
 * Android system masks the copied content in clipboard previews.
 */
fun writeClipboardText(
    context: Context,
    text: String,
    label: String = "Azure Boards",
    isSensitive: Boolean = false
) {
    if (text.isEmpty()) return
    val systemClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, text)
    if (isSensitive) {
        val sensitiveKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            "android.content.extra.IS_SENSITIVE"
        }
        clip.description.extras = PersistableBundle().apply {
            putBoolean(sensitiveKey, true)
        }
    }
    systemClipboard.setPrimaryClip(clip)
}
