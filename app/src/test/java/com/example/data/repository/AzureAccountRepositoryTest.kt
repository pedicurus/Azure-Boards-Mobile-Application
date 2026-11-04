package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.EncryptedTokenStore
import com.example.data.model.OrganizationAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AzureAccountRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: AzureAccountRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = AzureAccountRepository(context, EncryptedTokenStore(context))
        repository.clearCredentials()
    }

    @Test
    fun extractOrganization_acceptsValidNamesAndUrls() {
        assertEquals("contoso", repository.extractOrganization("contoso"))
        assertEquals("contoso-123", repository.extractOrganization("  contoso-123  "))
        assertEquals("contoso", repository.extractOrganization("https://dev.azure.com/contoso"))
        assertEquals("contoso", repository.extractOrganization("https://dev.azure.com/contoso/"))
        assertEquals("contoso", repository.extractOrganization("https://dev.azure.com/contoso/MyProject/_workitems"))
        assertEquals("contoso", repository.extractOrganization("http://DEV.AZURE.COM/contoso/Project"))
        assertEquals("my-org", repository.extractOrganization("https://my-org.visualstudio.com"))
        assertEquals("my-org", repository.extractOrganization("https://my-org.visualstudio.com/DefaultCollection"))
        assertEquals("MY-ORG", repository.extractOrganization("MY-ORG.VISUALSTUDIO.COM/"))
    }

    @Test
    fun extractOrganization_stripsQueryAndFragmentParameters() {
        assertEquals("org", repository.extractOrganization("org?foo=bar"))
        assertEquals("org", repository.extractOrganization("org#frag"))
        assertEquals("org", repository.extractOrganization("org?foo=bar#frag"))
        assertEquals("contoso", repository.extractOrganization("https://dev.azure.com/contoso?api-version=7.0#section"))
        assertEquals("contoso", repository.extractOrganization("https://contoso.visualstudio.com?foo=bar#frag"))
    }

    @Test
    fun extractOrganization_rejectsMaliciousAndMalformedInputs() {
        // Cross-pattern URL host confusion
        assertEquals("", repository.extractOrganization("https://dev.azure.com/evil.visualstudio.com"))
        assertEquals("", repository.extractOrganization("dev.azure.com/evil.visualstudio.com"))

        // Path traversal & slashes
        assertEquals("", repository.extractOrganization("../org"))
        assertEquals("", repository.extractOrganization("/../org"))
        assertEquals("", repository.extractOrganization("org/../evil"))
        assertEquals("", repository.extractOrganization("https://dev.azure.com/../org"))
        assertEquals("", repository.extractOrganization("plain-org/extra-segment"))

        // Dots, empty subdomains, leading hyphens, invalid characters
        assertEquals("", repository.extractOrganization(".visualstudio.com"))
        assertEquals("", repository.extractOrganization("https://.visualstudio.com"))
        assertEquals("", repository.extractOrganization("https://sub.org.visualstudio.com"))
        assertEquals("", repository.extractOrganization("org.with.dots"))
        assertEquals("", repository.extractOrganization("-leading-hyphen"))
        assertEquals("", repository.extractOrganization("org_with_underscore"))
        assertEquals("", repository.extractOrganization("org space"))
        assertEquals("", repository.extractOrganization("https://dev.azure.com/"))
        assertEquals("", repository.extractOrganization(""))
        assertEquals("", repository.extractOrganization("   "))

        // Length boundary checks (max 63 characters: 1 + 62)
        val maxValidOrg = "a" + "b".repeat(62)
        val tooLongOrg = "a" + "b".repeat(63)
        assertEquals(maxValidOrg, repository.extractOrganization(maxValidOrg))
        assertEquals("", repository.extractOrganization(tooLongOrg))
    }

    @Test
    fun saveOrganizationAccount_ignoresInvalidOrganizationInput() {
        repository.saveOrganizationAccount(
            OrganizationAccount(
                organization = "https://dev.azure.com/evil.visualstudio.com",
                pat = "valid-pat-token",
                isRemembered = true
            )
        )
        repository.saveOrganizationAccount(
            OrganizationAccount(
                organization = "../org",
                pat = "valid-pat-token",
                isRemembered = true
            )
        )

        assertTrue(repository.getSavedOrganizations().isEmpty())
    }
}
