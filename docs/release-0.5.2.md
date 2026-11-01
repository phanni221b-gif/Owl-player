# Owl 0.5.2

Owl 0.5.2 adds storage-scoped browsing and name search while preserving the 0.5.1 playback and removable-storage stability work.

- Choose all storage, internal storage, or an attached removable storage from the video-folder screen.
- Switch to hierarchical storage browsing to navigate real directories instead of only folders that contain videos.
- Search folder names and video file names recursively below the current browsing location.
- Search from an Owl video folder is scoped to that folder and its descendants when a filesystem path is available.
- The last selected storage is remembered. If a selected removable storage is disconnected, Owl safely falls back to the primary storage.
- `Android/data` and `Android/obb` remain excluded from direct traversal.
- Search and directory traversal are cancellable and stop when the browser leaves the foreground or the Owl session is ended from Recents.

Playback core, native dependency revisions, video-sync, hwdec, scaler and gpu options are unchanged from 0.5.1.
