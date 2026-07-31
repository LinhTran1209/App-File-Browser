$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Guard = Get-Content -Raw (Join-Path $Root 'filebrowser-ffmpeg-guard')
$DropIn = Get-Content -Raw (Join-Path $Root 'filebrowser-thumbnail-guard.conf')
$Installer = Get-Content -Raw (Join-Path $Root 'install-filebrowser-thumbnail-guard.sh')

function Assert-Contains([string]$Text, [string]$Needle, [string]$Message) {
    if (-not $Text.Contains($Needle)) {
        throw $Message
    }
}

Assert-Contains $Guard 'png_transport_stream_prefix' 'Guard must detect the malformed PNG plus MPEG-TS prefix.'
Assert-Contains $Guard '-skip_initial_bytes "$prefix"' 'Guard must skip the detected prefix before decoding.'
Assert-Contains $Guard '-threads 1 -filter_threads 1' 'Guard must bound ffmpeg worker threads.'
Assert-Contains $Guard '--kill-after=5s 45s' 'Guard must terminate stuck thumbnail generation.'
Assert-Contains $Guard 'is_single_frame_thumbnail' 'Guard must limit smart seeking to one-frame JPEG thumbnails.'
Assert-Contains $Guard 'seek_is_opening_frame' 'Guard must replace File Browser opening-frame seek requests.'
Assert-Contains $Guard 'remove_seek_arguments' 'Guard must remove the explicit zero seek before choosing a representative frame.'
Assert-Contains $Guard 'thumbnail_seek_candidates' 'Guard must calculate representative video seek positions.'
Assert-Contains $Guard 'percentages[1] = 0.20' 'Guard must avoid the opening frame for normal videos.'
Assert-Contains $Guard 'THUMBNAIL_DARK_YAVG=40' 'Guard must reject black and very dark thumbnail frames.'
Assert-Contains $Guard 'all thumbnail candidates were dark' 'Guard must preserve the brightest fallback candidate.'
Assert-Contains $DropIn 'MemoryMax=256M' 'File Browser cgroup must have a hard memory limit.'
Assert-Contains $DropIn 'MemorySwapMax=32M' 'File Browser cgroup must not exhaust global swap.'
Assert-Contains $Installer 'systemd-analyze verify filebrowser.service' 'Installer must verify the unit before restart.'

Write-Output 'PASS: File Browser thumbnail guard has format, timeout, memory and swap protections.'
