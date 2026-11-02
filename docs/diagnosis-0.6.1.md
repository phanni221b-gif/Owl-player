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
