### Task 7: Launcher Branding and Full Acceptance

**Files:**
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/values/ic_launcher_background.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/androidTest/java/com/j2team/fileserver/AppLaunchTest.kt`
- Create: `app/src/androidTest/java/com/j2team/fileserver/SessionResumeTest.kt`
- Create: `app/src/androidTest/java/com/j2team/fileserver/SettingsAndSelectionTest.kt`

**Interfaces:**
- Produces adaptive launcher icon using a folder/server mark.
- Produces end-to-end device verification evidence.

- [ ] **Step 1: Add instrumentation tests**

Tests cover password eye semantics, session resume after activity recreation,
language change, directory-selection state, long-press selection, transfer tabs,
and long-text scrolling.

- [ ] **Step 2: Verify instrumentation**

Run: `.\gradlew.bat connectedDebugAndroidTest`
Expected: all tests pass on `QV704YX51G`.

- [ ] **Step 3: Add adaptive launcher branding**

Use a safe-zone centered folder/server vector foreground with indigo background.
Set `android:icon` and `android:roundIcon` in the manifest.

- [ ] **Step 4: Run complete verification**

Run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug connectedDebugAndroidTest assembleDebug
& $adb -s QV704YX51G install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Expected: exit code 0, installation `Success`.

- [ ] **Step 5: Raspberry Pi acceptance**

Using a uniquely named temporary directory:

1. Login once and confirm process-restart session resume.
2. Upload a text file and nested folder.
3. Download the text file to the selected SAF directory.
4. Preview text/code, PDF, image, audio, and available video samples.
5. Multi-select and delete the temporary resources.
6. Confirm denied delete remains visible.
7. Confirm no more than two concurrent transfers.

- [ ] **Step 6: Commit and publish**

Commit: `git commit -m "feat: complete File Server Android experience"`

Update every changed file through the connected GitHub app and record the
confirmed remote commit SHA. Do not report a Git push until confirmation is
returned.
