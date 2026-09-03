# Construction Slice 004 — Installed Android Journey J1

## Accepted result

The Android emulator completed encrypted document creation, process force-stop, relaunch, search, verified decryption, and reopening through the rendered application UI. The result is bound to tested source commit `99e3e83bbbe92406b9010b56c9da05e8b8f0fea7` and APK SHA-256 `733f0bebdc48260efc4c53fe4dd1e45415353d3af0b4036466c218e967613137`.

The final screenshot was inspected. A system-status-bar overlap found in the first pass was repaired, then the same journey passed again. Save and Search now wait for secure-workspace startup recovery. The portable Python harness refuses non-emulator devices and resets only the test application before installation.

## Reproducible evidence

- [J1-verified Android debug APK](https://drive.google.com/file/d/1o2zUqBzq_dVmKk9RRbvZfYf4aQ86BDrH/view)
- [Test, build, hierarchy, screenshot and log evidence](https://drive.google.com/file/d/1c49tfSJozfdrg3ohd7kbzI1J0SViql43/view)
- [Verified opened-document screenshot](https://drive.google.com/file/d/1F-_lWIb9I-mKpmTgceF2EaZSU6ej2pcL/view)

Evidence archive SHA-256: `5ee5b6d5d07a5df214eac0e4682d097b7c832001f3bab3cb24b59fc26ff09cfb`.

The optional Windows executor passed:

- 25 shared JVM contract tests;
- four Android emulator security/runtime tests;
- eight Python boundary/harness tests;
- the exact-hash clean-room blueprint transfer check;
- the installed Android J1 journey.

## Hosted executor status

GitHub Product Boundary run `33789692285` and Product Construction run `33789692286` did not start because GitHub reported an account payment/spending-limit problem. They are not recorded as test passes. No paid overage was enabled, and repeated hosted retries were avoided.

The Android change was verified locally with the same application build and device-test commands plus the portable UI harness. Shared and iOS implementation source are unchanged from gated Product merge `b1ccbd1b56bbd06a178d9f97a7209a8c154376d4`; the earlier iOS gate remains evidence for that unchanged source, not for unfinished iOS J1.

## Remaining gates

Android J1 is accepted. Cross-platform J1 is not complete until the iOS UI is wired and validated on an authorized macOS executor. J2, J3, distribution signing, and final release packaging also remain outstanding. This debug APK is a development prototype, not a production-security or store-release claim.
