# Owl 0.5.3

Owl 0.5.3 restores responsive two-finger panning while keeping the bounded-pan safety introduced in earlier releases.

- Restore bounded pan sensitivity from 0.45 to 0.55.
- Remove the low-pass pan smoothing that made two-finger movement feel heavily damped.
- Keep pan boundaries based on `video-align-x/y`, so the picture cannot be dragged into empty space.
- Keep pinch zoom limits and pinch-distance jitter suppression unchanged.
- Storage browsing, recursive search, removable-storage handling, Recents exit behavior, playback core and native dependency revisions remain unchanged from 0.5.2.
