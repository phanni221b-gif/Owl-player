# Owl 0.6.1-diagnostic.5

Device-verification build. It is not a confirmed Neural/AI Max fix for Galaxy Fold8: the shader compile failure remains under investigation (see [audit](audit-0.6.1.md) and [diagnosis](diagnosis-0.6.1.md)).

- Fix data loss: folder delete removes only the folder's videos and empty directories; storage roots and shared folders are protected from delete/move/rename.
- Verified delete results, separate "missing file" reporting, and single file/folder rename.
- Two-finger pan follows the fingers 1:1 at every zoom level and never drifts on an axis that fits the screen.
- Resume position saved for every played playlist entry, on background and on task removal.
- Message when a file cannot be opened; centered play/pause control.
- Includes the 0.6.1 diagnostic.4 renderer diagnostics and folder unseen-video badges.
