# Product Construction Slice 002 — SecureBlob Cross-Platform Feasibility

Status: locked implementation boundary under the approved ProductBlueprint.

Product: approved personal vault and cadence workspace

Blueprint SHA-256: `2120f5989ec35562377bd42e032018bd0004916d5ea4c56005f8e7048414b6fc`

Milestone: `SECUREBLOB_CROSS_PLATFORM_FEASIBILITY_LOCKED`

Occurred at: `2026-09-03T04:31:57Z`

## Decision

Lock the product-owned `SecureBlob` envelope contract and its replaceable platform key-wrapper boundary. The envelope uses a fresh AES-256-GCM data key per protected blob, authenticates its version, key identity, and wrapped data key as associated data, rejects malformed or non-canonical encodings, and supports forward decrypt-and-re-encrypt migration.

Lock the Android adapter to a non-exportable Android Keystore AES wrapping key. Accept the Apple Keychain adapter as a simulator-proven feasibility implementation behind the same replaceable boundary. This milestone does not claim Secure Enclave backing, physical-device behavior, or that symmetric Keychain material is non-exportable to the owning application.

## Evidence

Final Product commit: `0b49de20b6b0653f8c66d7ebabc026012599d91d`

- Product Boundary CI run `33715028074`: PASS.
- Product Construction CI run `33715028079`: PASS.
- Android/JVM shared contracts: PASS.
- Android API 35 Keystore device probe: PASS.
- iOS Simulator ARM64 shared contracts and framework link: PASS.
- Xcode-signed iOS simulator host Keychain probe: PASS (`APPFUSION_KEYCHAIN_PROBE=OK`).

The shared contract tests prove strict canonical envelope parsing, fresh per-blob encryption, ciphertext and wrapped-key tamper rejection, active-key identity rejection, and version 1 to version 2 migration by decrypting and re-encrypting. The Android runtime test proves Keystore key creation, non-exportability through the application API, wrap/unwrap, tamper rejection, and cleanup. The Apple host probe proves Keychain persistence across wrapper instances, wrap/unwrap, SecureBlob round trip, tamper rejection, and cleanup.

## Harness correction history

The original hand-built iOS simulator host reached the real Keychain API but returned `errSecMissingEntitlement (-34018)`. Adding hand-authored entitlement values and DER entitlements did not create a valid platform identity and later caused SpringBoard launch denial. The final correction replaced that host with a minimal Xcode project so Xcode generates and signs the simulator application identity. The resulting signed host launched normally and passed the Keychain probe.

The Android emulator also exposed one infrastructure-only `ddmlib` property-fetch timeout. The final workflow builds the device-test APK before emulator startup, constrains emulator resources, waits for stable ADB readiness, and then runs the same Keystore runtime assertions successfully.

## Locked boundary

- Envelope format and authenticated metadata semantics are product-owned and versioned.
- A new data key is generated per protected blob.
- Long-lived key wrapping is exposed only through `DeviceKeyWrapper`.
- Android wrapping keys remain non-exportable in Android Keystore.
- Apple secure-key storage stays replaceable; this simulator proof is not a physical-device or Secure Enclave attestation.
- Metadata and ciphertext remain separate at the storage boundary.
- Tamper, wrong-key, malformed-input, and migration behavior remain acceptance gates.
- Foundry and source-aware artifacts remain prohibited from the Product repository.

## Next safe action

Implement the first bounded `document_vault` module checkpoint by composing the locked Room3 persistence and SecureBlob boundaries through product-owned repository contracts. Cover encrypted create, read, update, and archive lifecycle; metadata/ciphertext separation; atomic failure behavior; authorized search projection and activity-event emission; and versioned backup records. Keep camera, OCR, PDF export, UI, Foundry artifacts, and dynamic behavioral-reference execution outside this slice.
