# Construction Slice 003 — First Installable Shells

## Purpose

This slice converts the proven shared contracts into application artifacts. It deliberately precedes another infrastructure-only checkpoint.

## Android

The Android app is a real application module with an installed entry activity. It composes Room3 metadata, Android Keystore key wrapping, filesystem-backed SecureBlob persistence, startup reconciliation, encrypted document creation, authorized search, and verified decryption/reopen behind a small platform runtime. The first UI is intentionally narrow: create, encrypt, search, and open a private text document.

## iOS

The iOS Xcode target is a simulator-installable UIKit shell linked to the KMP framework. It verifies the Apple Keychain adapter at launch and exposes the same initial product workspace. Its document controls remain an explicit Journey J1 connection gate; the shell must not be reported as feature-complete.

## CI routing

Product Construction CI now classifies changed paths before spending platform minutes:

- Android application changes build artifacts and run the installed J1 emulator journey; docs-only changes do not trigger Product Construction CI.
- iOS application-only changes run the iOS gate and build the simulator `.app` bundle.
- shared-core changes still run JVM, Android device, and iOS contracts.
- workflow/build-system changes run all gates.

The Android debug APK, release APK, release AAB, and zipped iOS simulator application are uploaded as immutable run artifacts. Release readiness remains false until required journeys, reports, screenshots, checksums, and zero-open-critical-defect evidence pass.

The Android J1 harness installs the debug APK, creates an encrypted document through the rendered UI, force-stops the application process, relaunches it, searches for the persisted metadata, decrypts and opens the document, and uploads screenshot, hierarchy, result, and log evidence.
