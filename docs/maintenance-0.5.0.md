# Owl 0.5.0 maintenance validation

Baseline: 5622e29 (0.4.1). Work branch: maintenance/owl-0.5.0.

## Automated checks

- Kotlin debug compilation, allstorage unit tests and release lint.
- Pure rules cover grouping, MediaStore/direct duplicate preference, partial indexing, immutable snapshots, all video sort modes, playlist order with 5000 entries, malformed entries, legacy history digest and resume boundaries, and existing preference preservation.
- Filesystem tests cover Android/data and Android/obb exclusion, folder-only refresh and cancellation inside a directory.
- CI verifies the existing 22 protected playback/native build file hashes before building.
- CI verifies allstorageRelease arm64 identity, version, launch activity, non-debuggable status, signature and APK SHA-256 before uploading or publishing.

## Device checks still required

No Android device is connected to the development host. Automated checks do not establish playback or UI performance on a real device.

| Check | Expected | Status |
| --- | --- | --- |
| Install alongside mpv-android and old Owl | Separate Owl installation, no replacement | APK identities checked; device pending |
| Partially indexed removable SSD | MediaStore first; missing direct files merge later | Pure rules/walker tested; USB device pending |
| Pull folder refresh | Only selected folder query/listing | Code + walker test; device pending |
| Refresh then open player | Browser scan/thumbnail requests cancel on stop | Code reviewed; device pending |
| HW+/HW/SW, subtitles, scaling, gestures | Existing behavior preserved | Protected hashes unchanged; playback pending |
| PiP, notification controls, external VIEW/SEND | Existing behavior preserved | Code reviewed; device pending |
| 5000+ video launch | Sorted previous/next sequence, responsive UI | Pure order test; device launch timing pending |
| Disconnect SSD during scan/playback | No stale activity updates; inaccessible entries skipped | Device pending |
| Diagnostics OFF | No JankStats listener or playback polling | Code reviewed |

## Diagnostics workflow

In Owl settings enable performance diagnostics, return to the browser, scroll/refresh, then play the source that feels uneven for 30–60 seconds. Return to settings and open/copy the diagnostic report. UI frame statistics are separate from playback counters. Reports retain at most 300 playback samples and omit source paths.

The helper reads the pinned mpv property list: frame-drop-count, decoder-frame-drop-count, avsync, total-avsync-change, vo-delayed-frame-count, estimated-vf-fps, hwdec-current, paused-for-cache, demuxer-cache-duration and Android display Hz. Numeric values use mpv's double conversion, the cache pause uses boolean and decoder name uses string. Unsupported values are n/a. No playback property is written.

Reference: https://github.com/mpv-player/mpv/blob/8e5a96fc456c041c7dcfccc19467e439bbeb8aab/DOCS/man/input.rst

## Practical limits

- Native rolling revisions were resolved at the 0.4.1 build timestamp. The exact revisions inside its old dependency cache were not recorded and cannot be claimed identical.
- Direct discovery keeps a five-minute cache per mounted path in the current process. Pull refresh bypasses it. No persistent on-disk media index is introduced.
- A codec's already-running native thumbnail extraction may finish one bounded frame after cancellation; queued work and subscriptions are removed immediately. API <27 shows a placeholder rather than an unbounded retriever frame (the released allstorage flavor requires API 30).
- Filesystem listFiles and provider queries are blocking platform calls; traversal and row iteration check cancellation between calls/items.
- UI jank ratios and playback dropped-frame counts must be collected on the target device before a second-phase cadence/decoder experiment. No smoothness improvement percentage is claimed.

## Signing

Private key/password are held in GitHub Actions Secrets and a local recovery directory outside the repository. Only the public certificate fingerprint is versioned. Old public debug material was removed from the current tree; it remains public in history and is not trusted.
