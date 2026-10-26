# Android public testing verification

## Beta.2 source and update qualification

Android **0.5.0 (2)** is a new APK from the exact `v0.5.0-beta.2` source commit, using the same application ID and signing identity. Product/security behavior, dependencies and original images are unchanged. Its separately recorded release receipts must identify the new embedded source SHA, checksum, certificate, actual regression results, fresh install and beta.1 in-place upgrade outcome. These new receipts do not retroactively change beta.1 evidence. Never substitute a rewritten source SHA for the original binary revision. The publication gate forbids release if required upgrade acceptance fails.

Beta.2 release status, new checksum and verification results are published with its tag-specific assets. Exact-artifact funded acceptance remains unverified, and no independent security audit is claimed.

## Original beta.1 qualification (preserved)

Published pre-release: **[v0.5.0-beta.1](https://github.com/whoisfedor/massive-x-android/releases/tag/v0.5.0-beta.1)** · Android **0.5.0 (1)** · `com.massivex.android` · minSdk26 / targetSdk36.

## Exact artifact and source

- Release source/tag commit: `29a908959d3cf082fcabcb62cc29a02d0b32720e`. The APK embeds this exact revision in `META-INF/version-control-info.textproto`.
- Download: [Massive-X-Android-0.5.0-beta.1.apk](https://github.com/whoisfedor/massive-x-android/releases/download/v0.5.0-beta.1/Massive-X-Android-0.5.0-beta.1.apk) — **17,692,178 bytes**.
- APK SHA-256: `97b43f63ca55c6ccdb9ffa28cfc371fb83aaa027e8257cd12719fe095e154d2c`.
- Signing certificate SHA-256: `CE5A117B362095A6F610266D1102525A51C8440B48A0B68412849EFE2D76030F`.
- Certificate: CN=Massive X Android Release, O=Massive X; RSA4096, persistent dedicated publisher identity. Private material/password remain outside this repository; no signing configuration is distributed.
- [SHA256SUMS](https://github.com/whoisfedor/massive-x-android/releases/download/v0.5.0-beta.1/SHA256SUMS) and [signature receipt](https://github.com/whoisfedor/massive-x-android/releases/download/v0.5.0-beta.1/APK-SIGNATURE.txt) accompany the asset.

The original tag/source identity is immutable for this binary. Its original publication handoff and public Android capture were documentation-only; beta.2 later increments only versioning and updates publication documentation. Rebuilding/signing with your own key is not a byte-identical publisher artifact or compatible signing identity.

## Owner-performed development history versus release qualification

The owner reports personally completing successful real Massa Mainnet transfers during Android development. These were owner-performed development transfers, separate from the automated publication/qualification work recorded below. They are documented as owner-reported history, not an independently verified test result.

No independently verified transaction receipts or evidence connecting those transfers to the exact signed APK identified above are supplied here. Its **funded end-to-end acceptance remains unverified**. The successful development history does not expand the scope of the original 754 JVM executions, 43 native executions, 14 CLI scenarios or 10 signed-APK acceptance checks, none of which submitted a real Mainnet operation.

## GO / verification gates

| Gate | Observed result |
|---|---|
| Sanitized public source / history | PASS: fresh public history; private development objects/reports, owner-assisted device harnesses, iOS source/tooling, signing secrets, machine/device identifiers and private design references excluded |
| Secret scan | PASS after explicit review: Gitleaks 8.30.1 plus targeted searches; three scanner findings are published, compromised test-vector keys in the core fixture, not owner/service secrets; zero unknown credential findings |
| Source licensing / attribution | PASS: original MIT scope validated, artwork/trademarks separate, runtime notices bundled under assets/licenses (36 files), upstream licenses preserved |
| Complete JVM regression | PASS: **80 core + 337 app Debug + 337 app Release = 754 executions / 417 distinct cases**; 0 failed, 0 errors, 0 skipped |
| Builds / lint | PASS: Debug, unsigned Release and instrumentation APKs; strict offline dependency verification; lint **0 errors / 85 advisory warnings** |
| Native source regressions | PASS: **43 executions / 39 distinct methods**, plus **14 CLI UI scenarios**, isolated API34 ARM64 emulator; fake/offline writes only |
| Exact signed Release acceptance | PASS: **10/10 checks** in a separate blank API34 ARM64 emulator — install, launch, fresh protected create/import, actual confirmed/candidate Mainnet read, navigation, public capture, Classic default and same-certificate catalog-preserving update |
| Signature / package / alignment | PASS: APK v2/v3, non-debuggable production package; ZIP and every packaged native ELF LOAD alignment verified at 16 KB; expected five app permissions plus AndroidX internal signature permission |
| Public repository / image | PASS: anonymous repository access; GitHub-rendered README image; anonymously fetched promotional PNG matches approved local bytes |
| Public pre-release / download | PASS: not draft, prerelease=true; anonymous direct APK/checksum download matches local signed file and uploaded draft; real nonzero asset size/digest verified |
| Safety | PASS: publication/qualification automation used no owner keys/funds and submitted no real Mainnet operation; no iOS modifications; protected production protocol/storage/journal/transport algorithms unchanged |

Native coverage includes actual Keystore/auth and YAML import, UI/capture/clipboard/locales/fonts, fake transfer/source-lock/finality and journal process-restart cases, public read contracts, all icon restart phases and balance-job process death/reboot/opt-out. JNI/runtime ABI alignment is verified; 16 KB-page runtime hardware is not separately qualified. A real provider read initially returned unavailable; explicit refresh later rendered the actual zero confirmed/candidate values. No balance or history was fabricated.

Initial Release smoke harness checks were corrected for Android’s secure-lock command wording, the OS BiometricPrompt window identity and transient public accessibility-tree availability. The existing test installation and its disposable catalog were preserved; no app data was wiped to obtain a pass. These harness attempts are not counted as passing checks.

## Images, privacy and limitations

The current README uses `screenshots/scr.png`, an owner-supplied promotional composition of Android interfaces, published unchanged at the owner’s explicit request. Selected account details are blurred; some transaction amounts and timestamps remain visible. Device frames and background are illustrative. The obsolete standalone API34 demonstration image has been removed from the public documentation; the prior release acceptance results remain unchanged.

Mainnet Send is enabled for a real owner-reviewed/freshly authorized transfer. The publication qualification tests never confirmed/submitted one; the owner-reported development transfers are a separate history. Official-provider finality is trust, not independent consensus verification. Complete rollback, compromised devices, simultaneous use of a key on multiple devices, missing history and Keystore invalidation remain limits. Clipboard and public capture tradeoffs are disclosed in [Android privacy](PRIVACY.md).

No independent audit, funded end-to-end acceptance of the exact signed release APK, current signed-release Xiaomi/Android16 test, other-OEM/API26–29 runtime qualification, Google Play approval or Android developer-verification registration is claimed. Region/device sideload restrictions may apply. The website privacy policy remains iOS-specific; the app instead opens the accurate Android repository disclosure. A website policy update remains an operator follow-up, not an unrecorded change in this release.
