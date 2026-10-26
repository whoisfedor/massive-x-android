# Changelog

## 0.5.0 (2) — Android Public Testing · v0.5.0-beta.2

New higher-versionCode update with the existing signing identity. Product behavior, cryptography, wallet storage, transaction journal, guarded owner-initiated Send, artwork and dependencies remain unchanged. Four publication stages retain their original metadata; only local-brief ignore policy is cleaned up. Original beta.1 source and artifact identities remain valid and are not relabeled.

Qualification receipts for this new artifact accompany its release. The release gate requires forced JVM regressions, native/UI checks, exact signed-APK acceptance and a data-preserving beta.1-to-beta.2 upgrade with disposable wallets. No independent audit or funded end-to-end acceptance is claimed.

## 0.5.0 (1) — Android Public Testing · v0.5.0-beta.1

First public Android source export and release-signed sideload APK. Includes protected create/private-key/YAML-v1 import, watch-only/multiple-wallet management, public balances/activity/Receive, optional price estimates and balance notifications, six locales, native themes and four owner-supplied icons with Classic default.

Mainnet Send is enabled for owner-reviewed, freshly authorized transfers with local signing, durable attempt records, no uncertain resend and conservative source-lock/finality handling. The publication qualification tests used mocks and disposable wallets; those tests submitted no real operation.

The owner reports personally completing successful real Massa Mainnet transfers during Android development. This owner-performed development history is distinct from qualification of the exact signed `v0.5.0-beta.1` APK. No independently verified receipts or artifact-to-transfer evidence are supplied here, so funded end-to-end acceptance of that published APK remains unverified. This documentation clarification does not change the original automated results, artifact, signing identity or release tag.

Public screens can be captured; sensitive entry/reveal is protected. Copied keys retain their system-clipboard lifetime independently of reveal masking. The Android privacy link now opens the Android-specific repository disclosure rather than an iOS-specific website policy. No cryptographic/storage/journal/transfer algorithm change accompanies publication.

Known limitations and verification scope: [release security](docs/RELEASE_SECURITY.md). This is a pre-release, not a stable/store release or independent security audit.
