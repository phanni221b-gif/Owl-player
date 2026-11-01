# Owl 0.6.0

- Fix cold-start folder loading: pending requests start from the actual lifecycle STARTED event, with coalescing, force escalation, generation guards and stop/restart recovery.
- Retain internal-first external storage discovery; isolate filesystem reads with bounded workers and metadata batches; debounce foreground HDD mount/unmount refresh.
- Improve Ultra by removing inherited fast-profile quality limits while preserving decoder selection and frame pacing.
- Add opt-in Neural and AI Max automatic resolution rules, with right-side settings buttons. AI Max wins overlapping buckets. Both are real GPU FSRCNNX CNNs (8/16 channels), not NPU.
- Verify bundled models and actual CNN render passes; fail safely through lower tiers. SDR models require actual >1.3x enlargement in both axes; HDR/unknown transfer uses Ultra. Dormant no-upscale shaders can activate after zoom/rotation without per-frame Java decisions.
- Expand private-path-free source/quality/backend/fallback, renderer and scan diagnostics. Preserve 0.5.3 two-finger pan and bounds.

Dedicated release signing and applicationId com.phanni221b.owlplayer are retained. The release APK is allstorageRelease / arm64-v8a, not a debug build.

Model shader license: LGPL-3.0-or-later. Original/modified source, full licenses and attribution are bundled and available in settings. See [implementation, references and physical-device checklist](quality-0.6.0.md).

Automated JVM/lifecycle/native/parser/Mesa GLES/build checks do not certify Fold8 image quality, Android-driver compatibility, sustained performance, heat or battery. Physical-device tests remain required; AI Max is the larger provided model, not a universal measured quality guarantee.
