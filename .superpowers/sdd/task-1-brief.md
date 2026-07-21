### Task 1: Encrypted Persistent Sessions

**Files:**
- Create: `app/src/main/java/com/j2team/fileserver/core/session/EncryptedSecretStore.kt`
- Create: `app/src/main/java/com/j2team/fileserver/core/session/SessionRepository.kt`
- Create: `app/src/main/java/com/j2team/fileserver/core/session/SessionModels.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/core/network/FileBrowserClient.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/MainActivity.kt`
- Test: `app/src/test/java/com/j2team/fileserver/core/session/SessionPolicyTest.kt`

**Interfaces:**
- Produces `data class StoredCredential(val username: String, val password: CharArray)`.
- Produces `interface SecretStore { fun put(profileId: String, credential: StoredCredential); fun get(profileId: String): StoredCredential?; fun delete(profileId: String) }`.
- Produces `class SessionRepository` with `suspend fun open(profile): Result<AuthenticatedSession>`, `suspend fun login(profile, username, password): Result<AuthenticatedSession>`, and `fun clear(profileId)`.

- [ ] **Step 1: Write the failing token-renewal policy test**

```kotlin
@Test fun rejectedTokenRelogsInExactlyOnce() = runTest {
    val transport = FakeTransport(responses = mutableListOf(401, 200, 200))
    val policy = SessionPolicy(transport)
    assertTrue(policy.execute("old-token") { transport.request(it) }.isSuccess)
    assertEquals(1, transport.loginCalls)
    assertEquals(2, transport.requestCalls)
}
```

- [ ] **Step 2: Run the focused test**

Run: `.\gradlew.bat testDebugUnitTest --tests "*SessionPolicyTest"`
Expected: FAIL because `SessionPolicy` is absent.

- [ ] **Step 3: Implement encrypted storage**

Use `KeyGenParameterSpec` with alias `file_server_credentials_v1`, AES/GCM/NoPadding,
256-bit key, random 12-byte IV, and profile ID bytes passed to
`cipher.updateAAD(profileId.toByteArray())`. Persist only Base64 IV and ciphertext
in private SharedPreferences.

- [ ] **Step 4: Implement one-shot session renewal**

```kotlin
suspend fun <T> authenticated(profile: ServerProfile, call: suspend (String) -> ApiResult<T>): Result<T> {
    val first = call(tokenStore[profile.id].orEmpty())
    if (first.code !in setOf(401, 403)) return first.toResult()
    val credential = secretStore.get(profile.id) ?: return Result.failure(LoginRequiredException())
    val renewed = transport.login(profile, credential.username, credential.password.concatToString()).getOrThrow()
    tokenStore[profile.id] = renewed
    return call(renewed).toResult()
}
```

- [ ] **Step 5: Wire login and server opening**

Successful explicit login stores credentials and token. Opening a saved server
calls `SessionRepository.open`; it navigates directly to Browser on success and
to Login only for `LoginRequiredException`.

- [ ] **Step 6: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`
Expected: all tasks pass.

Commit: `git commit -m "feat: persist encrypted server sessions"`
