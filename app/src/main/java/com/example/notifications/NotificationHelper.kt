package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.AppNotificationItem
import com.example.data.model.NotificationCategory

object NotificationHelper {

    const val CHANNEL_ASSIGNED = "azure_boards_assigned_channel"
    const val CHANNEL_MENTIONED = "azure_boards_mentions_channel"

    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    const val EXTRA_NOTIFICATION_ORG = "extra_notification_org"
    const val EXTRA_NOTIFICATION_WORK_ITEM_ID = "extra_notification_work_item_id"
    const val EXTRA_OPEN_NOTIFICATIONS_SCREEN = "extra_open_notifications_screen"

    private const val BACKGROUND_SYNC_JOB_ID = 44019
    private const val PERIODIC_INTERVAL_MS = 15L * 60L * 1000L // 15 minutes

    fun ensureNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val assignedChannel = NotificationChannel(
                CHANNEL_ASSIGNED,
                context.getString(R.string.notif_channel_assigned_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_assigned_desc)
                enableVibration(true)
            }

            val mentionedChannel = NotificationChannel(
                CHANNEL_MENTIONED,
                context.getString(R.string.notif_channel_mentioned_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_mentioned_desc)
                enableVibration(true)
            }

            manager.createNotificationChannel(assignedChannel)
            manager.createNotificationChannel(mentionedChannel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun postSystemNotification(context: Context, item: AppNotificationItem) {
        ensureNotificationChannels(context)
        if (!hasNotificationPermission(context)) return

        val isMention = item.category == NotificationCategory.MENTIONED
        val channelId = if (isMention) CHANNEL_MENTIONED else CHANNEL_ASSIGNED

        val title = if (isMention) {
            val actor = item.actorName?.takeIf { it.isNotBlank() } ?: "Someone"
            "$actor mentioned you in #${item.workItemId}"
        } else {
            "Assigned to you: #${item.workItemId}"
        }

        val body = if (isMention) {
            val snippet = item.messageSnippet?.takeIf { it.isNotBlank() } ?: item.workItemTitle
            "${item.workItemTitle} — $snippet"
        } else {
            val metaParts = listOfNotNull(
                item.workItemType.takeIf { it.isNotBlank() },
                item.workItemState.takeIf { it.isNotBlank() },
                item.teamProject?.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            if (metaParts.isNotEmpty()) {
                "${item.workItemTitle}\n$metaParts"
            } else {
                item.workItemTitle
            }
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_ID, item.id)
            putExtra(EXTRA_NOTIFICATION_ORG, item.organization)
            putExtra(EXTRA_NOTIFICATION_WORK_ITEM_ID, item.workItemId)
        }

        val requestCode = item.id.hashCode()
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            tapIntent,
            pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_azure_devops)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(
                if (isMention) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(requestCode, notification)
        }
    }

    fun schedulePeriodicBackgroundSync(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return
            val existing = scheduler.allPendingJobs.any { it.id == BACKGROUND_SYNC_JOB_ID }
            if (existing) return

            val component = ComponentName(context, AzureNotificationJobService::class.java)
            val jobInfo = JobInfo.Builder(BACKGROUND_SYNC_JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIODIC_INTERVAL_MS)
                .build()
            scheduler.schedule(jobInfo)
        }
    }
}
