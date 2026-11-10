# Owl 0.6.1-diagnostic.8

Diagnostic build.

- Fixed: every video showed a false "재생할 수 없습니다" pop-up naming the folder's last file. Owl opens a folder as one in-memory playlist, and diagnostic.7 wrongly reported that playlist step as a failed file. Playlist containers are no longer reported; real files that fail to open still are.
- Fold8 result from diagnostic.7: AI Max (FSRCNNX 16) now really runs on the Fold8 GPU (`confirmed_pass n16=1`, 640x480 → 2448x1836, no dropped frames at 30 fps). This is the fix for the earlier shader compile error.
- Still to verify on the device: Neural (8-channel) with a clip that actually needs upscaling (480p/720p), an image-quality comparison against Ultra, and 60 fps sources.

Includes everything in 0.6.1-diagnostic.7.
