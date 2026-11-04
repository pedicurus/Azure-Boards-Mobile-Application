# Credential & Session Lifecycle Hardening Plan

## 1. Overview & Objectives
This focused security hardening pass strengthens Personal Access Token (PAT) storage, in-memory client caching, session sign-out cleanup, and UI input/clipboard handling without altering existing UI layouts or application architecture.

```
┌───────────────────────────────────────────────────────────────────────────┐
│                     PAT Input & Clipboard Hardening                       │
│  • Non-saveable Compose state (excluded from OS SavedInstanceState)       │
│  • Password keyboard type + autoCorrect disabled                          │
│  • Copy shortcut gated by visibility toggle + EXTRA_IS_SENSITIVE flag     │
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │
                                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                  Keystore Encryption & Downgrade Guard                    │
│  • AndroidKeyStore provider present ──► Hardware AES-256-GCM only         │
│    (Keygen/cipher error logs & returns null; no silent prefs fallback)    │
│  • AndroidKeyStore absent (JVM test) ──► Software AES-256-GCM fallback    │
│  • Decryption fallback only if fallback secret already exists in prefs    │
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │
                                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                 Session Eviction & Complete Sign-Out                      │
│  • Retrofit service cache keyed by SHA-256(pat.trim()), never raw PAT     │
│  • Account removal evicts service instance + org project cache            │
│  • Full sign-out wipes prefs, fallback key, local disk/memory stores,     │
│    service cache, identity registry, and periodic background sync         │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Planned Security Enhancements

### A. Prevent Silent Keystore Downgrade (`EncryptedTokenStore`)
- **Strict Hardware Keystore Enforcement on Device**:
  - In `getOrCreateSecretKey()`, check whether the `"AndroidKeyStore"` provider is available by attempting `KeyStore.getInstance("AndroidKeyStore")`.
  - Only invoke `getOrCreateSoftwareFallbackKey()` when `"AndroidKeyStore"` throws `KeyStoreException` (such as in local JVM/Robolectric unit test environments where the provider is unavailable).
  - If `"AndroidKeyStore"` is available on the device but key generation, key retrieval, or cipher encryption fails, log the error via `android.util.Log.e` and return `null` from `encryptToken(plainToken: String): String?` (updating callers in `AzureAccountRepository` to skip persisting entries when encryption returns `null`), rather than silently falling back to a software key stored in `SharedPreferences`.
- **Safe Decryption Fallback**:
  - In `decryptToken()`, only attempt fallback decryption in the inner `catch` block if `keyPrefs.contains(KEY_FALLBACK_SECRET)` is `true`. Never generate a new fallback key during decryption.
- **Fallback Key Wipe on Logout**:
  - Add `clearFallbackKey()` to clear `keyPrefs` and reset `cachedSecretKey = null`.

### B. Complete Logout & Memory Eviction (`AzureDevOpsClient` & `AzureAccountRepository`)
- **Hashed Service Cache Keys**:
  - Update `AzureDevOpsClient` so `serviceCache` is keyed by the lowercase SHA-256 hex digest of `pat.trim()` rather than the raw PAT string.
  - Add `evictService(pat: String)` to remove the corresponding SHA-256 entry from `serviceCache`, and `clearServiceCache()` to clear all cached `AzureDevOpsService` instances.
- **Per-Organization Account Removal Cleanup**:
  - In `AzureAccountRepository.removeOrganizationAccount(organization)`, read the target organization's PAT before removing it, invoke `AzureDevOpsClient.evictService(pat)`, remove the organization's cached projects entry (`"$KEY_CACHED_PROJECTS_PREFIX$normOrg"` and `"$KEY_CACHED_PROJECTS_PREFIX${normOrg.lowercase()}"`) from `prefs` and `cachedProjectsByOrgMemory`, and clear its local cache.
- **Complete Sign-Out Cleanup (`clearCredentials()`)**:
  - Retain an application `Context` reference in `AzureAccountRepository` and perform a comprehensive sign-out reset:
    1. Clear all account and project keys from `prefs` (`prefs.edit().clear().apply()`) before marking explicit disconnect (`setExplicitlyDisconnected(true)`).
    2. Call `tokenStore.clearFallbackKey()`.
    3. Call `localStore.clearAllCaches()`.
    4. Call `AzureDevOpsClient.clearServiceCache()`.
    5. Call `AzureIdentityRegistry.clearKnownIdentitiesForTesting()`.
    6. Call `AzureSyncWorker.cancelPeriodicSync(context)` (and expose a `com.example.sync.AzureSyncWorker` alias/delegate if referenced by external callers).

### C. PAT Input & Clipboard Hardening (`ConnectionScreen`, `AzureBoardsSettingsDialog`, `ClipboardUtils`)
- **Prevent OS Bundle Serialization of PAT**:
  - In `ConnectionScreen`, replace `var patInput by rememberSaveable { mutableStateOf(initialPat) }` with `var patInput by remember(initialPat) { mutableStateOf(initialPat) }` so the plaintext PAT is never written into the Android `onSaveInstanceState` `Bundle`.
- **Hardened Password Keyboard Options**:
  - In both `ConnectionScreen` and `AzureBoardsSettingsDialog`, configure the PAT `OutlinedTextField` with `KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done)`.
- **Sensitive Clipboard Flag & Copy Gating**:
  - In `ClipboardUtils`, add `isSensitive: Boolean = false` to `writeClipboardText(context, text, label, isSensitive)`. When `isSensitive` is `true`, attach a `PersistableBundle` to `clip.description.extras` with `ClipDescription.EXTRA_IS_SENSITIVE` (`"android.content.extra.IS_SENSITIVE"`) set to `true`.
  - In both `ConnectionScreen` and `AzureBoardsSettingsDialog`, only allow the `Key.Copy` / `Ctrl+C` shortcut on the PAT field when visibility is toggled on (`patVisible` / `editorPatVisible` is `true`), and pass `isSensitive = true` to `writeClipboardText`.
