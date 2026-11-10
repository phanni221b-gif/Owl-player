# Owl — working rules for Claude

## Every requested change ships as a GitHub Release
The owner wants every change they request published to GitHub Releases, so the APK is easy to find.
Do not stop at a branch, a PR or an Actions artifact.

1. Bump `versionCode` and `versionName` in `app/build.gradle`. Use `X.Y.Z` for a finished release and
   `X.Y.Z-diagnostic.N` for a build that is still incomplete or for device diagnosis.
2. Add `docs/release-<versionName>.md`. The release workflow publishes it as the release notes and fails without it.
   State plainly whether the build is a finished release or diagnostic, and what still needs device verification.
3. If a protected file listed in `.github/workflows/release-apv.yml` changes on purpose, update its SHA-256 there
   and explain why in the commit.
4. Push the work, then create the branch `maintenance/release-v<versionName>` from that commit
   (the GitHub API works when tag pushes are refused). The "Release Owl" workflow builds, signs, tags
   `v<versionName>` and publishes the release. Never reuse or replace an existing tag.
5. Wait for the run, confirm the release has the APK, and give the owner the direct link:
   https://github.com/phanni221b-gif/Owl-player/releases/tag/v<versionName>
6. Merging into `main` still needs the owner's explicit request.

## Reporting
- Talk to the owner in Korean.
- Use these verdicts: 검증 완료 / 구현됐으나 미검증 / 부분 구현 / 미구현 / 결함 확인.
- Never report a fallback to Ultra as Neural/AI Max success. Never claim a result on a physical Fold8 without real evidence from the device.

## Background
- See `docs/audit-0.6.1.md` for the requirement matrix, the shader investigation and the Fold8 checklist.
- Folder operations must never touch volume roots or shared top-level directories (`OwlFileSafety`).
