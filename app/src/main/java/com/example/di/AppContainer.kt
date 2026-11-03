package com.example.di

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.local.EncryptedTokenStore
import com.example.data.local.WorkItemLocalStore
import com.example.data.repository.AzureAccountRepository
import com.example.data.repository.AzureClassificationRepository
import com.example.data.repository.AzureCommentsRepository
import com.example.data.repository.AzureDevOpsRepository
import com.example.data.repository.AzureNotificationsRepository
import com.example.data.repository.AzureProcessMetadataRepository
import com.example.data.repository.AzureWorkItemCrudRepository
import com.example.data.repository.AzureWorkItemRepository
import com.example.ui.viewmodel.ConnectionViewModel
import com.example.ui.viewmodel.NotificationsViewModel
import com.example.ui.viewmodel.WorkItemsViewModel

/**
 * Manual dependency injection container managing application-scoped singletons
 * (`EncryptedTokenStore`, specialized domain repositories, `AzureDevOpsRepository`, `SessionManager`)
 * and `ViewModelProvider.Factory` creation with zero annotation-processing overhead.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    val encryptedTokenStore: EncryptedTokenStore by lazy {
        EncryptedTokenStore(appContext)
    }

    val localStore: WorkItemLocalStore by lazy {
        WorkItemLocalStore(appContext)
    }

    val accountRepository: AzureAccountRepository by lazy {
        AzureAccountRepository(appContext, encryptedTokenStore, localStore)
    }

    val classificationRepository: AzureClassificationRepository by lazy {
        AzureClassificationRepository()
    }

    val commentsRepository: AzureCommentsRepository by lazy {
        AzureCommentsRepository()
    }

    val crudRepository: AzureWorkItemCrudRepository by lazy {
        AzureWorkItemCrudRepository()
    }

    val processMetadataRepository: AzureProcessMetadataRepository by lazy {
        AzureProcessMetadataRepository(appContext, localStore)
    }

    val notificationsRepository: AzureNotificationsRepository by lazy {
        AzureNotificationsRepository(appContext, localStore, commentsRepository)
    }

    val workItemRepository: AzureWorkItemRepository by lazy {
        AzureWorkItemRepository(appContext, localStore)
    }

    val repository: AzureDevOpsRepository by lazy {
        AzureDevOpsRepository(
            context = appContext,
            tokenStore = encryptedTokenStore,
            localStore = localStore,
            accountRepository = accountRepository,
            classificationRepository = classificationRepository,
            commentsRepository = commentsRepository,
            crudRepository = crudRepository,
            processMetadataRepository = processMetadataRepository,
            notificationsRepository = notificationsRepository,
            workItemRepository = workItemRepository
        )
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
