# Build and verify

Use the tag `v0.5.0-beta.2` for the APK’s source. Normal Debug is `com.massivex.android.dev`; Release is `com.massivex.android`, version 0.5.0 / code 2, minSdk 26, target/compileSdk 36. Signing wallets require API30+ with a secure lock. No flavors or production signing credentials are embedded.

## Toolchain and dependencies

Verified: Android Studio Narwhal 2025.1.1 / bundled JBR 21.0.6, Gradle 8.13, AGP 8.11.1, Kotlin/Compose compiler 2.1.20, Compose BOM 2025.06.01, Activity Compose 1.10.1, SDK platform36 and Build Tools35.0.0. JVM bytecode target17. A compatible configured JDK/SDK can be used on another host; that host is not implicitly qualified by these results.

The wrapper, module locks and `gradle/verification-metadata.xml` pin dependencies. Initial setup downloads official artifacts; offline mode requires a populated cache. Add `--offline` after the cache is populated. Missing artifacts on another platform need deliberate checksum/lock review, never disabling verification.

```sh
./gradlew --dependency-verification strict test :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lint :app:lintRelease
```

On the verified Mac, `./tools/gradle-local.sh` selects the existing Android Studio JDK/SDK without changing global settings. `local.properties.example` is only a template. Never commit local configuration, signing secrets or wallet files. To force all JVM suites, run `./gradlew --offline --dependency-verification strict :massa-core:test :app:testDebugUnitTest :app:testReleaseUnitTest --rerun-tasks`.

## Signing your own build

`assembleRelease` creates an **unsigned** APK; it is not the downloadable signed asset. Use your own persistent private signing identity outside the checkout. The publisher’s key/password is not provided. Losing it prevents compatible future updates; back up the encrypted keystore and its credential independently and securely. Do not uninstall a funded app to resolve an incompatible signature.

This example signs a custom build, not a publisher artifact. With Android Build Tools on PATH:

```sh
zipalign -P 16 -f 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
apksigner sign --ks /private/path/to/your-release-keystore.p12 \
  --ks-key-alias your-release-alias --out Massive-X-Android-custom.apk aligned.apk
apksigner verify --verbose --print-certs Massive-X-Android-custom.apk
zipalign -c -P 16 4 Massive-X-Android-custom.apk
shasum -a 256 Massive-X-Android-custom.apk
```

Enter your own password only in the signing tool’s local secure prompt; never paste it into a public issue, command history or checked-in properties file. Confirm the actual package/version/SDK/permissions with `aapt dump badging` and `aapt dump permissions`. Release must be non-debuggable; inspect the APK rather than inferring from its filename. Signing and alignment do not establish independent security.

## Safe installation and tests

See [TESTS.md](TESTS.md). Native tests require an explicitly disposable environment, not an installed personal wallet. Test builds use deliberately compromised known vectors and fake writes. Never fund test identities or execute a real Mainnet write as an automated smoke test.

## Verify the publisher APK

Download `Massive-X-Android-0.5.0-beta.2.apk`, `SHA256SUMS` and `APK-SIGNATURE.txt` from the [beta.2 release](https://github.com/whoisfedor/massive-x-android/releases/tag/v0.5.0-beta.2). Run:

```sh
shasum -a 256 -c SHA256SUMS
apksigner verify --verbose --print-certs Massive-X-Android-0.5.0-beta.2.apk
zipalign -c -P 16 4 Massive-X-Android-0.5.0-beta.2.apk
```

Compare the file hash and certificate against the [README](../README.md#verify-the-download). The embedded `META-INF/version-control-info.textproto` must identify source `da438cd95fbf5eb9bddd5a72200071961f490a9f`. Signing/alignment do not imply a byte-identical rebuild or independent security audit. Exact artifact identities and test scope are recorded in [PUBLICATION.md](../PUBLICATION.md).
