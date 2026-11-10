# Owl 0.6.1-diagnostic.5 — full audit

Reviewed: `main` = `v0.6.0` (ab4bdde), PR #8 head = `v0.6.1-diagnostic.4` (ff5d68b). This branch
fast-forwards PR #8 and adds the fixes below. Verdicts: 검증 완료 / 구현됐으나 미검증 /
부분 구현 / 미구현 / 결함 확인. "검증 완료" means verified by an automated test or a check in this
environment; nothing here was run on a physical Fold8 (none was attached).

## Defects found and fixed

| Pri | Defect | Fix | Evidence |
| --- | --- | --- | --- |
| P0 | Folder delete called `deleteTree` on the folder directory. Folder keys are a video's parent directory, so a video stored in `/storage/emulated/0` (or an SSD root) produced a deletable "folder" that is the volume root; with all-files access every file on the volume would be deleted. Photos/documents and subfolders (other Owl folders) in a mixed folder were also deleted. | Delete only the folder's listed direct-child videos, then remove the directory only if empty and not protected. Volume roots, their ancestors and shared top-level directories (DCIM, Download, Movies, Android…) can never be deleted, moved or renamed. Confirmation text states exactly this. | `OwlFileOperationsTest` (root, mixed folder, standard dir, move refusal); protected-path rules also run locally |
| P1 | Delete counted a non-existent file as success and API return values as truth. | Success = file really gone afterwards; missing files reported separately; a video entry never recurses into a directory. History is cleared only for files that are really gone. | `OwlFileOperationsTest` |
| P1 | Rename was not implemented. | Single video/folder rename; keeps extension; handles case-only rename on case-insensitive (exFAT) drives; moves resume and seen state. | `OwlFileOperationsTest`, `PlaybackHistoryTest` |
| P1 | Two-finger pan: `align -= dx/W*0.55` moves the picture `0.275*(scale-1)` times the finger (pinned mpv `aspect.c`: `dst_start = (W-S)*(align+1)/2`): 27% at 2x, 190% at 8x. The fitting axis accumulated hidden align that jumped later; lifting one of three fingers re-paired pointers and jumped. | `BoundedPan`: 1:1 tracking from the displayed size (`osd-dimensions`, unclipped margins verified in pinned source) scaled by `2^zoom`; non-overflowing axis stays 0; re-anchor on pointer-up. The 0.5.3 jitter deadzone now gates only the start of a pan, so motion is continuous instead of 7 px steps. No smoothing added; pinch deadzone/limits unchanged. | `BoundedPanTest` (also run locally); CI pan contract updated |
| P1 | Resume position was saved only through the browser's activity result: lost on task removal/process death, and earlier playlist entries were never saved. | Player saves per source on end-file, background and task exit (`PlaybackHistory.saveSource`, keyed identically to the browser). | `PlaybackHistoryTest` |
| P2 | A file that could not be opened closed the player silently. | Message when a source ends before playback ever starts and the user did not skip/exit. | 구현됐으나 미검증 (needs device) |
| P2 | Play/pause was ~39 dp left of center (104 dp vs 182 dp side clusters). | Runtime padding balance; button order, D-pad and audio UI untouched. | 구현됐으나 미검증 (needs screen) |
| P3 | "삭제 중" toast shown even when the operation was refused as busy. | Guard first. | — |

PR #8 CI note: its last `build-release` failure is the tag publish step (`docs/release-0.6.1-diagnostic.4.md` missing), not build/test/lint.

## Requirement matrix

### Library / file management
| Requirement | Verdict | Notes |
| --- | --- | --- |
| Internal + external SSD/HDD discovery, internal first | 구현됐으나 미검증 | DiscoveryPipeline publishes primary before removable; bounded workers/timeouts (0.6.0 tests). Real HDD latency/unplug needs device. |
| Folder-centric browsing, subfolders | 부분 구현 | Folders are flat parent-directory groups; StorageBrowser provides tree navigation. |
| Thumbnail, resolution, duration, size, progress | 구현됐으나 미검증 | VideoAdapter; removable direct-walk items have no duration/resolution (MediaStore-less). |
| Name search; current-folder-and-below search; all/internal/external scope | 구현됐으나 미검증 | Content-only (non-path) folders can only filter direct videos. |
| Sort persisted and equal to playlist order | 검증 완료 (code) | Playlist is built from the displayed sorted list snapshot. |
| Unseen count per folder (PR #8) | 구현됐으나 미검증 | Unit-tested; marked on PLAYBACK_RESTART; device UI unverified. |
| Multi-select, select all, move, delete | 검증 완료 (unit) after P0 fix | |
| Rename | 검증 완료 (unit) | New. |
| Folder delete semantics / confirmation | 검증 완료 (unit) | Was 결함 확인 (P0). |
| Scan cancel/refresh/rotation/task removal races | 구현됐으나 미검증 | LifecycleScanController tests from 0.6.0; physical checks remain. |

### Player
| Requirement | Verdict | Notes |
| --- | --- | --- |
| Immediate fullscreen, tap to show/auto-hide (1.5 s) | 구현됐으나 미검증 | |
| Prev/next follow folder sort | 검증 완료 (code) | |
| Prev/next balanced placement | 구현됐으나 미검증 | Fixed centering. |
| 10 s double-tap seek, swipe seek, brightness/volume | 구현됐으나 미검증 | Unchanged. |
| Pinch zoom + two-finger pan | 검증 완료 (math) / device feel 미검증 | See P1. |
| Subtitle/audio track selection, subtitle delay | 구현됐으나 미검증 | Subtitle *position* adjustment has no dedicated control (mpv `sub-pos` reachable only through mpv config) — 미구현 as a UI. |
| Screen modes 맞춤/꽉참/늘림/100% | 구현됐으나 미검증 | "원본" (original aspect) is the same as 맞춤 in Owl; there is no separate aspect-override picker besides 늘림. |
| Auto-rotate setting | 구현됐으나 미검증 | |
| Resume | 검증 완료 (unit) after fix | |
| Error / unsupported / access failure message | 부분 구현 | Generic message; JNI does not pass end-file reason (protected native code unchanged). |

### Quality
| Tier | Verdict | Evidence |
| --- | --- | --- |
| Balanced | 구현됐으나 미검증 | `profile=fast` + bicubic/bilinear. |
| Sharp | 구현됐으나 미검증 | `profile=fast` + spline36/mitchell (via preferences, gpu renderer). |
| Ultra | 구현됐으나 미검증 | gpu-next, ewa_lanczossharp/mitchell, spline36 cscale, deband, fruit dither, linear/sigmoid/correct downscaling; fast profile skipped. Option names checked against pinned mpv in 0.6.0. |
| Neural (FSRCNNX 8) | 구현됐으나 미검증 on device (Fold8 shows only a correct no-upscale bypass so far) | Same input/output glue as AI Max; Mesa GLES compile+render: PASS. |
| AI Max (FSRCNNX 16) | **검증 완료 (Fold8 rendering)** in diagnostic.7: `confirmed_pass n16=1, passes=32`, 640x480→2448x1836, 0 drops at 30 fps. Image quality vs Ultra not yet compared. | diagnostic.5 compile error fixed (see diagnosis-0.6.1.md). | |
| GPU vs NPU labelling | 검증 완료 (code) | Backend reported as GPU; "GPU requested/unconfirmed" until a render pass is confirmed. |
| Bypass vs error separation | 구현됐으나 미검증 | PR #8 checks output geometry before submission; bypass has no submission. |

Fallback success is never reported as Neural/AI success: `effective` stays Ultra until `vo-passes` contains the matching CNN reconstruction pass.

## Fold8 shader failure — current analysis

Known: diagnostic.1 reported a compile error at *generated* GLSL line 81 for both models, then fallback to Ultra. PRECISION/SAMPLER2D labels may come from source dumps (diagnostic.2 fixed that classification). No diagnostic.4 device report exists in the repository.

New evidence from this audit (Ubuntu libplacebo 6.338 + Mesa llvmpipe GLES 3.0, `--msg-level=all=trace`):
- Generated fragment shaders start with `#version 300 es`, `precision highp float` (under `GL_FRAGMENT_PRECISION_HIGH`), `precision mediump sampler2D; precision mediump sampler3D; precision highp int;`.
- For **both** models, line 81 of every feature-map pass that reads the 1-component `OWL_LUMA` texture is `res += vec4(...) * float(OWL_LUMA_texOff(vec2(2,-1)));` — consistent with "same line 81 in both models". Line 80 has the identical form, so the line alone does not identify an invalid construct.
- Other passes put line 81 elsewhere (mat4 convolutions, delinearize, `#define`), and the 16-channel model has passes where 81 is a `#define` macro.

Limits: the device runs pinned libplacebo c4de47cd, whose preamble length differs, so these line numbers are **not** transferable. Hypotheses (unconfirmed, not patched): (H1) Adreno handling of the 1-component `OWL_LUMA` FBO format or its mediump sampling; (H2) a preamble/glue difference for MAIN hooks on direct MediaCodec input; (H3) a driver resource limit misreported as a compile error. Dynamic vector indexing in the aggregation pass (`res[index.x * 2 + index.y]`) is legal in GLSL ES 3.00 and compiled on glslang/Mesa; it is a candidate only if the device uses ES 1.00.

Certainty: root cause **unconfirmed**. No production shader change is justified. The diagnostic.4/5 report fields `generated_glsl` (version), `generated_source` (allowlisted tokens around the compiler line) and `generated_hook` (pass role) are designed to identify the exact construct.

**Update (diagnostic.7):** the Fold8 diagnostic.5 report identified the failing statement: the RGB-to-luma input glue at generated line 81, `NO_MATCHING_OVERLOAD`, reproduced with glslang as `textureLod` on `samplerExternalOES`. A glue-only fix ships in diagnostic.7 (see `diagnosis-0.6.1.md`). Neural/AI Max on Fold8 stays 결함 확인 until a device `confirmed_pass` exists.

## Fold8 checklist (diagnostic.5)

1. Shader: follow `diagnosis-0.6.1.md` "One Fold8 test session"; return the full Owl diagnostics. Required evidence for success: `confirmed_pass` with the matching model.
2. Pan: zoom 2x and 4x on unfolded and cover screens; the picture must stay under the fingers, never reveal empty space, never drift on the fitting axis; lift one of three fingers without a jump.
3. Folder delete: create a test folder with videos + a photo + a subfolder; confirm only videos are removed. Put a test video in the storage root and confirm the root folder delete removes only that video.
4. Rename on internal storage and on an exFAT SSD (including case-only rename); resume position kept.
5. Resume: play two playlist items partially, swipe the task away, reopen — both show progress.
6. Corrupt/permission-denied file shows the open-error message; quick next/prev skipping does not.
7. Controls: play/pause centered on cover screen, unfolded and landscape.
8. Heat, battery, sustained fps and A/V sync: physical measurement only (not assessed here).
