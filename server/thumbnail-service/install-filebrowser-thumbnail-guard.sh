#!/usr/bin/env bash
set -Eeuo pipefail

if (( EUID != 0 )); then
    printf 'Run this installer as root.\n' >&2
    exit 1
fi

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOL_DIR=/usr/local/libexec/filebrowser-thumbnail-guard
DROPIN_DIR=/etc/systemd/system/filebrowser.service.d

install -d -o root -g root -m 0755 "$TOOL_DIR" "$DROPIN_DIR"
install -o root -g root -m 0755 \
    "$SOURCE_DIR/filebrowser-ffmpeg-guard" \
    "$TOOL_DIR/ffmpeg"
install -o root -g root -m 0644 \
    "$SOURCE_DIR/filebrowser-thumbnail-guard.conf" \
    "$DROPIN_DIR/40-thumbnail-guard.conf"

systemd-analyze verify filebrowser.service
systemctl daemon-reload
systemctl restart filebrowser.service
systemctl is-active --quiet filebrowser.service

printf 'File Browser thumbnail guard installed.\n'
