# Test safety and coverage

The core fixture `massa-core/src/test/resources/ios-parity.json` contains **published, deliberately insecure TEST-ONLY private keys/passwords**, generated from zero, ascending, descending and all-255 byte patterns. They match the public iOS/core oracle and are not owner wallets or service credentials. Never fund these identities or replace them with real keys. Other generated fixtures use equally disposable public byte patterns. The production send path rejects the known compromised fixture identities.

`ios-provenance.json` records the pinned iOS/SDK revisions and source hashes for the committed interoperability fixture. The committed fixtures are sufficient for these interoperability checks; Swift/iOS source is not required. CryptoKit’s randomized valid signatures are verified, not falsely required to equal deterministic Android signature bytes.

## JVM

```sh
./gradlew --offline --dependency-verification strict test
```

These tests use in-memory/fake network adapters. Coverage includes Ed25519/BLAKE3/Base58Check and bidirectional protocol fixtures, YAML v1/wrong-password/tamper, exact nanoMAS, protected wallet identity/action approvals, corrupt/partial storage, bounded TLS/JSON, stale selection isolation, transfer source locks, durable journal crash/restart, fake send failures/cancellation, duplicate guards and separated conservative finality. No JVM test submits a real operation. Tests do not replace an independent audit.

## Native disposable emulator

The checked-in native tests target `com.massivex.android.dev`. Use only a dedicated `MassiveX_Dev` AVD at port5580; helper guards reject other AVDs and physical phones. The disposable emulator credential `246810` is a **public fixture**, never an owner PIN. Do not reuse it for a real device. Security/generated-wallet suites temporarily use airplane mode only on that guarded AVD and restore prior state.

```sh
./tools/gradle-local.sh --offline --dependency-verification strict assembleDebug assembleDebugAndroidTest
./tools/emulator-tests.sh
./tools/safe-send-emulator-tests.sh
./tools/parity-emulator-tests.sh
./tools/visual-emulator-tests.sh
./tools/ui02-emulator-tests.sh
./tools/ui03-emulator-tests.sh
./tools/read-only-emulator-tests.sh
./tools/send-read-contract-emulator-tests.sh
python3 tools/balance-scheduler-emulator-tests.py
```

Public-read suites contact fixed official read endpoints with canonical test public addresses only. Safe Send uses injected fake submissions. Job acceptance reboots only the guarded AVD. `tools/ui-smoke.py` and `tools/picker-emulator-tests.py` exercise the retained disposable catalog; never run these against a personal installation. No script is authorization to use an owner key, fund an address or submit a real transfer.

See [release verification](../PUBLICATION.md) for the tested artifact, environment and results. Emulator acceptance does not establish physical-device or universal OEM qualification.
