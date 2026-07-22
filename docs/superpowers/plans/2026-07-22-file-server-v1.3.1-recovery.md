# File Server v1.3.1 Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Android v1.3.1 with correct MPEG-TS preview routing and add a Raspberry Pi USB manager that automatically mounts, recovers, and exposes ordinary and BitLocker USB volumes without rebooting.

**Architecture:** Android classifies ambiguous `.ts` files from sampled bytes before trusting File Browser MIME metadata. The Pi runs one locked, idempotent reconciliation command from both udev and a systemd timer; it discovers current USB block devices, removes stale recorded mounts, mounts normal filesystems directly, unlocks configured BitLocker volumes, and restarts dependent services only when topology changes.

**Tech Stack:** Kotlin/JUnit/Jetpack Compose, Bash, systemd, udev, util-linux, dislocker, Gradle 9.5/JDK 17.

## Global Constraints

- Android version is exactly `versionCode = 5` and `versionName = "1.3.1"`.
- Do not embed BitLocker passwords in the repository, unit files, command arguments, or logs.
- Store keys only under `/etc/file-server/bitlocker-keys/<UUID>.key` with fallback `/etc/file-server/bitlocker.key`, mode `0600`.
- Preserve the existing primary BitLocker mount path `/media/usb_bitlocker`; mount other volumes below `/media/file-server/<stable-name>`.
- Reconciliation is exclusive, bounded by timeouts, idempotent, and failure-isolated per volume.
- Keep the thumbnail service at one low-priority worker and do not make thumbnail generation block playback.
- Do not attempt to solve the Raspberry Pi undervoltage condition in software.

---

## File Structure

- `app/src/main/java/com/j2team/fileserver/feature/preview/PreviewRouter.kt`: byte-signature routing and resolved MIME type.
- `app/src/main/java/com/j2team/fileserver/feature/preview/PreviewScreen.kt`: use the router's resolved type for preview and display.
- `app/src/test/java/com/j2team/fileserver/feature/preview/PreviewRouterTest.kt`: MPEG-TS versus TypeScript regression coverage.
- `app/build.gradle.kts`: Android v1.3.1 metadata.
- `server/usb-mount/file-server-usb-mount`: locked reconciliation command.
- `server/usb-mount/tests/test_usb_mount.ps1`: fake-device/fake-command behavioral tests runnable on the Windows workspace.
- `server/usb-mount/file-server-usb-reconcile.service`: one-shot reconciliation unit.
- `server/usb-mount/file-server-usb-reconcile.timer`: periodic recovery trigger.
- `server/usb-mount/99-file-server-usb.rules`: immediate block-device trigger.
- `server/usb-mount/install.sh`: root installation, legacy-key migration, journald persistence, and service enablement.

### Task 1: Correct ambiguous `.ts` preview routing

**Files:**
- Modify: `app/src/test/java/com/j2team/fileserver/feature/preview/PreviewRouterTest.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/preview/PreviewRouter.kt`
- Modify: `app/src/main/java/com/j2team/fileserver/feature/preview/PreviewScreen.kt`

**Interfaces:**
- Consumes: `PreviewRouter.kind(name: String, sample: ByteArray, declaredMimeType: String?)`.
- Produces: `PreviewRouter.resolvedMimeType(name: String, kind: PreviewKind, declaredMimeType: String?): String`.

- [ ] **Step 1: Add failing regression tests**

Add tests proving that sync bytes at offsets `0`, `188`, and `376` classify `movie.ts` as `Video` even when MIME is `text/typescript`; valid UTF-8 source classifies as `Text`; binary non-text `.ts` falls back to `Video`; and resolved video MIME is `video/mp2t`.

- [ ] **Step 2: Run the focused test and confirm RED**

Run: `gradle.bat :app:testDebugUnitTest --tests com.j2team.fileserver.feature.preview.PreviewRouterTest`

Expected: FAIL because misleading TypeScript MIME currently wins and `resolvedMimeType` does not exist.

- [ ] **Step 3: Implement signature-first routing**

For extension `ts`, scan offsets `0..187` for at least three `0x47` sync bytes separated by 188 bytes. Return `Video` for a signature match, `Text` for valid UTF-8 text, and `Video` for binary fallback. Add `resolvedMimeType` so a routed `.ts` video returns `video/mp2t`; retain the server MIME for other cases.

- [ ] **Step 4: Wire the resolved MIME into `PreviewScreen`**

Calculate the preview kind once, store it, and store `PreviewRouter.resolvedMimeType(...)` so Media3 and the footer never receive `text/typescript` for MPEG-TS video.

- [ ] **Step 5: Run focused tests and commit**

Run the focused JUnit command again; expect all tests to pass. Commit message: `fix: route mpeg ts streams by signature`.

### Task 2: Build a testable USB reconciliation manager

**Files:**
- Create: `server/usb-mount/file-server-usb-mount`
- Create: `server/usb-mount/tests/test_usb_mount.ps1`

**Interfaces:**
- Consumes: Linux sysfs block entries, `blkid`, `findmnt`, `mount`, `umount`, `dislocker`, `fusermount`, and optional `/etc/file-server/usb-mount.conf`.
- Produces: per-volume state below `/run/file-server-usb/volumes/<stable-id>/` and stable mount paths.

- [ ] **Step 1: Add failing fake-environment tests**

Create a temporary fake sysfs tree and fake executables that log calls. Cover ordinary USB mounting, two simultaneous volumes, BitLocker key selection, compatibility target selection, device rename cleanup/remount, missing-key isolation, and a second idempotent run that makes no mount changes.

- [ ] **Step 2: Run the shell-manager tests and confirm RED**

Run: `powershell -ExecutionPolicy Bypass -File server/usb-mount/tests/test_usb_mount.ps1`

Expected: FAIL because `file-server-usb-mount` does not exist.

- [ ] **Step 3: Implement discovery and stable identity**

Enumerate partition entries and removable whole disks from configurable `SYS_CLASS_BLOCK`, resolve `TYPE`, `UUID`, and `LABEL` with `blkid`, ignore loop/system devices, sanitize the stable identifier in UUID-label-device priority order, and select `/media/usb_bitlocker` only for configured `COMPAT_UUID`.

- [ ] **Step 4: Implement bounded ordinary and BitLocker mounts**

Acquire `flock`, validate state, lazily detach stale targets, mount ordinary filesystems with `rw,nosuid,nodev,noexec`, and unlock BitLocker using stdin redirected from a mode-`0600` key file so the secret never appears in argv. Record device, UUID, kind, target, and unlock path only after success.

- [ ] **Step 5: Implement topology-aware service recovery**

Track whether cleanup or mounting changed topology. Restart `filebrowser.service` and `file-server-thumbnail.service` once at the end only when topology changed; continue processing remaining volumes when one volume fails.

- [ ] **Step 6: Run tests and commit**

Run the PowerShell test harness; expect `PASS` for every case. Commit message: `feat: add resilient USB mount reconciler`.

### Task 3: Install and activate automatic recovery on Raspberry Pi

**Files:**
- Create: `server/usb-mount/file-server-usb-reconcile.service`
- Create: `server/usb-mount/file-server-usb-reconcile.timer`
- Create: `server/usb-mount/99-file-server-usb.rules`
- Create: `server/usb-mount/install.sh`

**Interfaces:**
- Consumes: the Task 2 command and existing legacy `/usr/local/bin/auto_decrypt_bitlocker.sh` if present.
- Produces: `/usr/local/sbin/file-server-usb-mount`, enabled timer/udev integration, persistent journal, and protected key/config files.

- [ ] **Step 1: Add static installation assertions**

Extend `test_usb_mount.ps1` to assert service hardening and timeout settings, a recurring timer, udev `SYSTEMD_WANTS`, root-owned key permissions in the installer, no literal credential, legacy key migration without output, and disabling the legacy decrypt service.

- [ ] **Step 2: Run tests and confirm RED**

Run the PowerShell harness; expect failures for missing unit, timer, rule, and installer.

- [ ] **Step 3: Add systemd/udev assets and installer**

Create a oneshot service with a 45-second timeout, a 15-second recovery timer, and a block add/remove/change udev rule. The installer copies assets, silently migrates the old key, writes `COMPAT_UUID`, persists journald, disables the legacy service, reloads systemd/udev, and enables the timer.

- [ ] **Step 4: Deploy to the Pi and reconcile current state**

Upload `server/usb-mount` to `/tmp/file-server-usb-v1.3.1`, execute `sudo install.sh`, and let the first reconciliation replace stale `/dev/sda1` state with the currently discovered stable volume.

- [ ] **Step 5: Verify Pi installation**

Check `systemctl status` for the reconciler/timer/File Browser/thumbnail services; inspect `findmnt` and `lsblk`; assert key modes are `600`; call the File Browser listing endpoint and thumbnail health endpoint; inspect the persistent journal for reconciliation errors. Do not reboot the Pi as part of verification.

- [ ] **Step 6: Commit**

Commit message: `feat: install automatic USB recovery services`.

### Task 4: Version, build, verify, and publish v1.3.1

**Files:**
- Modify: `app/build.gradle.kts`
- Produce: `app/build/outputs/apk/debug/app-debug.apk`

**Interfaces:**
- Consumes: completed Android and Pi changes.
- Produces: buildable v1.3.1 source, debug APK, and pushed `main` branch.

- [ ] **Step 1: Bump version metadata**

Set `versionCode = 5` and `versionName = "1.3.1"`.

- [ ] **Step 2: Run full verification**

Run `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, the USB mount harness, and `go test ./...` inside `server/thumbnail-service`. Expected: all exit code 0 and APK exists.

- [ ] **Step 3: Review repository state and APK metadata**

Confirm no secret, temporary SSH file, Gradle cache, key file, or Pi runtime state is tracked. Use `apkanalyzer manifest version-name` or inspect the merged manifest to confirm `1.3.1`.

- [ ] **Step 4: Commit version/build changes**

Commit message: `release: prepare File Server v1.3.1`.

- [ ] **Step 5: Push main**

Run `git push origin main`, then confirm local `main` matches `origin/main` and report the APK path and commit IDs.
