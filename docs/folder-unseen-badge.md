# Folder unseen-video badge

Folder cards now show a small circular count on the upper-right of the folder icon. Zero hides the badge; counts above 99 display 99+, with the exact count in the accessibility label.

Unseen means no known playback in Owl. Opening a folder does not clear it. MPVActivity records a hashed local/content source identity on MPV_EVENT_PLAYBACK_RESTART (initial playback or a completed seek), including playlist advancement and background playback while the player activity remains alive. This is not a watch-to-completion counter. No decoder, renderer, frame pacing, scan or storage-query settings are changed. Failed opens that never reach playback restart do not mark the video.

Seen state is independent of resume state and is not removed by completion or clearing the resume position. Existing surviving position records are imported into seen state; videos completed before this feature with no surviving record cannot be reconstructed. Keys are hashes, not raw filenames or paths. Local paths and file URIs share an identity; content-only videos use the content URI. Network/transient descriptor/playlist sources are ignored.

Counts use the current discovered folder snapshot without further storage scans, shared-preference reads or hashing during item binding. Counting runs off the UI thread, cooperates with cancellation per video, and publishes only for the current adapter generation. Only changed badges use payload updates. Returning to the folder list recomputes counts; disappearing/deleted files leave the discovered list and therefore the count. Sorting, filtering and selection retain existing behavior.

This feature is included in 0.6.1-diagnostic.3 alongside the existing GPU diagnostic.2 functionality. It does not claim a Neural/Adreno fix, and the draft GPU investigation remains open.

Validation: eight persistence/migration/identity/count/cancellation tests, full existing unit/lint suite, protected playback/native baseline, Mesa/GLES validation and signed arm64 release APK CI. Physical Fold UI appearance and real playback behavior still need device confirmation.
