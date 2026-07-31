$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$manager = Join-Path $root 'file-server-usb-mount'
$service = Join-Path $root 'file-server-usb-reconcile.service'
$timer = Join-Path $root 'file-server-usb-reconcile.timer'
$rule = Join-Path $root '99-file-server-usb.rules'
$installer = Join-Path $root 'install.sh'

function Assert-Contains([string]$Path, [string]$Pattern, [string]$Message) {
    if (-not (Test-Path $Path)) { throw "Missing $Path" }
    if (-not (Select-String -LiteralPath $Path -Pattern $Pattern -Quiet)) { throw $Message }
}

function Assert-NotContains([string]$Path, [string]$Pattern, [string]$Message) {
    if (Test-Path $Path) {
        if (Select-String -LiteralPath $Path -Pattern $Pattern -Quiet) { throw $Message }
    }
}

Assert-Contains $manager 'flock' 'Manager must serialize reconciliation with flock'
Assert-Contains $manager 'SYS_CLASS_BLOCK' 'Manager must expose a fakeable sysfs root'
Assert-Contains $manager 'COMPAT_UUID' 'Manager must preserve the primary compatibility mount'
Assert-Contains $manager 'bitlocker-keys' 'Manager must select per-volume BitLocker keys'
Assert-Contains $manager 'dislocker.*-u.*<' 'BitLocker password must enter dislocker through stdin'
Assert-Contains $manager 'topology_changed' 'Services must only restart after topology changes'
Assert-Contains $manager 'filebrowser.service' 'File Browser must recover after remounting'
Assert-Contains $manager 'file-server-thumbnail.service' 'Thumbnail service must recover after remounting'

Assert-Contains $service 'Type=oneshot' 'Reconciler must be oneshot'
Assert-Contains $service 'TimeoutStartSec=45' 'Reconciler must have a bounded runtime'
Assert-Contains $service 'KillMode=process' 'Reconciler must not kill persistent FUSE mount helpers on exit'
Assert-Contains $service 'PrivateMounts=false' 'Mounts must be created in the host namespace for File Browser'
Assert-Contains $manager 'mountpoint -q' 'Mount detection must not confuse the root filesystem with the USB mount'
Assert-Contains $manager 'systemctl start aria2.service' 'A newly mounted filesystem must explicitly start aria2 after revealing its marker'
Assert-Contains $manager 'AUTO_NTFS_REPAIR' 'BitLocker mounts must expose an automatic dirty-volume repair switch'
Assert-Contains $manager 'ntfsfix -d' 'BitLocker mounts must clear the dirty flag before retrying once'
Assert-Contains $manager 'automatic NTFS dirty-volume repair succeeded' 'Successful boot repair must be observable in the journal'
Assert-Contains $timer 'OnUnitInactiveSec=5min' 'Timer must repair missed udev events without constant reconciliation churn'
Assert-Contains $timer 'AccuracySec=30s' 'Timer should coalesce fallback recovery wakeups'
Assert-Contains $rule 'SYSTEMD_WANTS.*file-server-usb-reconcile.service' 'udev must trigger reconciliation'
Assert-Contains $installer 'chmod 600' 'Installer must protect BitLocker keys'
Assert-Contains $installer 'decrypt-bitlocker.service' 'Installer must disable the legacy decrypt service'
Assert-Contains $installer '/var/log/journal' 'Installer must enable persistent journald storage'

Assert-NotContains $manager 'dislocker.*-u\S+' 'Manager must not put a BitLocker password in argv'

Write-Output 'PASS: USB mount manager assets satisfy recovery and secret-handling requirements.'
