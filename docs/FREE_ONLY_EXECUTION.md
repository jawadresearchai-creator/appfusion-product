# Free-only execution

Maximum new service charge is zero. No billing activation, payment details,
subscriptions, paid trials, top-ups, overages, paid model runtimes, or paid signing
services. Monthly free tiers are not accepted as the permanent solution.

## Current state

Hosted workflows are disabled. All jobs also require an exact
`APPFUSION_FREE_APPROVED_SHA` match before allocation; absent approval skips them.
This guard is not a billing API. Approval must follow verified provider evidence.
Skipped jobs are not accepted tests. No repository has been made public.

The optional local adapter already passes Product boundary verification without
hosted Actions: `python scripts/verify_product.py`. Existing hardware can run
Android build/emulator gates directly, with accepted evidence saved in Drive.

## Quota-independent choices

1. A genuinely public Product source repository using standard GitHub runners:
   current policy has no monthly build-minute allowance. Requires explicit user
   approval of the reviewed publication set first. Never publish input APKs,
   private analyses, user documents, secrets, signing keys, or existing Git
   history without review. Never create a public facade that secretly builds
   private source to work around private CI quotas. Account restrictions and
   per-job/concurrency limits still apply; provider policy is not a forever guarantee.
2. Existing authorized hardware: no build-service minute quotas or service
   payment, but hardware/electricity/maintenance are not literally free. Android
   works on the existing Windows host. iOS requires an existing authorized Mac.
   This is optional and cannot silently replace the cloud-first requirement.

Private + cloud-only + quota-independent + zero service charge is not a verified
available configuration. Record that constraint rather than promising unlimited
free private macOS compute.

## Drive is storage, not a build machine

Drive stores inputs, source archives, checkpoint projections, logs and delivered
apps. An executor downloads needed inputs, builds/tests, then uploads artifacts.
Drive itself cannot launch Gradle, Xcode, Android Emulator or iOS Simulator.
Apps Script has execution quotas; Colab is separate compute with variable limits,
not a permanent unattended CI host.

Codemagic's monthly free tier was investigated and rejected as the permanent
solution. No account was connected, no source uploaded, and its draft workflow
was removed.

## Existing iOS gate

The tested Keychain/runtime and packaging commands remain unchanged inside the
construction workflow. No new iOS runtime result is claimed. A free macOS
executor and iOS J1 UI implementation/validation are still required.

## Sources checked 2026-09-04

- [GitHub standard public runners](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job)
- [Drive is file storage](https://developers.google.com/workspace/drive/api/guides/about-sdk)
- [Apps Script limits](https://developers.google.com/apps-script/guides/services/quotas)
- [Colab limitations](https://research.google.com/colaboratory/faq.html)
