# Task 1 independent re-review (`defcae0..bfe7487`)

Scope reviewed: the supplied task brief, updated implementation report, and full two-commit net diff. This is a static review; no application source was changed and no commit was made.

## Verdict 1 — Spec compliance: APPROVED

No Critical or Important spec-compliance findings remain.

The implementation persists credentials only as AES-256-GCM ciphertext in private preferences, using an Android Keystore key with the required alias, a fresh random 12-byte IV, and profile-ID AAD. Opening a saved server uses `SessionRepository.open`; it goes to Browser on success and Login only for `LoginRequiredException`. Deleting a profile clears both token and encrypted credential.

The previous renewal integration gap is closed: Browser listing, downloads, and text/image/media previews call `SessionRepository` wrappers, which apply the one-shot 401/403 policy. Upload first performs a safe authenticated read that may renew, then sends the multipart POST exactly once; the POST is never replayed after an ambiguous auth response.

## Verdict 2 — Code quality / security: APPROVED

No Critical or Important code-quality or security findings remain.

The prior password-copy lifetime issue is closed. `SecretStore.put` documents ownership and clears the supplied `CharArray` in `finally`; `SessionRepository.login` passes the original short-lived array and also clears it defensively. Decrypted credential arrays are cleared after renewal. Blocking network work remains on `Dispatchers.IO`, while Compose-owned coroutine scopes / `LaunchedEffect` provide normal lifecycle cancellation.

## Findings

### Critical

None.

### Important

None.

### Minor

1. **Policy tests do not assert that the retry uses the renewed token value**
   - Location: `app/src/test/java/com/j2team/fileserver/core/session/SessionPolicyTest.kt:59-62`.
   - Evidence: `FakeTransport.request(token)` returns the token as the `ApiResult` value, but no test asserts the returned value or records/asserts the tokens passed to the first and second request. A regression that renews once but retries with `old-token` would retain the same status/call-count assertions and pass.
   - Impact: the boundary suite correctly covers 401, 403, 500, and a second 401 with exact call counts, but it does not fully lock down token propagation.
   - Suggested fix: capture request tokens and assert `listOf("old-token", "new-token")` for the 401 and 403 success cases (or assert the successful result contains `"new-token"`).

## Verification of prior findings and required boundaries

- **Old Important: browser bypassed renewal** — closed. `MainActivity.kt:355`, `378`, `418`, and `466-475` use repository session-aware operations; `SessionRepository.kt:50-63` routes safe operations through `authenticated`.
- **Old Important: persistence password copy not cleared** — closed. `EncryptedSecretStore.kt:16,37-39` declares and fulfils the consuming/clearing contract; `SessionRepository.kt:26-30` has no extra copy and clears the caller array as a defense in depth.
- **401/403 only, one retry** — implemented at `SessionModels.kt:36-45`. Tests cover 401 (`SessionPolicyTest.kt:10-16`), 403 (`20-26`), non-auth 500 (`30-36`), and no second re-login after a renewed 401 (`40-46`).
- **Upload safety** — `SessionRepository.kt:69-79` authenticates via a safe list preflight, then delegates one upload without wrapping that POST in the retry policy.
- **Encrypted storage / no plaintext at rest / AAD** — `EncryptedSecretStore.kt:27-39,48-51,81-87` uses AES/GCM/NoPadding, Keystore AES-256, 12-byte random IV, and profile-ID AAD; preferences receive Base64 IV and ciphertext only.
