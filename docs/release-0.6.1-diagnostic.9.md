# Owl 0.6.1-diagnostic.9

Diagnostic build.

- New: automatic quality step-down on sustained frame loss. While Neural or AI Max is confirmed running, Owl checks dropped and late frames once per second. If 3 of any 5 seconds each lose 2 or more frames, it steps down AI Max → Neural → Ultra without interrupting playback. Seeks, pauses and the first 3 seconds after starting are ignored, so a single hiccup never triggers it. The diagnostics report records it as `performance_fallback` together with the reason.
- Recommended for the unfolded Fold8: AI Max for 480p and below, Neural for 720p, Ultra for 1080p and above (1080p is below the 1.3x upscaling threshold on this screen, so the models would not run anyway).
- Still to verify on the device: whether Neural runs at 720p without dropping frames (`confirmed_pass n8=1`), and that the step-down triggers only under real stutter.

Includes everything in 0.6.1-diagnostic.8 (fix for the false "재생할 수 없습니다" pop-up).
