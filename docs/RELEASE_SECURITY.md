# Public testing security model

Scope: Android **0.5.0 (2)**, `v0.5.0-beta.2`. No independent audit, independently verified funded end-to-end acceptance, Google Play approval or universal OEM qualification is claimed. Use disposable, unfunded wallets for testing and keep an independent offline backup before considering funds.

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

## Verification and platform scope

[PUBLICATION.md](../PUBLICATION.md) records exact source/APK identities, regression results, signed-install/update acceptance and prior beta.1 evidence. Tests use compromised public fixtures and fake write transports; real read-only requests are identified separately. Known test-vector secret-scan detections are reviewed explicitly, not treated as owner credentials. No known unresolved demonstrated CRITICAL/HIGH finding is accepted for release.

VersionCode 2 uses the same package and publisher certificate. In-place upgrade acceptance covers retained catalog, journal, source locks, settings and fresh-auth backup on API34 ARM64 emulators. It does not establish other-OEM/API26–29 runtime support, signed-release Xiaomi/Android16 acceptance or 16 KB-page hardware runtime qualification.

Five application permissions: INTERNET, USE_BIOMETRIC, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, ACCESS_NETWORK_STATE, plus AndroidX’s signature-protected internal receiver permission. One OS BIND_JOB_SERVICE-protected balance job; no camera, analytics SDK, arbitrary RPC setting, cleartext fallback or Send deep link. The default icon is Classic; changing icons does not migrate wallet data.

## Privacy and disclosure

[Android disclosures](../PRIVACY.md) accurately cover this build; the linked website policy remains iOS-specific and needs an Android update. In-app Privacy opens the Android disclosure. Security reports: [info@massive-x.com](mailto:info@massive-x.com), never keys or device credentials. See [SECURITY.md](../SECURITY.md).
