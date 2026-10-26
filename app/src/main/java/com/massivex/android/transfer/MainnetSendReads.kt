package com.massivex.android.transfer

import com.google.gson.*
import com.massivex.android.network.*
import com.massivex.massa.*
import java.util.UUID

/** Transfer preflight/finality adapter: fixed official HTTPS READS, no submission method or arbitrary endpoint. */
internal class MainnetSendReads(private val transport: ReadTransport = PlatformReadTransport(), private val clock: () -> Long = System::currentTimeMillis) : PreflightReader, FinalityReader {
    private suspend fun call(build: (String) -> ReadRequest): JsonElement {
        val id=UUID.randomUUID().toString()
        return MainnetReads.envelope(readPublicJson(transport,build(id)),id)
    }
    private suspend fun status(): Status {
        val started=clock()
        return decodeStatus(call { ReadRequest.rpc(RpcRead.STATUS,it) },started,clock())
    }
    override suspend fun snapshot(source: TransferSource, recipient: String): SendSnapshot {
        source.validate();validAU(recipient);SendIdentityPolicy.production(source,recipient)
        val started=clock();val status=status() // Chain/version before sending either public AU address.
        val rows=call { ReadRequest.preflightAddresses(it,source.address,recipient) }.array()
        checkRead(rows.size()==2)
        fun balance(index:Int, address:String):Pair<MASAmount,MASAmount> {
            val row=rows[index].obj();checkRead(row.text("address")==address)
            return MASAmount.parse(row.text("final_balance")) to MASAmount.parse(row.text("candidate_balance"))
        }
        val a=balance(0,source.address);val b=balance(1,recipient)
        return SendSnapshot(started,status.context.chain,status.context.version,status.period,status.validity,status.fee,a.first,a.second,b.first,b.second).also { it.validate(clock()) }
    }
    override suspend fun checkContext(): VerifiedNodeContext = status().context
    override suspend fun observe(signed: SignedTransfer): OperationObservation {
        signed.validate();val context=status().context
        val result=call { ReadRequest.operation(it,signed.draft.operationId) }
        return decodeObservation(result,signed,context,clock())
    }
    internal data class Status(val context:VerifiedNodeContext,val period:ULong,val validity:ULong,val fee:MASAmount)
    companion object {
        private fun JsonElement.unsigned():ULong {
            checkRead(isJsonPrimitive && asJsonPrimitive.isNumber && asString.matches(Regex("0|[1-9][0-9]{0,19}")))
            return asString.toULongOrNull() ?: invalid()
        }
        internal fun decodeStatus(json:JsonElement, started:Long, now:Long):Status {
            val o=json.obj();val chain=o.required("chain_id").integer();val version=o.text("version")
            demand(chain==CHAIN && version in SUPPORTED_VERSIONS && age(now,started),SendError.STALE)
            val current=o.required("current_time").integer()
            demand(current>=0 && if(current>now)current-now<=REVIEW_LIFETIME else now-current<=REVIEW_LIFETIME,SendError.STALE)
            val period=o.required("last_slot").obj().required("period").unsigned()
            val validity=o.required("config").obj().required("operation_validity_periods").unsigned()
            demand(validity>=2uL && period<=ULong.MAX_VALUE-9uL,SendError.STALE)
            return Status(VerifiedNodeContext(chain,version,started),period,validity,MASAmount.parse(o.text("minimal_fees")))
        }
        internal fun decodeObservation(json:JsonElement,signed:SignedTransfer,context:VerifiedNodeContext,now:Long):OperationObservation {
            signed.validate();context.validate(now)
            val rows=json.array();checkRead(rows.size()<=1)
            if(rows.size()==0)return OperationObservation(TransferState.UNKNOWN)
            val row=rows[0].obj();val draft=signed.draft;demand(row.text("id")==draft.operationId,SendError.EVIDENCE)
            fun bool(field:String,required:Boolean=false):Boolean? {
                val v=row.get(field)?.takeUnless { it.isJsonNull }
                if(v==null){checkRead(!required);return null}
                checkRead(v.isJsonPrimitive && v.asJsonPrimitive.isBoolean);return v.asBoolean
            }
            val pool=bool("in_pool",true)!!;val final=bool("is_operation_final");val execution=bool("op_exec_status")
            if(final!=true)return OperationObservation(if(pool||execution!=null)TransferState.PENDING else TransferState.UNKNOWN)
            demand(!pool && execution!=null,SendError.EVIDENCE)
            val op=row.required("operation").obj();val content=op.required("content").obj();val types=content.required("op").obj()
            demand(op.text("id")==draft.operationId && op.text("content_creator_address")==draft.source.address && op.text("content_creator_pub_key")==draft.source.publicKey &&
                op.text("signature")==signed.signature && types.keySet()==setOf("Transaction"),SendError.EVIDENCE)
            val tx=types.required("Transaction").obj()
            demand(tx.text("recipient_address")==draft.recipient && MASAmount.parse(tx.text("amount"))==draft.amount &&
                MASAmount.parse(content.text("fee"))==draft.fee && content.required("expire_period").unsigned()==draft.expiry,SendError.EVIDENCE)
            val bytes=MassaOperation.serialized(MASAmount.parse(content.text("fee")),MASAmount.parse(tx.text("amount")),content.required("expire_period").unsigned(),tx.text("recipient_address"))
            demand(bytes.contentEquals(draft.content) && MassaOperation.operationID(bytes,op.text("content_creator_pub_key"))==draft.operationId &&
                PublicAccount.fromRaw(MassaCodec.decode(op.text("content_creator_pub_key"),"P")).address==draft.source.address &&
                PublicAccount(draft.source.publicKey,draft.source.address).verify(MassaOperation.canonical(bytes,draft.source.publicKey),op.text("signature")),SendError.EVIDENCE)
            val report=FinalReport(context,now,RPC_ENDPOINT,draft.operationId,draft.source.address,draft.source.publicKey,signed.signature,bytes.hex(),signed.binding,execution!!)
            report.validate(signed)
            return OperationObservation(if(execution)TransferState.VERIFIED_SUCCESS else TransferState.VERIFIED_FAILURE,report)
        }
    }
}
