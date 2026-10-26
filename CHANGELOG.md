# Changelog

## 0.5.0 (2) — Minor Maintenance Update · v0.5.0-beta.2

Higher-versionCode release packaging and documentation update, using the existing publisher signing identity. Application behavior, cryptography, wallet storage, transaction journal, guarded Mainnet Send, artwork and dependencies are unchanged. In-place beta.1-to-beta.2 upgrade and fresh-install checks passed with disposable wallets; see [release verification](PUBLICATION.md).

## 0.5.0 (1) — Android Public Testing · v0.5.0-beta.1

First Android testing release: protected create/private-key/YAML-v1 import, watch-only and multiple wallets, public balances/activity/Receive, optional estimates and balance notifications, six locales, native themes and four icons with Classic default.

Mainnet Send supports owner-reviewed, freshly authorized transfers with local signing, durable attempt records, no automatic uncertain resend and conservative finality/source locks. Public screens permit capture; sensitive entry/reveal is protected. Copied keys retain their system-clipboard lifetime independently of reveal masking. The in-app privacy link opens the Android-specific disclosure.

For security limitations and the no-independent-audit qualification, see the [security model](docs/RELEASE_SECURITY.md).
