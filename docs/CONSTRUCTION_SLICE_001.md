# Product Construction Slice 001 — Shared Contract Feasibility

Status: implementation probe under the approved ProductBlueprint.

## Purpose

Establish the smallest clean-room, cross-platform core that can be compiled and tested independently of UI, camera, OCR, database, notification, and cryptographic providers.

This slice implements the approved shared contracts only:

- typed opaque `EntityRef` values;
- normalized `SearchProvider` results plus deterministic federated composition;
- `ScheduleRequest` transport intent with explicit time-zone and missed-trigger semantics;
- `SecureBlobStore` metadata/ciphertext boundary without choosing a cryptographic provider;
- versioned `BackupAdapter` records;
- append-only `ActivityEventLog` semantics.

## Feasibility stack

For this probe the build uses:

- Kotlin 2.4.10;
- Android Gradle Plugin 9.3.0;
- Gradle 9.5.0;
- JDK 17;
- the dedicated Android Kotlin Multiplatform library plugin;
- Android compile SDK 36 / min SDK 26;
- iOS ARM64 device and ARM64 simulator targets.

These versions are probe inputs, not irreversible product locks. Engine locking occurs only after the CI evidence passes.

## Gates

The construction workflow must prove:

1. the approved ProductBlueprint + attestation still pass the clean-room boundary;
2. common/JVM tests pass on Linux;
3. Android KMP library code compiles on Linux;
4. common code executes through the Kotlin/Native iOS simulator test runner on an ARM64 macOS host;
5. an iOS simulator framework links successfully;
6. no prohibited package/decompilation artifacts enter the Product repository.

A failure is repaired within this construction loop unless it requires a semantic ProductBlueprint deviation or an external authorization/security gate.
