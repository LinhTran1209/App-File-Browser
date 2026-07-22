# File Server v1.3.1 Recovery Design

## Goal

Release Android app version 1.3.1 and make the Raspberry Pi recover automatically when removable USB storage disconnects and reconnects. The mount layer must support ordinary filesystems and BitLocker volumes, including multiple USB devices, without requiring a Pi reboot.

## Confirmed failure

The Pi reports active and historical undervoltage (`get_throttled=0x50005`). During load, the complete USB root hub disconnected. The BitLocker device returned under a new kernel name, but `dislocker` and the NTFS loop mount continued reading the deleted old device. File Browser remained alive and correctly returned HTTP 500 because the underlying mount produced `EIO`.

The app has a separate `.ts` routing defect. File Browser may declare an MPEG transport stream as `text/typescript`; the preview probe trusts that MIME and starts a full text download instead of Media3 streaming.

Power replacement is explicitly outside this change. Software recovery reduces downtime but cannot guarantee storage integrity while power remains unstable.

## Raspberry Pi USB mount manager

Repository-owned assets live under `server/usb-mount/`:

- `file-server-usb-mount`: a root-only reconcile command.
- `file-server-usb-reconcile.service`: serialized oneshot reconciliation.
- `file-server-usb-reconcile.timer`: periodic recovery if a udev event is missed.
- `99-file-server-usb.rules`: schedules reconciliation for block partition add/remove/change events.

The reconciler takes an exclusive lock and discovers removable partitions from `/sys/class/block` plus `lsblk`/`blkid`. It ignores the system disk and loop devices.

For every supported partition it derives a stable identity from filesystem UUID, then a sanitized label, then the device serial. Mount directories remain stable across `/dev/sda1` to `/dev/sdb1` renames.

Ordinary filesystems are mounted below `/media/file-server/<stable-name>`. Supported types are NTFS, exFAT, FAT, ext2/3/4, and common Linux filesystems available in the installed kernel. Mount options are conservative and do not execute binaries from removable media.

BitLocker volumes are unlocked with `dislocker` into a per-volume runtime directory and their decrypted NTFS image is mounted at the stable public mount directory. Keys are read from `/etc/file-server/bitlocker-keys/<UUID>.key`, with `/etc/file-server/bitlocker.key` as the single-key fallback. Key files must be owned by root and mode `0600`; no key is embedded in scripts, unit files, process arguments, or repository files.

The existing BitLocker volume keeps the compatibility path `/media/usb_bitlocker` so saved app paths remain valid. Additional volumes use `/media/file-server/<stable-name>`.

State files under `/run/file-server-usb/` record device identity, mount target, dislocker directory, and helper PIDs. During each reconciliation the manager:

1. Removes stale mounts whose backing device disappeared or whose mount fails a bounded read check.
2. Terminates only helpers recorded for that stale volume.
3. Mounts newly discovered volumes.
4. Restarts File Browser and the thumbnail companion only when public mount topology changed.

All mount and cleanup operations use timeouts. Failure of one USB volume does not block other volumes. Logs go to journald without secrets.

## Android `.ts` routing

The preview probe classifies `.ts` samples in this order:

1. MPEG-TS sync-byte evidence at 188-byte packet intervals means video.
2. A valid UTF-8 text sample means TypeScript text.
3. A binary `.ts` sample falls back to video.

For `.ts`, server MIME is advisory and cannot override MPEG-TS evidence. Video classification passes `video/mp2t` to Media3 and requests the companion thumbnail asynchronously. Text classification retains the existing paged text preview. No video is downloaded into the text-preview cache.

Thumbnail generation remains a single low-priority Pi worker. A request is queued only once for an uncached video and never blocks playback.

## Versioning and delivery

- Android `versionName`: `1.3.1`
- Android `versionCode`: `5`
- Build a debug APK for user installation.
- Commit implementation and deployment assets to `main`, then push `origin/main`.

## Verification

- Unit tests cover MPEG-TS signature detection, TypeScript text, misleading MIME, and binary fallback.
- Shell tests run the mount reconciler against fake discovery/state commands without mounting real devices.
- On the Pi, verify systemd/udev installation, key permissions, stable compatibility mount, ordinary USB discovery, current service health, and journal output.
- Run Android unit tests and `assembleDebug` before commit and push.

## Operational caveat

Automatic remount prevents a full reboot after a disconnect. It cannot make unsafe writes reliable during undervoltage. The user must still replace the power supply and should check the NTFS filesystem from Windows after a clean unmount.
