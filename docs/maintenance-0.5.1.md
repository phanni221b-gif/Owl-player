# Owl 0.5.1 USB/HDD discovery and task-exit validation

Baseline: v0.5.0 / main 4023b89. No protected playback-core/native-option changes.

## Confirmed application cause and remaining device uncertainty

0.5.0 enumerated and queried all MediaStore volumes sequentially before publishing any snapshot. The volume set did not ensure primary-first order. A slow external query (or volume enumeration) therefore hid even successfully discovered internal media. Query/iteration also held the global scan mutex; Job cancellation did not cancel a blocked ContentResolver.query call. These application-level blocking paths are confirmed from source. The exact Fold8 vendor Binder/provider call that hung is not established without a device trace.

## Discovery changes

- Query external_primary directly before removable-volume enumeration, then publish immediately. Merge/copy/sort work runs off the UI thread.
- Each provider query gets its own CancellationSignal; the coroutine suspension bridges a bounded worker instead of blocking a coroutine dispatcher under a global lock.
- Queries and external volume enumeration have an eight-second budget. Query timeout/cancellation requests CancellationSignal.cancel and Future interruption. Cancellation dispatch is itself isolated so a vendor's cancel Binder cannot hang the UI.
- Independent primary/external lanes have at most two workers each and no pending queue. A provider ignoring cancellation is quarantined; saturated lanes fail promptly instead of adding unbounded threads. A canceled continuation is detached so the blocked worker does not retain its Activity.
- Scan ownership/generation rejects stale publications. Only actual direct filesystem traversal holds a mutex. A new request does not wait for an unresponsive old traversal; it keeps provider results and skips the busy direct pass until a later refresh.
- Direct discovery remains read-only, incremental, cancellable and deduplicated. Changed results publish at most every 1.5 seconds, including directory-boundary progress, with a final snapshot at completion. Provider errors/timeouts/disconnection preserve internal results and attempt direct reconciliation where permitted.
- Folder refresh queries the selected volume/path and reads only that immediate directory. It never invokes global scan.

## Task removal and fresh launch

OwlTaskService observes removal while a browser/player session is active; BackgroundPlaybackService also handles onTaskRemoved. stopWithTask is false so Android delivers that callback rather than silently stopping the service first. Both services are non-sticky. Service removal callbacks carry their session generation so a delayed old callback cannot close a newly launched session. Home/onStop is not a session-exit event.

The idempotent runtime coordinator cancels scans, thumbnail subscribers/queued work, file jobs and playlist preparation; stops diagnostics and clears runtime samples; stops playback through the Activity shell; detaches playback-service observers and delayed thumbnail callbacks before native destruction; releases the media session; removes foreground status/notification and stops services. Late notification/media-session callbacks are guarded. Activity onDestroy owns native destruction exactly once.

A new root launcher task resets process caches and old runtime hooks if no live existing Owl task is registered. Home return and configuration recreation do not reset the session. Preferences, sort state and persisted playback history are not cleared. A new launcher start is the fallback if Android discarded the lightweight non-foreground browser task monitor while idle. The monitor creates no browser notification.

No Process.killProcess, System.exit or forced process termination is used. Cached processes are allowed.

## Additional shell work

VideoAdapter submits the list before reading history; only changed progress rows receive payload updates. Existing scaled thumbnail decode, dedup, ListAdapter, signing identity and native revision lock remain. File operations now check cancellation between items/copy chunks. An incomplete cross-volume copy never causes deletion of its source; a complete destination is retained if cancellation occurs during source cleanup. Already-completed deletions/moves are not rolled back.

## Limits and Fold8 verification

Cancellation is cooperative. Android/vendor calls such as query, remote cancellation, listFiles, a currently-running codec extraction or provider delete may ignore interruption. Owl bounds/isolate provider work and rejects stale results; it does not claim to forcibly stop vendor/kernel I/O. UI callbacks and preferences are not used to simulate process killing. Physical USB activity, foreground-service/task-removal delivery, PiP and JNI lifecycle races need device verification.

Storage phase timings/outcomes (latest 40, no source filenames) are included in the copyable diagnostics report and OwlMediaScan log. Record a new launch with HDD attached to distinguish primary vs removable timeout.

| Fold8 check | Expected | Status |
| --- | --- | --- |
| A: launch without HDD | Internal folders appear promptly | Device pending |
| B: launch with HDD | Internal first; external merges later; no indefinite whole-screen loading | Fake slow-source test passes; device pending |
| C: disconnect during discovery | Internal remains, scan finishes/cancels, no crash | Failure/cancel rules tested; device pending |
| D: remove task during scan | Jobs/subscribers stop; no notification; next launch fresh | Coordinator tests pass; device pending |
| E: remove task during playback, background play enabled | Playback/session/foreground service/notification stop; next launcher Main | Shell reviewed; device pending |
| F: press Home only | Normal background-play preference preserved; no full session reset | Separate lifecycle path reviewed; device pending |

Real-device measurements and vendor cancellation responsiveness are not inferred from unit tests. Existing native revisions, video-sync, interpolation, hwdec, scaler, gpu/gpu-next and protected hash baseline remain unchanged.
