package com.massivex.android.network

import com.google.gson.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CancellationException

private val cryptoAssets = mapOf("BTC" to "bitcoin", "ETH" to "ethereum", "SOL" to "solana")
private val preferredCodes = listOf("USD", "EUR", "RUB", "BTC", "ETH", "SOL", "JPY", "AED", "KZT", "UAH", "GBP", "CHF", "CNY", "KRW", "IDR", "INR")
data class MarketQuote(val currency: String, val perMas: BigDecimal, val updated: Long, val fxDate: Long? = null) {
    fun usable(now: Long) = perMas.signum() > 0 && updated <= now + 60000 && now - updated <= 900000 && (fxDate == null || (fxDate <= now + 86400000 && now - fxDate <= 7 * 86400000L))
    val source get() = if (fxDate == null) "CoinGecko" else "CoinGecko + Frankfurter"
    fun value(nano: ULong): String {
        val value = BigDecimal(nano.toString()).movePointLeft(9).multiply(perMas, MathContext.DECIMAL128)
        val scale = if (currency in cryptoAssets) 12 else 2
        return if (value.signum() > 0 && value < BigDecimal.ONE.movePointLeft(scale)) "<" + BigDecimal.ONE.movePointLeft(scale).toPlainString() else value.setScale(scale, RoundingMode.HALF_EVEN).toPlainString()
    }
}
interface MarketReader {
    suspend fun currencies(now: Long): List<String>
    suspend fun quote(currency: String, now: Long): MarketQuote
}
class PublicMarketReads(private val transport: ReadTransport) : MarketReader {
    private var direct = emptySet<String>(); private var fx = emptySet<String>(); private var fetched: Long? = null
    override suspend fun currencies(now: Long): List<String> {
        if (fetched == null || now - fetched!! > 6 * 3600000 || now < fetched!!) {
            val codes = readPublicJson(transport, ReadRequest.currencies()).array()
            val parsed = codes.map { it.string() }.filter { it.matches(Regex("[a-z]{3}")) }.map { it.uppercase(Locale.ROOT) }.toSet()
            checkRead("USD" in parsed)
            var extra = emptySet<String>()
            try {
                extra = readPublicJson(transport, ReadRequest.fxCurrencies()).array().mapNotNull {
                    val row = it.obj(); val code = row.text("iso_code")
                    // end_date denotes latest observation coverage, not a promise of a current FX quote.
                    val end = row.optionalText("end_date") ?: return@mapNotNull null
                    val date = dateEpoch(end)
                    return@mapNotNull code.takeIf { value -> value.matches(Regex("[A-Z]{3}")) && date <= now + 86400000 && now - date <= 7 * 86400000L }
                }.toSet()
            } catch (e: CancellationException) { throw e } catch (_: ReadFailure) { /* FX is optional; direct quotes still work. */ }
            direct = parsed; fx = extra; fetched = now
        }
        val all = (direct + fx + cryptoAssets.keys).filter { it in cryptoAssets || runCatching { java.util.Currency.getInstance(it) }.isSuccess }.toSet()
        return preferredCodes.filter { it in all } + all.filterNot { it in preferredCodes }.sorted()
    }
    override suspend fun quote(currency: String, now: Long): MarketQuote {
        val supported = currencies(now)
        if (currency !in supported) throw ReadFailure(ReadError.UNSUPPORTED)
        val asset = cryptoAssets[currency]
        val code = if (asset != null || currency !in direct) "USD" else currency
        val root = readPublicJson(transport, ReadRequest.price(code, asset)).obj()
        val massa = root.required("massa").obj(); val rate = decimal(massa.required(code.lowercase(Locale.ROOT)))
        val updated = timestamp(massa.required("last_updated_at"))
        var result = MarketQuote(currency, rate, updated)
        if (asset != null) {
            val denominator = root.required(asset).obj()
            val time = timestamp(denominator.required("last_updated_at"))
            checkRead(kotlin.math.abs(time - updated) <= 300000)
            result = MarketQuote(currency, rate.divide(decimal(denominator.required("usd")), MathContext.DECIMAL128), minOf(time, updated))
        } else if (currency !in direct) {
            val value = readPublicJson(transport, ReadRequest.fx(currency)).obj()
            checkRead(value.text("base") == "USD" && value.text("quote") == currency)
            val date = dateEpoch(value.text("date"))
            result = result.copy(perMas = rate.multiply(decimal(value.required("rate")), MathContext.DECIMAL128), fxDate = date)
        }
        if (!result.usable(now)) throw ReadFailure(ReadError.STALE)
        return result
    }
    companion object {
        fun dateEpoch(text: String): Long {
            checkRead(text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
            return try { LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) { invalid() }
        }
        fun decimal(value: JsonElement): BigDecimal {
            checkRead(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            val number = try { BigDecimal(value.asString) } catch (_: Exception) { invalid() }
            checkRead(number.signum() > 0 && number.precision() <= 60 && number.scale() in -30..60)
            return number
        }
        fun timestamp(value: JsonElement): Long = value.integer().also { checkRead(it <= Long.MAX_VALUE / 1000) } * 1000
    }
}
