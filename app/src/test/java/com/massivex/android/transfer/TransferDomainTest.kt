package com.massivex.android.transfer

import com.google.gson.JsonParser
import com.massivex.massa.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

internal inline fun rejects(reason:SendError?=null,block:()->Unit){try{block();fail("Expected conservative transfer rejection")}catch(e:SendFailure){if(reason!=null)assertEquals(reason,e.reason)}}
class TransferDomainTest {
    @Test fun commaAndLeadingDecimalMatchIosInput(){assertEquals("1.23",SendInput.amount("1,23").text);assertEquals("0.1",SendInput.amount(",1").text);assertEquals("0.1",SendInput.amount(".1").text)}
    @Test fun zeroAndNegativeRejected(){listOf("0","0.000000000","-1","+1","-0").forEach{rejects(SendError.AMOUNT){SendInput.amount(it)}}}
    @Test fun noncanonicalAmountsRejected(){listOf("01","1.","1,2,3","1e3","NaN"," 1","1 ","١","1.0000000001","18446744073.709551616").forEach{rejects(SendError.AMOUNT){SendInput.amount(it)}}}
    @Test fun nanoAndUint64Exact(){assertEquals(1uL,SendInput.amount("0.000000001").nano);assertEquals(ULong.MAX_VALUE,SendInput.amount("18446744073.709551615").nano)}
    @Test fun recipientRequiresCanonicalAu(){assertEquals(recipient,SendInput.recipient(" $recipient "));listOf("AS"+recipient.drop(2),recipient+"x","massa:$recipient","",recipient.lowercase()).forEach{rejects(SendError.RECIPIENT){SendInput.recipient(it)}}}
    @Test fun mismatchedPublicIdentityRejected(){rejects(SendError.WALLET){source().copy(address=recipient).validate()}}
    @Test fun nonUuidSourceRejected(){rejects(SendError.WALLET){source().copy(walletId="alias").validate()}}
    @Test fun sourceLockIgnoresUuidForSameKey(){assertEquals(source().identity,source().copy(walletId=java.util.UUID.randomUUID().toString()).identity)}
    @Test fun onlyReviewedProtocolVersions(){listOf("MAIN.5.2","MAIN.5.10","MAIN.5.1-extra","MAIN.6.0","").forEach{rejects(SendError.STALE){snapshot().copy(version=it).validate(TEST_TIME)}};snapshot().copy(version="MAIN.5.0").validate(TEST_TIME)}
    @Test fun wrongChainAndAgeRejected(){rejects{snapshot().copy(chain=0).validate(TEST_TIME)};rejects{snapshot().validate(TEST_TIME-1)};rejects{snapshot().validate(TEST_TIME+20001)}}
    @Test fun bundleAtBoundaryValid(){snapshot().validate(TEST_TIME+20000)}
    @Test fun invalidValidityAndPeriodOverflow(){rejects{snapshot().copy(validity=1uL).validate(TEST_TIME)};rejects{snapshot().copy(period=ULong.MAX_VALUE-8uL).validate(TEST_TIME)}}
    @Test fun maxUsesSmallerBalanceAndReservesFee(){assertEquals("8.999",SendInput.maximum(snapshot(),TEST_TIME).text)}
    @Test fun maxNoPositiveFundsRejected(){rejects(SendError.FUNDS){SendInput.maximum(snapshot().copy(fee=MASAmount(9_000_000_000uL)),TEST_TIME)}}
    @Test fun maxDoesNotAddCreationCostToFee(){val s=snapshot().copy(recipientFinal=MASAmount(0uL));assertEquals("8.999",SendInput.maximum(s,TEST_TIME).text)}
    @Test fun uncertainRecipientCreationMinimumEnforced(){val s=snapshot().copy(recipientCandidate=MASAmount(0uL));rejects(SendError.CREATION_MINIMUM){review(amount="0.000999999",snap=s)}}
    @Test fun creationCreditAtExactMinimum(){val r=review(amount="0.001",snap=snapshot().copy(recipientFinal=MASAmount(0uL)));assertEquals(0uL,r.credit.minimum.nano);assertEquals(1_000_000uL,r.credit.maximum.nano)}
    @Test fun positiveBothRecipientBalancesDoNotInventCost(){val r=review(amount="0.000000001");assertFalse(r.credit.creationPossible);assertEquals(r.amount,r.credit.minimum)}
    @Test fun amountPlusFeeOverflowRejected(){rejects(SendError.OVERFLOW){review(amount="18446744073.709551615")}}
    @Test fun insufficientConservativeBalanceRejected(){rejects(SendError.FUNDS){review(amount="9")}}
    @Test fun recipientBalanceOverflowRejected(){rejects(SendError.OVERFLOW){review(snap=snapshot().copy(recipientFinal=MASAmount(ULong.MAX_VALUE)))}}
    @Test fun expiryUsesValidityAndNinePeriodCap(){assertEquals(109uL,review().expiry);assertEquals(101uL,review(snap=snapshot().copy(validity=2uL)).expiry)}
    @Test fun exactReviewFields(){val r=review();assertEquals("1.001",r.total.text);assertEquals(source(),r.source);assertEquals(32,r.digest.size);assertEquals(MassaOperation.MAINNET_CHAIN_ID,r.snapshot.chain)}
    @Test fun reviewedBytesCannotBeMutated(){val r=review();val before=r.digest.hex();r.content.fill(0);r.canonical.fill(0);r.digest.fill(0);assertEquals(before,r.digest.hex());r.validate()}
    @Test fun newReviewCommitmentBoundButOperationIdStable(){val a=review();val b=review();assertNotEquals(a.commitment,b.commitment);assertEquals(a.operationId,b.operationId)}
    @Test fun anyFinancialContextChangeRequiresNewReview(){val r=review();listOf(snapshot().copy(fee=MASAmount(2uL)),snapshot().copy(finalBalance=MASAmount(9_000_000_000uL)),snapshot().copy(candidateBalance=MASAmount(10_000_000_000uL)),snapshot().copy(recipientFinal=MASAmount(0uL)),snapshot().copy(validity=11uL),snapshot().copy(version="MAIN.5.0")).forEach{rejects(SendError.CHANGED){r.validateFresh(it,TEST_TIME)}}}
    @Test fun validPeriodProgressDoesNotSilentlyChangeExpiry(){review().validateFresh(snapshot().copy(period=108uL),TEST_TIME);rejects{review().validateFresh(snapshot().copy(period=109uL),TEST_TIME)};rejects{review().validateFresh(snapshot().copy(period=99uL),TEST_TIME)}}
    @Test fun validSignatureAndTamper(){val s=signed();s.validate();rejects(SendError.SIGNATURE){SignedTransfer(review(amount="2"),s.signature).validate()};rejects(SendError.SIGNATURE){SignedTransfer(s.draft,s.signature.dropLast(1)+"x").validate()}}
    @Test fun unsafePublicIdentitiesBlockedByShippingPolicy(){for(address in SendIdentityPolicy.unsafe)rejects(SendError.RECIPIENT){SendIdentityPolicy.production(source(),address)}}
    @Test fun existingSdkCanonicalVectorsRemainTheOracle(){
        val root=JsonParser.parseString(File("../massa-core/src/test/resources/ios-parity.json").readText()).asJsonObject
        // Compare the actual SDK/iOS serialized fields, rather than recomputing expected bytes with this domain.
        root.getAsJsonArray("operations").take(4).forEach { item ->
            val o=item.asJsonObject
            if(o["recipient"].asString.startsWith("AU")){
                val public=o["publicKey"].asString
                val from=TransferSource(java.util.UUID.randomUUID().toString(),PublicAccount.fromRaw(MassaCodec.decode(public,"P")).address,public)
                val expiry=o["expiry"].asString.toULong()
                val snap=snapshot().copy(period=if(expiry>=9uL)expiry-9uL else 0uL,validity=if(expiry>=9uL)10uL else expiry+1uL,fee=MASAmount.parse(o["fee"].asString),finalBalance=MASAmount(ULong.MAX_VALUE),candidateBalance=MASAmount(ULong.MAX_VALUE))
                val d=ReviewedTransfer.make(from,o["recipient"].asString,o["amount"].asString,snap,TEST_TIME)
                assertEquals(o["serialized"].asString,d.content.hex());assertEquals(o["canonical"].asString,d.canonical.hex());assertEquals(o["digest"].asString,d.digest.hex());assertEquals(o["operationID"].asString,d.operationId)
                SignedTransfer(d,o["sdkSignature"].asString).validate()
            }
        }
    }
}
