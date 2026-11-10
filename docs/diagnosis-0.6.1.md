# 0.6.1 device diagnosis — diagnostic.1

This is an intermediate diagnostic build, not a confirmed Adreno compatibility fix. Do not merge or tag v0.6.1 until physical-device evidence identifies the failure and the eventual change passes regression checks.

## Evidence and boundaries
The supplied Fold8 report shows both model attempts falling back and normal MediaCodec/gpu-next playback afterward. The sampled n8/n16 values are after fallback; they cannot alone establish whether earlier CNN passes executed. No Android device is attached in the build environment.

The production shader assets, coefficients, hook topology, native revisions, decoder selection, renderer options, scan coordinator and storage implementation remain unchanged in this build. Existing tools/validate_neural_assets.py compares all upstream CNN hook functions to the adapted functions after the sole LUMA-to-OWL_LUMA symbol substitution. Owl adds MAIN RGB luminance extraction and RGB reconstruction around those functions; these shared glue stages, COMPONENTS/format selection, precision and driver resource limits remain hypotheses, not established causes. Mesa success does not prove Adreno compatibility.

## Diagnostic change
Renderer messages are classified synchronously into fixed categories/reasons, bounded line numbers, allowlisted compiler codes, shader stages, GL enums and shader-language symbols. Raw text and paths are never retained in the new report or posted to the UI handler. Each entry identifies source generation, attempt and phase. A bounded 80 ms collection window preserves detailed compiler lines accompanying generic error banners before normal downgrade. Stale generations cannot trigger a new downgrade. At most 160 sanitized entries are retained and session cleanup clears them.

AI Max → Neural → Ultra → selected base fallback remains in place. These diagnostics do not mark an unconfirmed model as rendered. No minimum probe suite is added yet: the first device run should expose the actual compiler/runtime category before choosing a narrower test or changing production shaders.

## Fold8 next run
1. Install the signed diagnostic APK over 0.6.0. Enable diagnostics and clear the report.
2. Use the same 1280x720 SDR H.264 clip, base Ultra, AI Max enabled for 720p. Keep the viewing size large enough to require upscaling. Play for 10 seconds.
3. Copy the complete diagnostic report, including Sanitized renderer failures, Quality pipeline and Latest playback summary. Do not send a raw logcat or private filenames.
4. Repeat after disabling AI Max for 720p and enabling Neural alone. Clear the report first.
5. Confirm playback continues after fallback and report frame/decoder/delayed drops, A/V sync and renderer average time. Performance, heat and battery remain physical-device tests.

Use the returned error category/code to decide whether precision, allocation, format, resource pressure or another path actually needs changing. If the category remains unknown, add a targeted probe rather than guessing a compatibility patch.

## Physical diagnostic.1 result and diagnostic.2 follow-up

The returned physical reports show compile errors at generated GLSL line 81 for AI Max and Neural, with no retained compiler code or specific offending symbol. Their final source metadata differs (480x268 at 25 fps vs 1920x1080 at 25 fps), so this is shared-line evidence across two sources, not a controlled identical-clip comparison. It identifies a common compile failure but cannot uniquely identify an Adreno precision/format/glue bug. The compiler line refers to generated source, not the asset's line 81. No production compatibility patch is justified yet.

The pinned libplacebo source confirms that failed program creation dumps numbered vertex/fragment/compute source at error severity. Diagnostic.1 incorrectly classified these dump lines as driver failures; they crowded the 160-entry ring and could obscure detailed reasons. Diagnostic.2 excludes dump headers/lines from fallback classification, removes generic error-level text without actual failure terms, preserves fixed compiler terms (reserved word, undeclared identifier, unexpected token, overload/type mismatch, etc.), and records only a five-line window around up to four compiler locations per attempt. The window retains allowlisted GLSL vocabulary; arbitrary identifiers, numbers, strings, comments and paths are redacted. Raw messages are still not retained. Generation fencing and normal fallback remain.

All 44 unmodified hook bodies also pass 132 standalone GLSL ES 300/310/320 syntax/type compilations using official glslang 16.6.0 locally. CI adds glslang-tools checks alongside unchanged full Mesa framebuffer rendering. The standalone harness supplies simple 2D bindings; it does not reproduce Android external-texture glue or prove Adreno compatibility.

Next device run: diagnostic.2, same known SDR clip in both modes; clear diagnostics between runs. Return the full safe report including generated_source and terms. These fields should identify the actual line/token rather than requiring raw logs. The actual shader/driver cause, confirmed device correction and final 0.6.1 release remain pending that evidence.

Pinned-source references:
- https://github.com/haasn/libplacebo/blob/c4de47cd94af6ce15573debe84306e01bca33996/src/gpu.c (log_shader_sources)
- https://github.com/haasn/libplacebo/blob/c4de47cd94af6ce15573debe84306e01bca33996/src/log.c (pl_msg_source numbering)
- https://github.com/haasn/libplacebo/blob/c4de47cd94af6ce15573debe84306e01bca33996/src/opengl/gpu_pass.c (GL compile log)
## diagnostic.4: Fold8 reproduction

The diagnostic.3 report shows a Neural attempt followed by Ultra, with neither CNN marker present in the later Ultra pass sample. The earlier diagnostic.1 report captured compiler line 81 for both models. Its sampler and precision labels could come from shader source dumps. The reported FPS samples do not identify the cause.

The earlier controller submitted the shader before checking the output rectangle and allowed the first generic renderer error to end the attempt after 80 ms. Detailed logs posted after an attempt switch were discarded. The classifier also treated libplacebo's common program-build error as a link error. The pinned libplacebo dispatch cache can retain a failed pass, so a repeated attempt in the same renderer can lack the original compiler log. These are possible explanations for missing detail in diagnostic.3. The available Fold8 logs do not identify which path occurred or the defective GLSL expression.

The controller now checks the OSD-derived video rectangle before submitting a model. It records bypass, submission, fallback, and confirmed pass samples with source and attempt IDs. During confirmation a generic error keeps the bounded render deadline open for more specific evidence. A compile or link failure has a short collection window before downgrade. Reports retain sanitized error evidence on arrival and deduplicate repeated lines. Dump stage, GLES version, sampler declarations, and known hook expressions are recorded separately from compiler locations. A source dump describes generated code, and a hook match identifies which code appeared in that dump.

### One Fold8 test session

1. Update to diagnostic.4. Close Owl completely and open it again to start with a new renderer. Turn on diagnostics and clear the report. Use fullscreen on the unfolded screen and keep MediaCodec enabled.
2. Set Neural for 1080p and AI Max for 720p. Play a 1080p SDR clip and then a 720p SDR clip for around ten seconds each. At the 1.3 threshold both output edges must exceed 2496 × 1404 for 1080p, or 1664 × 936 for 720p. Use the reported output geometry to verify eligibility. If 720p is bypassed, use a smaller SDR clip and assign its bucket to AI Max.
3. If AI Max fails, let the automatic Neural fallback finish. If it renders successfully, switch the same bucket to Neural and replay the clip within the session. Copy the full Owl diagnostics once.

Compare the attempt IDs on `submit geometry`, `generated_glsl`, `generated_hook`, `generated_source`, and `fallback`. A bypass has no model submission. A compiler status or line identifies compile failure. A link status identifies link failure. A later pass-creation or dispatch message can follow either failure. A `confirmed_pass` with the matching model and pass marker is the device evidence required for success. If the driver supplies only a generic failure, the generated preamble and hook role narrow the next probe, and missing compiler/GL values remain unavailable.

## diagnostic.5 Fold8 result and diagnostic.7 change

The Fold8 report (AI Max, 640x480 -> 2448x1836, upscale required) contains, for the same source and attempt:
`shader_compile_error:syntax_or_type_error, line=81, terms=NO_MATCHING_OVERLOAD`, then `program_build_failed`
and `pass_setup_failed`. The dump identifies GLSL ES 3.20 with `precision highp sampler2D`, and line 81 of the
failing fragment shader is the Owl RGB-to-luma input glue (role `main_rgb_to_luma`):
`vec4 hook() { return vec4(dot(HOOKED_tex(HOOKED_pos).rgb, vec3(...)), ...); }`. The CNN passes never ran.
The following Neural attempt only logged `driver_reported_error`: its input pass is byte-identical, so the
renderer re-used the failed program without a new compiler log, which also explains diagnostic.3.

Pinned libplacebo c4de47cd `bind_pass_tex` defines `NAME_tex(pos)` as `mul * vec4(textureLod(raw, pos, 0.0))`
and `HOOKED_tex` as an alias. GL_OES_EGL_image_external_essl3 provides `texture()` but not `textureLod()` for
`samplerExternalOES`, the type of direct MediaCodec frames. Reproduction with glslang 15 and the exact libplacebo
macro layout: the old line fails with `'textureLod' : no matching overloaded function found` when HOOKED is
`samplerExternalOES`, and compiles when it is `sampler2D`. That matches the device error class and line.

diagnostic.7 changes only the two glue reads of HOOKED (input and RGB reconstruction) to
`HOOKED_mul * texture(HOOKED_raw, HOOKED_pos)`, valid for both sampler types and identical for non-mipmapped
textures. CNN functions, coefficients and topology are unchanged (validator still compares them to upstream).
`tools/validate_glsl_es.mjs` now uses the libplacebo macro layout and compiles HOOKED passes as both sampler types;
it fails on diagnostic.5 assets with the device error and passes on diagnostic.7. Mesa GLES render smoke passes.

Certainty: the failing statement and error class are established from device evidence; that HOOKED is an external
texture at the MAIN stage on Fold8 is inferred (it is the only overload on that line that cannot match) and is
not proven until the device shows `confirmed_pass`. If a different error appears, the same report fields apply.

Also changed: an untagged transfer (HDR=unknown) blocked both models for an 854x480 H.264 clip. It is now treated
as SDR only when the decoder transfer is not PQ/HLG, primaries are not BT.2020 and the codec has no practical HDR
path (H.264, MPEG-2/4, VP8). HEVC/AV1/VP9 with unknown transfer still fall back to Ultra.

### Fold8 check for diagnostic.7
Same session procedure as diagnostic.4. Success requires `confirmed_pass` with the matching model and
`effective=Neural`/`AI Max` in Quality pipeline, plus a visual comparison against Ultra on the same clip.

## diagnostic.7 Fold8 result

Same Fold8, base Ultra, AI Max for SD/HD, direct MediaCodec, 640x480 H.264 at 29.97 fps, fullscreen unfolded:
`submit geometry=640x480->2448x1836` followed 0.7 s later by `phase=CONFIRMED, confirmed_pass id=4,n8=0,n16=1,passes=32`.
No renderer failure was recorded. Twelve consecutive one-second samples kept `n16=1, passes=32` with
frame-drop 0, decoder-drop 0 and vo-delayed 0; the summed pass average was 17.4-23.3 ms per frame.
A following 1920x1080 clip with Neural requested was correctly `bypassed_no_upscale` (2448x1377 is < 1.3x).

Conclusion: the textureLod/samplerExternalOES diagnosis is confirmed by device behaviour (same clip and
geometry failed in diagnostic.5, renders in diagnostic.7). AI Max actually executes on the Fold8 GPU.
Still open: Neural (8-channel) has not yet been observed in `confirmed_pass` on the device (only bypass);
image-quality comparison vs Ultra; 60 fps or larger sources, where ~23 ms of summed pass time per frame would
exceed a 16.7 ms frame budget; heat and battery.

## diagnostic.9: performance step-down

While a model is confirmed, `PerformanceGuard` samples `frame-drop-count + vo-delayed-frame-count` once per second.
It ignores 3 grace seconds after confirmation, a seek or restart, plus paused time and counter resets. It downgrades
when 3 of the last 5 seconds each lose >= 2 frames, using the existing fallback chain with reason
`performance_frame_drops` and a `performance_fallback` report line (mode, lost frames, fps, geometry).
Thresholds are first estimates from the 30 fps Fold8 samples (0 drops with AI Max at 640x480) and still need
device tuning.
