# Owl 0.5.1

USB/HDD discovery now publishes internal results before removable provider queries. Provider calls use CancellationSignal with an eight-second budget and bounded isolated workers; failures preserve internal results and fall back to cancellable direct discovery.

Removing Owl from Recents requests functional session shutdown (scans, thumbnails, playlist jobs, diagnostics, media session, background playback and notification), without killing the Android process. Home navigation keeps normal background-playback behavior. Settings, history and sort preferences are retained.

Same applicationId and private release certificate as 0.5.0. Protected playback core, native revisions and video-sync/hwdec/scaler/gpu options are unchanged. Fold8 vendor-provider cancellation, USB removal and task removal still require device verification; ignored platform cancellation cannot be forcefully interrupted.
