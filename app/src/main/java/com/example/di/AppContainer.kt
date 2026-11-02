package com.example.di

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.local.EncryptedTokenStore
import com.example.data.repository.AzureDevOpsRepository
import com.example.ui.viewmodel.ConnectionViewModel
import com.example.ui.viewmodel.NotificationsViewModel
import com.example.ui.viewmodel.WorkItemsViewModel

/**
 * Manual dependency injection container managing application-scoped singletons
 * (`EncryptedTokenStore`, `AzureDevOpsRepository`, `SessionManager`) and `ViewModelProvider.Factory`
 * creation with zero annotation-processing overhead.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    val encryptedTokenStore: EncryptedTokenStore by lazy {
        EncryptedTokenStore(appContext)
    }

    val repository: AzureDevOpsRepository by lazy {
        AzureDevOpsRepository(appContext, encryptedTokenStore)
    }

    val sessionManager: SessionManager by lazy {
        SessionManager(repository)
    }

    fun provideViewModelFactory(application: Application): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return when {
                    modelClass.isAssignableFrom(ConnectionViewModel::class.java) -> {
                        ConnectionViewModel(application, sessionManager) as T
                    }
                    modelClass.isAssignableFrom(WorkItemsViewModel::class.java) -> {
                        WorkItemsViewModel(application, sessionManager) as T
                    }
                    modelClass.isAssignableFrom(NotificationsViewModel::class.java) -> {
                        NotificationsViewModel(application, sessionManager) as T
                    }
                    else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun getInstance(context: Context): AppContainer {
            return instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
        }

        fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
