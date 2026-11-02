package com.example.ui.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.OrganizationAccount
import com.example.data.model.WorkItem
import com.example.di.AppContainer
import com.example.ui.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeatureViewModelsRefactorTest {

    private lateinit var application: Application
    private lateinit var container: AppContainer
    private lateinit var connectionViewModel: ConnectionViewModel
    private lateinit var workItemsViewModel: WorkItemsViewModel
    private lateinit var notificationsViewModel: NotificationsViewModel

    @Before
    fun setup() {
        AppContainer.resetForTesting()
        application = ApplicationProvider.getApplicationContext()
        container = AppContainer.getInstance(application)
        container.repository.clearCredentials()

        val factory = container.provideViewModelFactory(application)
        connectionViewModel = factory.create(ConnectionViewModel::class.java)
        workItemsViewModel = factory.create(WorkItemsViewModel::class.java)
        notificationsViewModel = factory.create(NotificationsViewModel::class.java)
    }

    @After
    fun tearDown() {
        container.repository.clearCredentials()
        AppContainer.resetForTesting()
    }

    @Test
    fun `feature ViewModels share reactive SessionManager state via AppContainer`() {
        val sampleAccount = OrganizationAccount("contoso", "pat-secret-token", isRemembered = true)
        container.sessionManager.updateOrganizations(listOf(sampleAccount))
        container.sessionManager.updateDefaultOrganization("contoso")
        container.sessionManager.updateConnectionStatus(ConnectionStatus.Connected("contoso"))

        assertEquals(1, connectionViewModel.organizations.value.size)
        assertEquals("contoso", connectionViewModel.defaultOrganization.value)
        assertTrue(connectionViewModel. connectionStatus.value is ConnectionStatus.Connected)

        connectionViewModel.setDefaultProjectForOrg("contoso", "MobileApp")
        assertEquals("MobileApp", connectionViewModel.defaultProject.value)
        assertEquals("MobileApp", workItemsViewModel.selectedProject.value)
    }

    @Test
    fun `WorkItemsViewModel filters and selects work items cleanly`() {
        workItemsViewModel.setSearchQuery("login bug")
        assertEquals("login bug", workItemsViewModel.searchQuery.value)

        val item = WorkItem(
            id = 42,
            title = "Fix login bug",
            workItemType = "Bug",
            state = "Active",
            teamProject = "MobileApp"
        )
        workItemsViewModel.selectWorkItem(item)
        assertEquals(42, workItemsViewModel.selectedWorkItem.value?.id)
        // Selecting a work item automatically clears an active search query
        assertEquals("", workItemsViewModel.searchQuery.value)

        workItemsViewModel.selectWorkItem(null)
        assertNull(workItemsViewModel.selectedWorkItem.value)
    }

    @Test
    fun `ConnectionViewModel disconnect resets shared session and work items state`() {
        val sampleAccount = OrganizationAccount("contoso", "pat-secret-token", isRemembered = true)
        container.sessionManager.updateOrganizations(listOf(sampleAccount))
        container.sessionManager.updateConnectionStatus(ConnectionStatus.Connected("contoso"))

        connectionViewModel.disconnect()

        assertEquals(ConnectionStatus.Disconnected, connectionViewModel.connectionStatus.value)
        assertTrue(connectionViewModel.organizations.value.isEmpty())
        assertTrue(workItemsViewModel.allWorkItems.value.isEmpty())
    }
}
