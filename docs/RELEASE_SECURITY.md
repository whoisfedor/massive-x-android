# Public testing security and release verification

Scope: Android 0.5.0 (1), tag `v0.5.0-beta.1`, public testing. No independent audit, Google Play approval, universal OEM acceptance or funded end-to-end acceptance of this exact signed APK is claimed. Begin with disposable, unfunded wallets and keep a real independent backup before considering funds.

## Development transfers and evidence scope

The owner reports personally completing successful real Massa Mainnet transfers during Android development. This is owner-reported, owner-performed development history; it is separate from automated qualification of the exact signed `v0.5.0-beta.1` APK.

No independently verified transaction receipts or artifact-to-transfer evidence are supplied here. The documented release qualification used mocks/disposable wallets and real read-only requests, without submitting a real Mainnet operation. Historical owner transfers therefore do not establish funded end-to-end acceptance of that exact artifact, which remains **unverified**. Automated results, hashes, signing details and source/tag references retain their original scope.

## Source and network boundary

Mainnet Send is enabled in the exact Release build. Immutable source-bound review, deliberate confirmation, fresh protected authorization, exact local signature verification, durable SIGNED and SUBMIT_ATTEMPT records/read-back and a one-use submission permit precede a single official HTTPS write. No launch/background automatic broadcast or uncertain resend exists. Test adapters are not packaged in Release.

The fixed official RPC has validated chain/context and strict supported-version parsing; address disclosure follows context validation. Transport is bounded, platform-TLS-only, redirect-free and cancellation/age-checked before writing operation bytes. No automatic write retry occurs. Exact matching separated official final reports and durable terminal resolution precede source unlock. Speculative, expired, pruned, unavailable and conflicting evidence stays locked. Explorer display status does not establish finality; iOS historical-explorer recovery is not implemented here.

Per-wallet nonexportable Keystore AES-GCM wrapping uses fresh protected action approval and identity-bound authenticated metadata. Ed25519/BLAKE3/encoding and exact fee arithmetic remain the existing pinned implementation. Debug and Release share the same guarded transfer path. Private keys are never intentionally sent to the public providers.

## Limits to understand

- Provider honesty/availability, unversioned explorer history and incomplete/pruned records remain trust/availability limits.
- Root/in-UID compromise, complete local rollback, re-import after losing all journal evidence and simultaneous use of a key on multiple devices are not solved.
- Keystore loss/invalidation and app/device loss need a valid offline backup; Android backup/transfer is excluded. Hardware backing is not assumed on all devices.
- Public pages are screenshotable. Sensitive entry/reveal is protected, but cameras/compromised OS and managed-memory copies remain risks.
- Authorized copied keys persist on the system clipboard independently of reveal masking; Android may clear them and other software may retain them. Manual Clear never wipes a newer unrelated clip.
- Optional balance checks are OS-scheduled, not instant push; force-stop, permission/battery/network restrictions can suppress them.
- Funded end-to-end acceptance of the exact signed release APK is not established by the documented qualification. Owner-reported development transfers, APK signing and passing mocks do not establish that artifact’s paid-settlement acceptance.

## Publication gates

The public repository is a fresh audited export, not the private development history. Public vectors are explicitly compromised fixtures; real signing keys/passwords, wallet data, machine paths, device identifiers, internal logs and unapproved images are excluded. Original code is MIT; artwork/trademarks remain separate. Gitleaks results are reviewed with narrow, documented public-fixture exceptions, not silently treated as owner credentials.

Current source tests/build/lint, signed APK metadata/certificate/alignment, isolated install/launch/create/import/public-read checks and anonymous download verification are recorded with actual outcomes in `PUBLICATION.md` and release notes. Previous development reports are not carried over as current release evidence. A blocked check is labeled unverified; no known unresolved demonstrated CRITICAL/HIGH finding is accepted for publication.

Five application permissions: INTERNET, USE_BIOMETRIC, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, ACCESS_NETWORK_STATE, plus AndroidX’s signature-protected internal receiver permission. One OS BIND_JOB_SERVICE-protected balance job; no camera, analytics SDK, arbitrary RPC setting, cleartext fallback or Send deep link. The default icon is Classic; changing icons does not migrate wallet data.

## Privacy and disclosure

[Android disclosures](../PRIVACY.md) accurately cover this build; the linked website policy remains iOS-specific and needs an Android update. In-app Privacy opens the Android disclosure. Security reports: [info@massive-x.com](mailto:info@massive-x.com), never keys or device credentials. See [SECURITY.md](../SECURITY.md).

## Beta.2 update scope

VersionCode 2 uses the existing package and publisher certificate for in-place upgrades. Product, authorization, cryptographic/storage, journal and transfer behavior are unchanged. New artifact qualification and beta.1 upgrade receipts must be reviewed separately from the historical beta.1 results; neither establishes funded end-to-end acceptance or an independent audit. Original binary source/checksum identities remain valid after publication-history maintenance.
