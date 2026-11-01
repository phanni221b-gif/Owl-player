# Owl 0.6.0: quality and scan design

## Cold-start root cause and verification

MainActivity and FolderActivity deferred scan requests when lifecycle.currentState was below STARTED, then retried from Activity.onStart(). AndroidX Lifecycle 2.9.4 uses the Android API 29+ onActivityPostStarted callback to deliver ON_START, after Activity.onStart() returns. Thus the retry could still see CREATED, defer again, and lose its only trigger. The default loading text made a scan that never started look permanently busy. ColdStartLifecycleTest reproduces CREATED inside onStart and STARTED in the lifecycle observer using actual AndroidX and Robolectric API 34.

LifecycleScanState now separates requests from execution. CREATED requests become PENDING; DefaultLifecycleObserver.onStart opens execution exactly once. Ordinary duplicates join an active scan; forced refreshes queue one forced successor. Force is OR-merged and survives stop/restart. Stop revokes the generation before canceling work. Old partial/final callbacks cannot publish. Completion, failure and cancellation leave explicit terminal/pending states; loading text/spinner reflect actual LOADING state. There is no unconditional onResume scan. Failed playback preparation can reopen the coordinator while still foregrounded.

LifecycleScanControllerTest exercises the production Android binding: granted cold launch, active refresh coalescing, foreground return, stop/restart, stale partial/final rejection, resumed permission callback, failure/retry and canceled playback preparation. Pure reducer tests separately cover force/generation invariants.

## Storage guarantees

Existing DiscoveryPipeline/ScanOwnership/ProviderQueryRunner isolation remains: primary publication precedes removable enumeration; external errors cannot remove primary results; bounded primary/external pools have no unbounded queue, and canceled continuations are detached even if vendor code ignores cancellation. Folder refresh stays scoped to the folder.

Direct filesystem inspection now uses a separate two-worker bounded pool. Directory listing and 128-entry metadata batches each have an 8-second budget. Only immutable results cross the worker boundary; abandoned workers cannot mutate discovery maps/cache or invoke UI callbacks. This avoids both unbounded waits and a single metadata timeout discarding a large directory. A timed-out batch/directory can be missing from that scan and is reported; manual forced refresh retries it. A disconnected/unresponsive filesystem cannot be made readable by the app. Logical traversals remain single-flight; permanently blocked OS calls occupy at most the bounded worker slots and do not block primary/provider scans.

Main storage-root filesystem checks also run in a bounded worker, not during UI startup. Foreground mount/unmount/eject/removal broadcasts request a 600 ms debounced forced scan while retaining visible primary rows. On later foreground entries, a changed storage-root inventory requests a scan only if the previous scan is already complete. Event diagnostics store generation, requests, deferral, merge, start, primary publish, removable query/enumeration, traversal, timeout, cancellation reason, completion and failure, without filenames/paths.

## Exact Ultra changes

Pinned mpv: 8e5a96fc456c041c7dcfccc19467e439bbeb8aab.
Its etc/builtin.conf fast profile sets scale/dscale=bilinear, dither=no, correct-downscaling=no, linear-downscaling=no, sigmoid-upscaling=no, hdr-compute-peak=no and allow-delayed-peak-detect=yes.

Ultra/neural-capable sessions skip that profile. Ultra explicitly uses:

| Option | Value / purpose |
| --- | --- |
| vo | gpu-next preferred; gpu initialization fallback |
| scale / dscale | ewa_lanczossharp / mitchell |
| cscale | spline36; explicit moderate-cost chroma filter instead of inherited EWA |
| deband | yes, GPU |
| dither / dither-depth | fruit / auto; no forced assumption about panel bit depth |
| correct-downscaling | yes |
| linear-downscaling | yes |
| sigmoid-upscaling | yes |
| hdr-compute-peak | auto, capability-aware instead of forced no/yes |
| allow-delayed-peak-detect | no, normal quality default |
| tone-mapping | auto (pinned gpu-next selects spline); no guessed HDR target/peak |
| scale-antiring | 0.6, pinned high-quality profile value |
| vd-lavc-fast / skiploopfilter | no / default |

All names/values were checked in the pinned options.rst/builtin.conf and vo_gpu_next.c option mapping. fbo-format is not mapped by this pinned gpu-next, so it is deliberately not set as a purported quality control. Pinned libplacebo renderer.c selects its own intermediate formats, preferring float16; float inference behavior on the actual Android driver remains a physical-device check. No video-sync, display-fps override, interpolation, HW decoder selection or pan parameter changes are made. Without advanced tiers enabled, Balanced/Sharp keep their existing initialization. With advanced tiers enabled, gpu-next is shared by the player session even when a particular source uses Balanced/Sharp. Source boundaries snapshot current user settings; a renderer change happens only if the user changed the renderer requirement, not for every source bucket. BaseMPVView retains that preferred renderer for surface restoration. Source selection restores the base fast-quality limits and user base scalers. Emergency Balanced fallback does not inherit a failed Ultra scaler.

Pinned cscale defaults inherit scale when unset. Direct mediacodec performs RGB conversion upstream; chroma reconstruction/bit depth already lost there cannot be restored by a later scaler. Copy-back/SW YUV sources can use the explicit chroma filter. This release does not silently change decoder paths to chase quality.

## Neural and AI Max

Neural: igv FSRCNNX_x2_8-0-4-1, release 1.1.
AI Max: igv FSRCNNX_x2_16-0-4-1, release 1.1, the larger-capacity provided model.
Both are real CNN convolution networks and both use GPU GLSL inference, not NPU. The 16-channel model is the highest configured tier; it is not a measured promise of superior output for every clip.

The upstream LUMA-only hook cannot cover direct MediaCodec RGB textures. Owl adapts the hook boundaries to MAIN: SDR RGB-to-luma extraction, unchanged CNN functions/coefficients/topology, then RGB reconstruction preserving chroma. Original and modified sources, pinned SHA-256 manifests, attribution and full LGPLv3/GPLv3 texts ship in the APK/repository. Shader headers explicitly license these files LGPL-3.0-or-later; training-code MIT/GPL licenses are not substituted. Settings exposes attribution and full license texts.

No new video-pixel CPU readback or CPU inference is introduced. Existing explicit HW copy-back/SW choices remain available. Models run only when BOTH output axes exceed input by 1.3x; otherwise diagnostics say bypassed_no_upscale and effective Ultra, retaining the dormant conditional shader so a later zoom/rotation can activate it. Source tier selection happens once after metadata; geometry checks are debounced events, not video-frame callbacks. HDR and unknown transfer characteristics are conservatively rejected because these models/hook adaptations are validated for SDR only.

Integrity/installation/option/compile/render-confirmation failure advances AI Max → Neural → Ultra → user base, removing duplicates. Balanced is a final emergency fallback if the base itself fails. A single glsl-shaders assignment overwrites the shader list; model networks are never stacked. Shader parsing alone is not success: read-only vo-passes NODE inspection confirms the CNN RGB reconstruction pass. Fresh/redraw histories are inspected without exporting descriptions/paths. GPU-next initialization can fall back to gpu, which disqualifies the higher tiers and restores the base. A larger runtime/driver failure still needs device reproduction; physical-device recovery must be tested.

## Why GPU, not a claimed NPU path

Qualcomm QNN/AI Engine Direct and LiteRT Qualcomm acceleration require a supported device/runtime, model conversion, deployment/redistribution terms, buffer-layout interoperability and synchronization. Owl currently has a MediaCodec/OpenGL ES mpv pipeline, not a QNN tensor/external-memory bridge. A reliable zero-copy NPU integration and its performance/license/deployment behavior were not established here. No proprietary runtime is added. The practical implementation keeps inference on the existing GPU texture pipeline and reports GPU honestly. No assumption is made about an unconnected Fold8's exact chipset or capabilities.

## Resolution and precedence

Use the smallest enclosing reference box after sorting source edges: 854×480, 1280×720, 1920×1080, 2560×1440, then UHD bucket for anything larger. Portrait is identical to landscape. 1920×800 is FHD; 2560×1080 ultrawide is QHD to account for its long edge. Nonstandard 540p is HD; the UI explicitly labels the last bucket as including resolutions above 1440p. Unknown/invalid dimensions use the user base.

AI Max's matching bucket wins over Neural; otherwise use the selected Balanced/Sharp/Ultra base. Model failure can try Neural even when its own automatic checkbox does not include that bucket. QualityPolicy is the isolated extension point for future measured fps, HDR, thermal and battery gates. Only SDR/renderer capability gates are active now; thermal, battery and fps limits are not represented as measured device policies.

## Diagnostics and protected baseline review

Diagnostics include source dimensions/fps/HDR/codec/bucket; base/requested/effective mode; model and real backend; pending/bypassed/confirmed/failed status; fallback reasons; hwdec-current, decoder/video/delayed drops, cache pause and per-pass GPU timing where available. Sum of per-pass averages is not a wall-clock video frame duration. Missing properties remain n/a.

Intentional protected changes: MPVView initialization selects the quality bootstrap and preferred renderer; MPVLib/property.cpp add one read-only async request; event.cpp summarizes its vo-passes NODE reply using render_pass_stats.h. Request userdata and a single-flight generation fence reject stale responses and prevent queue growth. No synchronous VO timing query waits on the UI thread. No JNI setter/decoder/engine/source-revision changes are made. Their updated hashes were reviewed against the small intended diff. Existing protected checks and the 0.5.3 pan contract remain; exact TouchGestures and new helper hashes are added. Native parser tests cover empty data, real matching reconstruction vs feature-only pass, fresh/redraw history, numeric aggregation and overflow. The built release APK is also checked for exact shader/source/license payloads, arm64 ELF headers and the new JNI export. CI separately compiles/renders both networks on desktop Mesa OpenGL ES, checks colored output and distinct model results. Desktop Mesa is not the Android driver or a Fold performance test.

## Physical Fold8 checks still required

- Cold start with permission already granted; launch after HDD attach; slow/failing/unmounted provider; internal rows appear first without refresh; refresh during scan; Home/foreground; rapid plug/unplug.
- Neural/AI overlapping 480p/720p buckets; portrait, square, ultrawide and unknown metadata; confirm one effective tier and GPU backend in diagnostics; HDR falls to Ultra.
- No-upscale → zoom/rotation → upscale and back: truthful bypass/effective status, normal zoom/pan bounds and unchanged 0.5.3 two-finger sensitivity.
- HW+/HW/SW/Auto, subtitles, resume, rotation, background audio and task-removal cleanup; storage browse/recursive search/sort/move/delete and large-folder scrolling.
- Compare Balanced/Sharp/Ultra/Neural/AI Max on the same SDR clip and output size; inspect halos, texture, color/chroma and subtitle stability. Compare HDR color/tone mapping across HW modes without assuming direct MediaCodec preserves 10-bit precision.
- Record real renderer timing, decoder/frame/delayed drops, cache pauses, heat, sustained fps and battery for 24/30/60 fps and longer playback. No Fold GPU/NPU speed, temperature, power or subjective quality result is claimed by CI.

Primary references: [pinned mpv fast profile](https://github.com/mpv-player/mpv/blob/8e5a96fc456c041c7dcfccc19467e439bbeb8aab/etc/builtin.conf), [pinned options](https://github.com/mpv-player/mpv/blob/8e5a96fc456c041c7dcfccc19467e439bbeb8aab/DOCS/man/options.rst), [FSRCNNX release](https://github.com/igv/FSRCNN-TensorFlow/releases/tag/1.1), [AndroidX lifecycle callbacks](https://github.com/androidx/androidx/blob/androidx-main/lifecycle/lifecycle-runtime/src/androidMain/kotlin/androidx/lifecycle/ReportFragment.android.kt), [Qualcomm runtime](https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk), [LiteRT Qualcomm accelerator](https://developers.google.com/edge/litert/next/qualcomm).

Buffer format reference: https://github.com/haasn/libplacebo/blob/c4de47cd94af6ce15573debe84306e01bca33996/src/renderer.c
