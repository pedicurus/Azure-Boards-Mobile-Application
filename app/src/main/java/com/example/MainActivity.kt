package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.di.AppContainer
import com.example.notifications.NotificationHelper
import com.example.ui.navigation.AzureBoardsNavHost
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NeumorphicBg
import com.example.ui.viewmodel.ConnectionViewModel
import com.example.ui.viewmodel.NotificationsViewModel
import com.example.ui.viewmodel.WorkItemsViewModel

class MainActivity : ComponentActivity() {

    private val appContainer: AppContainer by lazy {
        AppContainer.getInstance(applicationContext)
    }

    private val viewModelFactory by lazy {
        appContainer.provideViewModelFactory(application)
    }

    private val connectionViewModel: ConnectionViewModel by viewModels { viewModelFactory }
    private val workItemsViewModel: WorkItemsViewModel by viewModels { viewModelFactory }
    private val notificationsViewModel: NotificationsViewModel by viewModels { viewModelFactory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Touch feature ViewModels so their session callbacks are registered prior to deep-link handling
        connectionViewModel
        workItemsViewModel
        notificationsViewModel
        handleNotificationIntent(intent)
        setContent {
            MyApplicationTheme {
                Surface(
                    color = NeumorphicBg,
                    modifier = Modifier.fillMaxSize()
                ) {
                    AzureBoardsApp(
                        connectionViewModel = connectionViewModel,
                        workItemsViewModel = workItemsViewModel,
                        notificationsViewModel = notificationsViewModel
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val workItemId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_WORK_ITEM_ID, -1)
        if (workItemId > 0) {
            val org = intent.getStringExtra(NotificationHelper.EXTRA_NOTIFICATION_ORG)
            val notifId = intent.getStringExtra(NotificationHelper.EXTRA_NOTIFICATION_ID)
            notificationsViewModel.handleNotificationDeepLink(
                organization = org,
                workItemId = workItemId,
                notificationId = notifId
            )
            intent.removeExtra(NotificationHelper.EXTRA_NOTIFICATION_WORK_ITEM_ID)
        }
    }
}

@Composable
fun AzureBoardsApp(
    connectionViewModel: ConnectionViewModel,
    workItemsViewModel: WorkItemsViewModel,
    notificationsViewModel: NotificationsViewModel
) {
    AzureBoardsNavHost(
        connectionViewModel = connectionViewModel,
        workItemsViewModel = workItemsViewModel,
        notificationsViewModel = notificationsViewModel
    )
}
