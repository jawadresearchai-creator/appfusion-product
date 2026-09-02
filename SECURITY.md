# Security boundary

Do not commit APKs, decompiled material, source-aware Foundry records, signing keys, service credentials, or production secrets.

Report suspected clean-room contamination by stopping Product work, preserving evidence, and opening a private security issue. Do not “clean up” or delete evidence before review.

Release signing occurs only in the protected `release-signer` environment after artifact provenance, Blueprint approval, tests, security, and licensing checks pass.
