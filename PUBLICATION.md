# Android release verification

Current testing release: **[v0.5.0-beta.2](https://github.com/whoisfedor/massive-x-android/releases/tag/v0.5.0-beta.2)** · **0.5.0 (2)** · `com.massivex.android` · minSdk26 / targetSdk36.

## Beta.2 artifact

- Source/tag commit: `da438cd95fbf5eb9bddd5a72200071961f490a9f`.
- Source tree: `c68fa5ada0322a52b925efae0c1d3e4e4121cfad`.
- [Massive-X-Android-0.5.0-beta.2.apk](https://github.com/whoisfedor/massive-x-android/releases/download/v0.5.0-beta.2/Massive-X-Android-0.5.0-beta.2.apk): **17,692,178 bytes**.
- APK SHA-256: `d427129de3cdd88277dd130253c49cae8cf2c2e483214a638b5b950be7886d93`.
- Signing-certificate SHA-256: `CE5A117B362095A6F610266D1102525A51C8440B48A0B68412849EFE2D76030F`.
- [SHA256SUMS](https://github.com/whoisfedor/massive-x-android/releases/download/v0.5.0-beta.2/SHA256SUMS) · [APK-SIGNATURE.txt](https://github.com/whoisfedor/massive-x-android/releases/download/v0.5.0-beta.2/APK-SIGNATURE.txt).

The APK embeds its source revision in `META-INF/version-control-info.textproto`. The persistent publisher certificate is CN=Massive X Android Release, O=Massive X, RSA4096. A matching certificate identifies signing continuity; signing/alignment do not imply an independent security audit or a byte-identical rebuild. See [verification commands](docs/BUILD_AND_VERIFY.md#verify-the-publisher-apk).

## Beta.2 qualification — October 26, 2026

| Check | Result |
| --- | --- |
| Forced JVM regressions | **80 core + 337 Debug + 337 Release = 754 executions / 417 distinct cases**; zero failures, errors or skips |
| Builds and dependencies | Debug, unsigned Release and instrumentation builds pass; all **131 tasks** in the forced build/regression command executed; strict offline dependency verification |
| Lint | **0 errors**; **85 Debug / 86 Release advisory warnings** |
| Native source regressions | **43 executions** and **14 CLI UI scenarios** pass on the dedicated API34 ARM64 development emulator; fake/offline writes and public read-only requests |
| Exact signed-APK acceptance | **3 methods**: exact downloaded beta.1 preparation, beta.2 verification after in-place update, independent fresh beta.2 installation; plus **14 public Release UI checks** (7 upgraded / 7 fresh) |
| Package, signature and alignment | Production package, non-debuggable, versionCode2, v2/v3 signature; 16 KB ZIP alignment and ELF LOAD alignment/congruence for all **4 native libraries** |
| Source and dependency review | Gitleaks 8.30.1: three known compromised fixture-key locations (core fixture lines16/25/34), zero additional credential findings; upstream licenses and **36 bundled notice files** retained |
| Public downloads | Anonymous APK/checksum/signature downloads match qualified local bytes and GitHub asset digests; downloaded signature/source revision verified; fresh clone matches source tag |
| README image | Rendered preview and anonymous `screenshots/scr.png` download match the approved file; SHA-256 `f563efb62a417ee83323a58052801cac65db2408abbe3c26dc8a0f8a848a0af0` |

Native coverage includes real Keystore authorization, YAML import, capture/clipboard transitions, locales/fonts/themes/icons, fake Send/source locks/finality, journal process restarts, public read contracts and scheduler process-death/reboot/opt-out behavior. See [test safety and commands](docs/TESTS.md).

### In-place update and fresh install

An isolated API34 ARM64 AVD installed the exact signed beta.1 APK. A separate same-certificate instrumentation harness created a synthetic owned wallet and watch-only entry, saved the owned selection and settings, and used a fake transport to leave one encrypted UNKNOWN attempt with an active source lock. The AVD was offline for these signed-release tests; the helper is not part of the distributed APK.

`adb install -r` updated it to the exact beta.2 APK without uninstalling, clearing data or resetting Keystore. Checks verified the same UID, wallet identities/addresses and selection, byte-identical encrypted catalog and journal, operation ID/binding, UNKNOWN state, duplicate/removal locks and retained EUR/fiat, LIGHT appearance, ORANGE accent and hidden-balance settings. The auth-required five-second key policy and expiry denial remained effective, and backup/decryption passed after fresh system authorization. A separate empty AVD passed beta.2 install, protected create/backup and Classic-default checks.

Public Release UI checks covered Wallet, Activity, Settings, Receive, guarded Send entry without confirmation, process restart and ordinary public capture on each AVD. No real Mainnet operation was submitted. Initial host-resource/startup timeouts and a locked-screen UI harness attempt were resolved before complete affected checks passed; failed attempts are not counted, and app data was retained.

## Historical beta.1 qualification

[v0.5.0-beta.1](https://github.com/whoisfedor/massive-x-android/releases/tag/v0.5.0-beta.1) · **0.5.0 (1)** · `com.massivex.android` · minSdk26 / targetSdk36.

- Source/tag and embedded revision: `29a908959d3cf082fcabcb62cc29a02d0b32720e`.
- [Massive-X-Android-0.5.0-beta.1.apk](https://github.com/whoisfedor/massive-x-android/releases/download/v0.5.0-beta.1/Massive-X-Android-0.5.0-beta.1.apk): **17,692,178 bytes**.
- APK SHA-256: `97b43f63ca55c6ccdb9ffa28cfc371fb83aaa027e8257cd12719fe095e154d2c`.
- Signing-certificate SHA-256: `CE5A117B362095A6F610266D1102525A51C8440B48A0B68412849EFE2D76030F`.
- [SHA256SUMS](https://github.com/whoisfedor/massive-x-android/releases/download/v0.5.0-beta.1/SHA256SUMS) · [APK-SIGNATURE.txt](https://github.com/whoisfedor/massive-x-android/releases/download/v0.5.0-beta.1/APK-SIGNATURE.txt).

These results apply to the beta.1 artifact above, not to beta.2:

| Check | Recorded beta.1 result |
| --- | --- |
| JVM regressions | **80 core + 337 Debug + 337 Release = 754 executions / 417 distinct cases**; zero failures, errors or skips |
| Builds / lint | Debug, unsigned Release and instrumentation builds pass; strict offline dependency verification; **0 lint errors / 85 advisory warnings** |
| Native source regressions | **43 executions / 39 distinct methods**, plus **14 CLI UI scenarios**, isolated API34 ARM64 development emulator; fake/offline writes |
| Exact signed Release acceptance | **10/10 checks** in a separate blank API34 ARM64 emulator: install/launch, fresh protected create/import, actual confirmed/candidate Mainnet read, navigation, public capture, Classic default and same-certificate catalog-preserving update |
| Signature / package / alignment | v2/v3, non-debuggable production package; 16 KB ZIP and every packaged native ELF LOAD alignment; five application permissions plus AndroidX internal signature permission |
| Source / license / secret review | Original MIT/artwork scope validated; **36 bundled notices**; three reviewed compromised fixture-key findings, zero unknown credentials |
| Public access | Anonymous repository/image access and APK/checksum downloads matched local and uploaded asset bytes/digests; public prerelease, not draft |
| Safety | Qualification used no owner wallet keys/funds and submitted no real Mainnet operation; no iOS modifications; protocol/storage/journal/transport algorithms unchanged |

A beta.1 provider read initially returned unavailable; explicit refresh later returned actual zero confirmed/candidate values. Initial smoke-harness checks were corrected for lock-command wording, BiometricPrompt window identity and transient accessibility-tree availability. The installation/catalog was retained; failed attempts are not passing checks.

The owner reports successful real Mainnet transfers during development. These are separate from release qualification; no independently verified receipts tie those development transfers to either exact release APK.

## Scope and limitations

Neither release has an independent security audit or independently verified funded end-to-end acceptance. The signed-release checks above used API34 ARM64 emulators; they do not establish signed-release Xiaomi/Android16 acceptance, other-OEM/API26–29 runtime qualification, 16 KB-page hardware runtime qualification, Google Play approval or developer-verification registration.

Official-provider finality is operational trust, not independent consensus verification. Rollback, compromised devices, simultaneous key use across devices, missing history and Keystore invalidation remain risks. See the [security model](docs/RELEASE_SECURITY.md) and [Android privacy disclosures](PRIVACY.md) for backup, capture, clipboard and provider limits.

The preview is an owner-supplied Android composition. Selected details are blurred; some amounts/timestamps remain visible, and frames/background are illustrative. Artwork/content-authenticity metadata and third-party notices are retained. The app links to the Android privacy disclosure; the website policy remains iOS-specific.
