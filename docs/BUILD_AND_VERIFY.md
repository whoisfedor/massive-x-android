# Build and verify

Use the tag `v0.5.0-beta.1` for the APK’s source. Normal Debug is `com.massivex.android.dev`; Release is `com.massivex.android`, version 0.5.0 / code 1, minSdk 26, target/compileSdk 36. Signing wallets require API30+ with a secure lock. No flavors or production signing credentials are embedded.

## Toolchain and dependencies

Verified: Android Studio Narwhal 2025.1.1 / bundled JBR 21.0.6, Gradle 8.13, AGP 8.11.1, Kotlin/Compose compiler 2.1.20, Compose BOM 2025.06.01, Activity Compose 1.10.1, SDK platform36 and Build Tools35.0.0. JVM bytecode target17. A compatible configured JDK/SDK can be used on another host; that host is not implicitly qualified by these results.

The wrapper, module locks and `gradle/verification-metadata.xml` pin dependencies. Initial setup downloads official artifacts; offline mode requires a populated cache. Missing artifacts on another platform need deliberate checksum/lock review, never disabling verification.

```sh
./gradlew --offline --dependency-verification strict test assembleDebug assembleRelease assembleDebugAndroidTest lint
```

On the verified Mac, `./tools/gradle-local.sh` selects the existing Android Studio JDK/SDK without changing global settings. `local.properties.example` is only a template. Never commit local configuration, signing secrets or wallet files. The complete JVM suite can be forced to execute again with `:massa-core:cleanTest :app:cleanTestDebugUnitTest :app:cleanTestReleaseUnitTest` before `test`.

## Release signing

`assembleRelease` creates an **unsigned** APK; it is not the downloadable signed asset. Use your own persistent private signing identity outside the checkout. The publisher’s key/password is not provided. Losing it prevents compatible future updates; back up the encrypted keystore and its credential independently and securely. Do not uninstall a funded app to resolve an incompatible signature.

With Android Build Tools on PATH:

```sh
zipalign -P 16 -f 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
apksigner sign --ks /private/path/to/your-release-keystore.p12 \
  --ks-key-alias your-release-alias --out Massive-X-Android-0.5.0-beta.1.apk aligned.apk
apksigner verify --verbose --print-certs Massive-X-Android-0.5.0-beta.1.apk
zipalign -c -P 16 4 Massive-X-Android-0.5.0-beta.1.apk
shasum -a 256 Massive-X-Android-0.5.0-beta.1.apk
```

Enter your own password only in the signing tool’s local secure prompt; never paste it into a public issue, command history or checked-in properties file. Confirm the actual package/version/SDK/permissions with `aapt dump badging` and `aapt dump permissions`. Release must be non-debuggable; inspect the APK rather than inferring from its filename. Signing and alignment do not establish independent security.

## Safe installation and tests

See [TESTS.md](TESTS.md). Native tests require an explicitly disposable environment, not an installed personal wallet. Test builds use deliberately compromised known vectors and fake writes. Never fund test identities or execute a real Mainnet write as an automated smoke test.

The published asset is built from the exact clean public tag, then aligned and signed outside the checkout. Checksum/certificate are in the README and release’s `SHA256SUMS`. A later documentation-only checksum handoff on main does not rewrite the release tag or change its source provenance. Private development Git objects, local acceptance logs and publisher signing configuration are not part of this public history.
