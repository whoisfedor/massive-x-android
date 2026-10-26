# Changelog

## 0.5.0 (1) — Android Public Testing · v0.5.0-beta.1

First public Android source export and release-signed sideload APK. Includes protected create/private-key/YAML-v1 import, watch-only/multiple-wallet management, public balances/activity/Receive, optional price estimates and balance notifications, six locales, native themes and four owner-supplied icons with Classic default.

Mainnet Send is enabled for owner-reviewed, freshly authorized transfers with local signing, durable attempt records, no uncertain resend and conservative source-lock/finality handling. Publication testing uses only mocks and disposable wallets; no real operation was submitted.

Public screens can be captured; sensitive entry/reveal is protected. Copied keys retain their system-clipboard lifetime independently of reveal masking. The Android privacy link now opens the Android-specific repository disclosure rather than an iOS-specific website policy. No cryptographic/storage/journal/transfer algorithm change accompanies publication.

Known limitations and verification scope: [release security](docs/RELEASE_SECURITY.md). This is a pre-release, not a stable/store release or independent security audit.
