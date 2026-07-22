#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
    printf 'Run this installer as root.\n' >&2
    exit 1
fi

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_DIR=/etc/file-server
KEY_DIR="$CONFIG_DIR/bitlocker-keys"
FALLBACK_KEY="$CONFIG_DIR/bitlocker.key"
CONFIG_FILE="$CONFIG_DIR/usb-mount.conf"
LEGACY_SCRIPT=/usr/local/bin/auto_decrypt_bitlocker.sh

install -d -m 0755 "$CONFIG_DIR" "$KEY_DIR" /media/file-server /media/usb_bitlocker

# Migrate the legacy inline password once without printing it or putting it in process arguments.
if [[ ! -s "$FALLBACK_KEY" && -r "$LEGACY_SCRIPT" ]]; then
    legacy_key="$(sed -n "s/.*-u'\([^']*\)'.*/\1/p" "$LEGACY_SCRIPT" | head -n1)"
    if [[ -n "$legacy_key" ]]; then
        umask 077
        printf '%s\n' "$legacy_key" >"$FALLBACK_KEY"
        unset legacy_key
    fi
fi
if [[ -e "$FALLBACK_KEY" ]]; then chmod 600 "$FALLBACK_KEY"; chown root:root "$FALLBACK_KEY"; fi
find "$KEY_DIR" -maxdepth 1 -type f -name '*.key' -exec chmod 600 {} +
find "$KEY_DIR" -maxdepth 1 -type f -name '*.key' -exec chown root:root {} +

# Keep the first detected BitLocker volume on the app's historical path.
compat_uuid=""
while IFS= read -r candidate; do
    compat_uuid="$(blkid -o value -s UUID "$candidate" 2>/dev/null || true)"
    [[ -n "$compat_uuid" ]] && break
done < <(blkid -t TYPE=BitLocker -o device 2>/dev/null || true)
if [[ -n "$compat_uuid" ]]; then
    printf 'COMPAT_UUID=%q\n' "$compat_uuid" >"$CONFIG_FILE"
else
    : >"$CONFIG_FILE"
fi
chmod 0644 "$CONFIG_FILE"
chown root:root "$CONFIG_FILE"

install -m 0755 "$SOURCE_DIR/file-server-usb-mount" /usr/local/sbin/file-server-usb-mount
install -m 0644 "$SOURCE_DIR/file-server-usb-reconcile.service" /etc/systemd/system/file-server-usb-reconcile.service
install -m 0644 "$SOURCE_DIR/file-server-usb-reconcile.timer" /etc/systemd/system/file-server-usb-reconcile.timer
install -m 0644 "$SOURCE_DIR/99-file-server-usb.rules" /etc/udev/rules.d/99-file-server-usb.rules

# Preserve logs across reboots so USB/power faults can be diagnosed without reproducing them live.
install -d -m 2755 /var/log/journal
systemd-tmpfiles --create --prefix /var/log/journal || true
systemctl restart systemd-journald.service || true

systemctl disable --now decrypt-bitlocker.service >/dev/null 2>&1 || true
systemctl stop file-server-usb-reconcile.timer >/dev/null 2>&1 || true
systemctl kill --kill-who=all --signal=TERM file-server-usb-reconcile.service >/dev/null 2>&1 || true
systemctl stop filebrowser.service file-server-thumbnail.service >/dev/null 2>&1 || true
timeout 10 umount -l /media/usb_bitlocker >/dev/null 2>&1 || true
timeout 10 fusermount -uz /media/bitlocker >/dev/null 2>&1 || true
rm -rf /run/file-server-usb

systemctl daemon-reload
udevadm control --reload-rules
udevadm trigger --subsystem-match=block --action=change || true
systemctl enable --now file-server-usb-reconcile.timer
systemctl start file-server-usb-reconcile.service
systemctl start filebrowser.service file-server-thumbnail.service

printf 'File Server USB recovery installed.\n'
