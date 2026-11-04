# Network & Input Sanitization Hardening Plan

This focused security hardening pass prevents sensitive HTTP request/response metadata from being logged in release builds and enforces strict organization name sanitization and validation to block path traversal, query/fragment injection, and malformed URL confusion.

### User Review & Critical Decisions

> [!IMPORTANT]
> All requirements from Task 2 are fully specified and preserve existing UI flows and architecture:
> - **Release HTTP Logging Suppression**: `HttpLoggingInterceptor` is restricted strictly to debug builds (`BuildConfig.DEBUG == true`).
> - **Strict Organization Name Sanitization**: `extractOrganization(input)` strips protocols and slashes, parses `dev.azure.com/` and `.visualstudio.com` hosts without cross-pattern confusion, strips query/fragment strings, and validates the final candidate against `^[a-zA-Z0-9][a-zA-Z0-9-]{0,62}$`.

---

### 1. Overview & Core Concept

- **What It Does**: Eliminates production log exposure of network traffic and ensures every organization identifier entering the repository layer is strictly validated against Azure DevOps organization naming rules before constructing API URLs or cache keys.
- **Target Audience / Persona**: Mobile users connecting to enterprise Azure DevOps organizations who require strong transport metadata privacy and protection against malformed or maliciously crafted organization URLs.
- **Key Value**: Prevents accidental leakage of request paths/headers in system logs on production devices and neutralizes URL/path injection vectors (`../`, `?`, `#`, or spoofed `.visualstudio.com` path segments).

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  - When a user enters an organization name or full URL (`https://dev.azure.com/contoso`, `https://contoso.visualstudio.com/`, or `contoso`) in the Connection Screen or Settings Dialog, the app transparently normalizes and validates it.
  - If a malformed or malicious input (e.g., `https://dev.azure.com/evil.visualstudio.com`, `../org`, `org?foo=bar`, `org#frag`, or special characters) is supplied, `extractOrganization` safely rejects or sanitizes it according to the strict rules, preventing invalid network calls.
- **Visual Identity & Theme**: No visual or layout changes; all existing Material 3 screens, dialogs, and typography remain untouched.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Conditional HTTP Logging via `BuildConfig.DEBUG`**
  - *Chosen Approach*: Confirm `buildFeatures { buildConfig = true }` in the app Gradle configuration and only attach `HttpLoggingInterceptor` (with `Level.BASIC`) to `AzureDevOpsClient.baseOkHttpClient` when `com.example.BuildConfig.DEBUG` is `true`.
  - *Why*: Completely avoids interceptor overhead and prevents URLs, organization names, and status codes from being emitted to Android `logcat` in release APKs.
- **Decision 2: Strict Host-Aware Parsing & Regex Allowlisting in `extractOrganization`**
  - *Chosen Approach*:
    1. Trim whitespace and slashes, strip leading `https://` or `http://` (case-insensitive), strip query/fragment strings, and trim leading/trailing slashes again.
    2. If the remaining string starts with `dev.azure.com/` (case-insensitive), extract the first path segment (`substringAfter("/").substringBefore("/")`).
    3. Otherwise, if the host segment (`substringBefore("/")`) ends with `.visualstudio.com` (case-insensitive), extract the subdomain immediately preceding `.visualstudio.com` (`host.substring(0, host.length - ".visualstudio.com".length)`).
    4. Strip any residual query (`?`) or fragment (`#`) components (`substringBefore("?").substringBefore("#")`).
    5. Validate the resulting candidate against `Regex("^[a-zA-Z0-9][a-zA-Z0-9-]{0,62}$")`; return `""` if it does not match.
  - *Why*: Evaluating `dev.azure.com/` before checking the host for `.visualstudio.com` and validating against the strict alphanumeric-and-hyphen regex guarantees that inputs like `https://dev.azure.com/evil.visualstudio.com` or `../org` cannot bypass sanitization or inject dots/slashes into API paths.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Unit Test Verification (`AzureAccountRepositoryTest`)**:
  - Dedicated test suite verifying valid inputs (`contoso`, `https://dev.azure.com/contoso-123/project`, `https://my-org.visualstudio.com/DefaultCollection`, `org?foo=bar`, `org#frag`) and rejecting malicious/malformed inputs (`https://dev.azure.com/evil.visualstudio.com`, `../org`, `.visualstudio.com`, `-leading-hyphen`, over-length >63 chars, embedded dots/slashes).
- **Documentation Sync**:
  - Update `README.md` to reflect the release logging guard and strict organization input validation.
