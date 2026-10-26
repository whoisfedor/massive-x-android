package com.massivex.massa

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.AfterClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.security.MessageDigest

internal object Fixtures {
    private val bytes = checkNotNull(javaClass.getResourceAsStream("/ios-parity.json")).use { it.readBytes() }
    val root: JsonObject = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
    fun rows(name: String): List<Array<Any>> = root.getAsJsonArray(name).map { arrayOf<Any>(it.asJsonObject) }
    fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun validateProvenance() {
        val provenance = JsonParser.parseReader(checkNotNull(javaClass.getResourceAsStream("/ios-provenance.json")).reader()).asJsonObject
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(provenance["fixtureSha256"].asString, hash)
        assertEquals("2ff9411c82e23255509304de1c7aa9cdff61108d", root["iosCommit"].asString)
    }
}

@RunWith(Parameterized::class)
class AccountParityTest(private val fixture: JsonObject) {
    companion object { @JvmStatic @Parameterized.Parameters(name = "public-pattern-{index}") fun data() = Fixtures.rows("accounts") }
    @Test fun publicKeyAndVersionedAddressMatchExecutedSwift() {
        OfflineAccount.fromSeed(Fixtures.hex(fixture["seedHex"].asString)).use { account ->
            val identity = account.publicAccount()
            assertEquals(fixture["publicKey"].asString, identity.publicKey)
            assertEquals(fixture["address"].asString, identity.address)
            assertArrayEquals(Fixtures.hex(fixture["publicKeyHex"].asString), MassaCodec.decode(identity.publicKey, "P"))
            assertArrayEquals(Fixtures.hex(fixture["addressHash"].asString), MassaCodec.decode(identity.address, "AU"))
        }
    }
    @Test fun syntheticPrivateKeyEncodingImportAndRoundTrip() {
        OfflineAccount.fromSeed(Fixtures.hex(fixture["seedHex"].asString)).use { account ->
            // Boolean assertion intentionally avoids printing even published secret-shaped values.
            assertTrue("Public fixture encoding must match Swift", account.privateKeyEncoding() == fixture["privateKey"].asString)
        }
        OfflineAccount.import(fixture["privateKey"].asString).use { account -> assertEquals(fixture["address"].asString, account.publicAccount().address) }
    }
    @Test fun closeRevokesKeyMaterialAndDescriptionsRemainRedacted() {
        val key = OfflineAccount.fromSeed(Fixtures.hex(fixture["seedHex"].asString))
        assertEquals("OfflineAccount(<redacted>)", key.toString())
        key.close(); key.close()
        assertThrows(MassaException::class.java) { key.publicAccount() }
        assertThrows(MassaException::class.java) { key.privateKeyEncoding() }
    }
}

@RunWith(Parameterized::class)
class OperationParityTest(private val fixture: JsonObject) {
    companion object {
        private val generated = mutableListOf<JsonObject>()
        @JvmStatic @Parameterized.Parameters(name = "operation-{index}") fun data() = Fixtures.rows("operations")
        @JvmStatic @AfterClass fun savePublicSignaturesForActualSwiftVerification() {
            val file = java.io.File("build/public-android-signatures.json")
            file.parentFile.mkdirs()
            // Only public synthetic vectors; never an application key or account store.
            file.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(generated))
        }
    }
    private fun content() = MassaOperation.serialized(MASAmount.parse(fixture["fee"].asString), MASAmount.parse(fixture["amount"].asString), fixture["expiry"].asString.toULong(), fixture["recipient"].asString)
    @Test fun exactSerializedAndCanonicalBytesMatchSwift() {
        assertArrayEquals(Fixtures.hex(fixture["serialized"].asString), content())
        assertArrayEquals(Fixtures.hex(fixture["canonical"].asString), MassaOperation.canonical(content(), fixture["publicKey"].asString))
        assertArrayEquals(Fixtures.hex(fixture["digest"].asString), MassaCodec.hash(MassaOperation.canonical(content(), fixture["publicKey"].asString)))
    }
    @Test fun pureEd25519SignaturesInteroperateWithSwiftAndMatchSDK() {
        OfflineAccount.fromSeed(ByteArray(32)).use { key ->
            val signed = key.signOperation(content(), key.publicAccount())
            if (fixture.has("sdkSignature")) assertEquals(fixture["sdkSignature"].asString, signed)
            val native = fixture["swiftSignature"].asString
            assertArrayEquals(Fixtures.hex(fixture["swiftSignatureHex"].asString), MassaCodec.decode(native, "", 64))
            assertTrue(key.publicAccount().verify(MassaOperation.canonical(content(), key.publicAccount().publicKey), native))
            val result = JsonObject().apply {
                addProperty("publicKey", key.publicAccount().publicKey)
                addProperty("content", fixture["serialized"].asString)
                addProperty("operationID", MassaOperation.operationID(content(), key.publicAccount().publicKey))
                addProperty("signature", signed)
            }
            generated.add(result)
            assertEquals(fixture["operationID"].asString, MassaOperation.operationID(content(), key.publicAccount().publicKey))
            assertTrue(key.publicAccount().verify(MassaOperation.canonical(content(), key.publicAccount().publicKey), signed))
        }
    }
    @Test fun alteredBytesSignatureAndChainCannotVerify() {
        OfflineAccount.fromSeed(ByteArray(32)).use { key ->
            val canonical = MassaOperation.canonical(content(), key.publicAccount().publicKey)
            assertFalse(key.publicAccount().verify(canonical + byteArrayOf(1), fixture["swiftSignature"].asString))
            val otherChain = canonical.copyOf(); otherChain[7] = (otherChain[7].toInt() xor 1).toByte()
            assertFalse(key.publicAccount().verify(otherChain, fixture["swiftSignature"].asString))
            val changedSignature = MassaCodec.decode(fixture["swiftSignature"].asString, "", 64); changedSignature[0] = (changedSignature[0].toInt() xor 1).toByte()
            assertFalse(key.publicAccount().verify(canonical, MassaCodec.encode(changedSignature, "")))
        }
    }
    @Test fun wrongExpectedSignerIsRejected() {
        OfflineAccount.fromSeed(ByteArray(32)).use { key ->
            OfflineAccount.fromSeed(ByteArray(32) { 42 }).use { other ->
                assertThrows(MassaException::class.java) { key.signOperation(content(), other.publicAccount()) }
            }
        }
    }
}

@RunWith(Parameterized::class)
class Blake3ParityTest(private val fixture: JsonObject) {
    companion object { @JvmStatic @Parameterized.Parameters(name = "blake3-{index}") fun data() = Fixtures.rows("hashes") }
    @Test fun swiftDigestAlsoMatchesPinnedUpstreamKnownAnswer() {
        val bytes = ByteArray(fixture["length"].asInt) { (it % 251).toByte() }
        assertArrayEquals(Fixtures.hex(fixture["digest"].asString), MassaCodec.hash(bytes))
    }
}

@RunWith(Parameterized::class)
class AmountParityTest(private val fixture: JsonObject) {
    companion object { @JvmStatic @Parameterized.Parameters(name = "amount-{index}") fun data() = Fixtures.rows("amounts") }
    @Test fun exactAmountAcceptanceAndFormattingMatchSwift() {
        val input = fixture["input"].asString
        if (!fixture["accepted"].asBoolean) assertThrows(MassaException::class.java) { MASAmount.parse(input) }
        else {
            val amount = MASAmount.parse(input)
            assertEquals(fixture["nano"].asString, amount.nano.toString())
            assertEquals(fixture["text"].asString, amount.text)
        }
    }
}

@RunWith(Parameterized::class)
class YAMLParityTest(private val fixture: JsonObject) {
    companion object { @JvmStatic @Parameterized.Parameters(name = "yaml-{index}") fun data() = Fixtures.rows("yamlCases") }
    @Test fun acceptedAndRejectedEncryptedImportsMatchExecutedSwift() {
        val bytes = fixture["yaml"].asString.toByteArray(Charsets.UTF_8)
        if (!fixture["accepted"].asBoolean) assertThrows(MassaException::class.java) { MassaYAML.decrypt(MassaYAML.parse(bytes), fixture["password"].asString).close() }
        else MassaYAML.decrypt(MassaYAML.parse(bytes), fixture["password"].asString).use { account ->
            assertEquals(fixture["publicKey"].asString, account.publicAccount().publicKey)
            assertEquals(fixture["address"].asString, account.publicAccount().address)
        }
    }
}

class CoreAdversarialTest {
    @Test fun fixtureProvenanceHashAndMainnetIdentity() { Fixtures.validateProvenance(); assertEquals("77658377", Fixtures.root["chainID"].asString); assertEquals(77_658_377L, MassaOperation.MAINNET_CHAIN_ID) }
    @Test fun malformedEncodingChecksumPrefixesAndLengthsAreRejected() {
        val address = Fixtures.root.getAsJsonArray("accounts")[0].asJsonObject["address"].asString
        for (bad in listOf("", "AU0", "AUO", address + "1", address.dropLast(1) + if (address.last() == '1') "2" else "1", " " + address, address + "\u0000", address + "\n", "AÙ" + address.drop(2), "P" + address.drop(2), "AU" + "1".repeat(101))) {
            assertThrows(MassaException::class.java) { MassaCodec.validateAddress(bad) }
        }
        assertThrows(MassaException::class.java) { MassaCodec.encode(ByteArray(31), "P") }
        assertThrows(MassaException::class.java) { MassaCodec.decode(address, "AU", 31) }
        assertThrows(MassaException::class.java) { OfflineAccount.fromSeed(ByteArray(64)) }
    }
    @Test fun validChecksumButWrongVersionCannotImport() {
        val body = org.bitcoinj.base.Base58.encodeChecked(1, ByteArray(32))
        assertThrows(MassaException::class.java) { OfflineAccount.import("S$body") }
        assertThrows(MassaException::class.java) { MassaCodec.validateAddress("AU$body") }
    }
    @Test fun sumOverflowZeroAndInsufficientBalanceFailWithoutFloatingPoint() {
        val address = Fixtures.root.getAsJsonArray("accounts")[0].asJsonObject["address"].asString
        assertEquals(MASAmount.parse("1.01"), MASAmount.validateTransfer(address, "1", "0.01", "1.01"))
        for ((amount, fee, available) in listOf(Triple("0", "0", "1"), Triple("1", "0.01", "1"), Triple("18446744073.709551615", "0.000000001", "18446744073.709551615"))) {
            assertThrows(MassaException::class.java) { MASAmount.validateTransfer(address, amount, fee, available) }
        }
    }
    @Test fun canonicalUnsignedVarintBoundaries() {
        assertArrayEquals(byteArrayOf(0), MassaOperation.varint(0uL))
        assertArrayEquals(byteArrayOf(127), MassaOperation.varint(127uL))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 1), MassaOperation.varint(128uL))
        assertArrayEquals(ByteArray(10) { if (it == 9) 1 else 0xff.toByte() }, MassaOperation.varint(ULong.MAX_VALUE))
    }
    @Test fun malformedUTF8AndNullYAMLNeverReachDecryption() {
        assertThrows(MassaException::class.java) { MassaYAML.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(MassaException::class.java) { MassaYAML.parse("Version: 1\u0000".toByteArray()) }
    }
}
