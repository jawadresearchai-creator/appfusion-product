# Clean-room transfer boundary

Permitted inbound artifacts:

- one schema-valid `ProductBlueprint`;
- one schema-valid `ProductApprovalAttestation` whose hash matches that blueprint;
- independently permitted fixtures declared by the blueprint;
- public Product-engine documentation and dependencies selected through the feasibility gate.

Forbidden inbound material includes `FoundryDecisionDossier`, source app/package identities used as behavioral references, APKs, JADX/apktool/smali output, proprietary assets, reverse mappings, source-specific evidence locations, and instructions to copy a named implementation.

The Product implementation context must be fresh and must not have a Capability Foundry checkout or credential. Human owners may administer both repositories, but automation identities and working contexts remain separated.
