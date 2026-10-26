# Android privacy disclosures

Applies to Massive X Android 0.5.0 (1), public testing, October 26, 2026. Contact: **info@massive-x.com**. This describes the Android source in this repository, not the iOS implementation. The website policy at https://massive-x.com/privacy/ is currently iOS-specific and needs an Android update; its Keychain and clipboard-expiry language does not describe this build.

## Local storage and keys

The app is self-custodial. Wallet names, public identities, selection/preferences, backup acknowledgments, optional notification baselines and encrypted transfer records remain on the device. Per-wallet seeds are Keystore-encrypted and accessed locally under protected action authorization. Private keys are not intentionally sent to network providers or an app-operated cloud wallet. Biometric templates/device credentials are managed by Android, not collected by this app. Temporary managed-memory copies cannot be completely zeroized.

Android backup and device-transfer exclusions cover the app; do not rely on migration/reinstall for recovery. Keystore invalidation/device loss can require your independent private-key backup. Removal affects selected local wallet material subject to authorization/source locks; it does not erase blockchain history or provider logs. Journal evidence may outlive a selected wallet record. App deletion/OS compromise is not guaranteed secure erasure.

## Requests to external services

Massa Labs Mainnet RPC receives selected public addresses, recipient addresses during preflight, original operation IDs during reconciliation and signed public operation data only after an owner-authorized Send. Explorer API receives public addresses/history requests. These services also see IP, timing and connection metadata. This is direct HTTPS, not an anonymous proxy. Official-provider finality is operational trust, not independent chain verification.

CoinGecko and Frankfurter receive market/currency requests and network metadata, without intentional wallet-address attachment. Public exchange/explorer/support/share links open external applications under their own policies. Providers can retain logs and process data internationally; the app does not control their retention or availability. Public on-chain transfers can be permanent and cannot be erased by deleting this app.

## Optional notifications

Balance checks are off by default. If enabled, Android JobScheduler may check configured public wallet addresses while the app is closed, at 15-minute or longer intervals. Android/OEM battery, network, force-stop and permission rules can delay/suppress execution. Generic local notices avoid including balances or addresses. No instant push backend or signing runs in the balance job. Disable the setting to cancel scheduled checks; manage notification permission in Android as desired.

## Screenshots, clipboard and sharing

Ordinary public pages allow screenshots, including public balance/activity and transfer-review data. Private-key/password input and revealed keys use FLAG_SECURE. Reveals hide after 30 seconds or exit/background. External cameras, compromised OS/accessibility/keyboards and other retained copies remain risks.

Copying a private key requires a deliberate freshly authenticated action. It is marked sensitive in Android ClipData, but the app does **not** clear it on navigation, background or a 30-second timer. Android 13+ may clear clipboard content on its own. Manual Clear checks the current random ownership label and will not clear a newer unrelated item; ownership may be forgotten after process death. Another app or your chosen paste destination may retain it. A clipboard copy is not an independent offline backup. Public address/ID copying and sharing disclose data to the chosen destination.

## Tracking and contact

No dedicated analytics, advertising SDK or app-operated cloud account is included. Platform and external-provider processing still occurs under their own practices. The app cannot retrieve your protected keys or erase third-party/on-chain records. Send privacy questions to info@massive-x.com without keys, recovery files, passwords or device credentials. Future data-processing changes require a fresh review of these disclosures.
