package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

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
 */
fun writeClipboardText(context: Context, text: String, label: String = "Azure Boards") {
    if (text.isEmpty()) return
    val systemClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    systemClipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}
