# Dependency purpose, provenance and notices

| Dependency | Version / role | License / provenance |
| --- | --- | --- |
| Bouncy Castle bcprov-jdk18on | 1.86; lightweight Ed25519, BLAKE3 and PBKDF2-HMAC-SHA256 primitives | Bouncy Castle MIT license preserved in `third-party/BOUNCY_CASTLE_LICENSE.html`; https://www.bouncycastle.org/download/bouncy-castle-java/ ; Maven Central `org.bouncycastle:bcprov-jdk18on:1.86` |
| bitcoinj-core | 0.17.1; existing Base58.encodeChecked/decodeChecked, not Bitcoin wallet/network/transaction APIs | Apache2 license in `third-party/BITCOINJ_COPYING`; https://github.com/bitcoinj/bitcoinj/releases/tag/v0.17.1 |
| Gson | 2.13.2; strict JSON-quoted YAML scalars and public test-fixture loading | Apache2 in `third-party/GSON_LICENSE`; https://github.com/google/gson/tree/gson-parent-2.13.2 |
| Kotlin, Jetpack Compose, AndroidX Activity | Pinned build/UI support; native Compose, no WebView | Apache2 and upstream embedded notices; Google Maven / Maven Central |
| Gradle wrapper | 8.13; reproducible build bootstrap | Apache2 notice preserved in `third-party/GRADLE_LICENSE`; wrapper JAR checksum checked against official Gradle checksum endpoint |
| JUnit | 4.13.2; JVM tests only | EPL1; Maven Central; not packaged as app runtime |
| BLAKE3 known-answer data | Selected public upstream test vectors, pinned commit recorded with file SHA256 | CC0 alternative preserved in `third-party/BLAKE3_TEST_VECTORS_CC0`; no native BLAKE3 C copied into the Android app |

Use Bouncy Castle primitives directly; do not register a global provider or replace Android's provider order. bitcoinj's transitive older bcprov-jdk15to18 is excluded to avoid duplicate cryptographic implementations; bcprov-jdk18on1.86 is the single pinned implementation. bitcoinj's Base58Check uses JCA SHA-256 and existing checksum logic. Its broader wallet/network/BIP APIs are not used and do not imply Android support for Bitcoin, mnemonics or network transfers. The extra transitive footprint is a known tradeoff for not writing a new cryptographic checksum codec; evaluate narrowing it with an independently reviewed alternative in a future dependency review.

JCA `AES/GCM/NoPadding` is used for YAML decryption on Android/JVM; PBKDF2 receives explicit UTF-8 bytes through Bouncy Castle for matching Swift password semantics. Library source JARs/API/licensing were inspected; selected classes include Ed25519Signer, Ed25519PrivateKeyParameters, Blake3Digest, PKCS5S2ParametersGenerator, bitcoinj Base58/Sha256Hash and Gson JsonReader. No self-written cipher, hash, signature or checksum is introduced.

All resolved configurations are locked in module Gradle lockfiles (build plugins are separately pinned and checksum-verified). `gradle/verification-metadata.xml` records SHA256 of official HTTPS artifacts used by this Mac, including build tools and plugins. Bootstrap checksums are trust-on-first-use evidence, not independent publisher-signature validation or a vulnerability audit. Required notices are preserved; do not remove dependency notices to minimize packaging.

The runtime includes other transitive support libraries (Guava Android, protobuf-javalite, SLF4J API and annotations) through bitcoinj and normal AndroidX/Kotlin dependencies. Exact versions and hashes are recorded in lockfiles/verification metadata rather than an unpinned marketing list. No SDK download or Node/Swift runtime is embedded in the application.

The original iOS public source/fixture copyright and MIT grant are retained under `LICENSE`. Public fixture keys/passwords are explicitly synthetic, known-insecure and never fundable test credentials; they are not production secrets or service credentials. No owner app data or signing identity was copied. Artwork added in MXA-005 is separately described below.

## MXA-003 additions

- **ZXing core 3.5.3**, `com.google.zxing:core:3.5.3`, Apache License 2.0. Runtime use is limited to QR encoding of validated canonical public AU addresses; independent QR decoder tests verify exact text. No camera, barcode scanning service, network or image-loading integration. Original release license and notice are preserved in `third-party/ZXING_LICENSE` and `third-party/ZXING_NOTICE`. Provenance: https://github.com/zxing/zxing/tree/zxing-3.5.3 and official Maven Central HTTPS artifacts. Jar SHA256: `8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82`. Jar/POM/parent SHA256 and published Maven SHA1 sidecars were compared before recording the three new artifact hashes. This is HTTPS trust-on-first-use verification, not an independent vulnerability certification. This pinned encode-only use accepts no untrusted images; no gratuitous upgrade of the established toolchain was made.
- Existing **Gson 2.13.2** is now a direct app implementation dependency for bounded strict public-response parsing. It was already present in the verified runtime graph. Crypto/core artifacts and versions are unchanged. The network adapter uses native platform HTTPS/coroutines; no extra networking, tracking or HTTP-logging library is included.

## Presentation assets

No additional runtime dependency is used for presentation. Small stroke icons are native project code. Four app icons and the promotional preview are separately authorized owner-supplied artwork, excluded from MIT; see [asset terms](ASSETS_AND_TRADEMARKS.md) and [hashes](docs/ASSET_PROVENANCE.json). The Massa currency symbol is descriptive third-party branding and conveys no endorsement or trademark grant.

## Release binary notices

The APK includes license/notice copies under `assets/licenses/`; the public source also preserves them. `third-party/RUNTIME_COMPONENTS.json` records the resolved runtime artifact names/versions and available embedded notices, without local cache paths. Additional transitive obligations include protobuf-javalite 4.33.4 (BSD three-clause, full upstream license in `third-party/PROTOBUF_LICENSE`), Checker Framework checker-qual 3.43.0 (MIT, embedded notice retained), SLF4J API 2.0.16 (MIT, embedded notice retained), and JCIP annotations 1.0 (Creative Commons Attribution 2.5, attribution/official license link in `third-party/JCIP_NOTICE`). AndroidX, Kotlin, Guava, jspecify, error-prone/FindBugs/J2ObjC annotations use their upstream Apache2/declared licenses; original bundled notices are retained when supplied. A full Apache2 license is included in BITCOINJ_COPYING and other bundled copies. This inventory is a licensing/provenance review, not a vulnerability certification.
