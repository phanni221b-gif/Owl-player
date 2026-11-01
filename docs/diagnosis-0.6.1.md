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
