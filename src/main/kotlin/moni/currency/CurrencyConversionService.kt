// backend/src/main/kotlin/moni/currency/CurrencyConversionService.kt
package moni.currency

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import moni.models.Amount
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

@Service
class CurrencyConversionService(
    private val objectMapper: ObjectMapper,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val log = LoggerFactory.getLogger(CurrencyConversionService::class.java)

    // Per-pair rate cache — key: "FROM_TO", value: direct exchange rate
    // Caffeine evicts entries automatically after 24 h; thread-safe by design
    private val rateCache: Cache<String, BigDecimal> = Caffeine.newBuilder()
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build()

    // Currency names cache — single sentinel key "all", value: the names map.
    // Uses the same Caffeine TTL approach as rateCache for consistency and thread safety
    // (eliminates the @Volatile pair that had a race window between the two fields).
    private val namesCache: Cache<String, Map<String, String>> = Caffeine.newBuilder()
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build()

    /** For unit tests only — injects rates and names directly into the caches. */
    fun injectForTest(rates: Map<String, BigDecimal>, names: Map<String, String>) {
        rates.forEach { (key, rate) -> rateCache.put(key, rate) }
        namesCache.put("all", names)
    }

    fun convertAmount(amount: Amount, targetCurrency: String): Amount {
        if (amount.currency == targetCurrency) return amount

        val cacheKey = "${amount.currency}_$targetCurrency"
        val rate = rateCache.get(cacheKey) {
            fetchRate(amount.currency, targetCurrency)
        } ?: run {
            log.warn("Currency conversion unavailable for ${amount.currency}→$targetCurrency; returning original amount")
            return amount  // fetch failed and no cached value — return original
        }

        return Amount(amount.value.multiply(rate), targetCurrency)
    }

    fun isValidCurrency(code: String): Boolean {
        if (code.isBlank()) return false
        return getNames().containsKey(code)
    }

    fun getCurrencyNames(): Map<String, String> = getNames()

    private fun getNames(): Map<String, String> =
        namesCache.get("all") { fetchNames() } ?: emptyMap()

    private fun fetchRate(from: String, to: String): BigDecimal? {
        return try {
            val url = "https://api.frankfurter.app/latest?from=$from&to=$to"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.warn("frankfurter.app rate fetch $from→$to returned HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val parsed = objectMapper.readValue<Map<String, Any>>(body)
                @Suppress("UNCHECKED_CAST")
                val rates = parsed["rates"] as? Map<String, Any> ?: return null
                val value = rates[to] ?: return null
                log.debug("Fetched rate $from→$to = $value")
                BigDecimal(value.toString())
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch rate $from→$to: ${e.message}")
            null
        }
    }

    private fun fetchNames(): Map<String, String>? {
        return try {
            val request = Request.Builder()
                .url("https://api.frankfurter.app/currencies")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.warn("frankfurter.app /currencies returned HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val names = objectMapper.readValue<Map<String, String>>(body)
                log.info("Currency names refreshed: ${names.size} currencies")
                names
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch currency names: ${e.message}")
            null
        }
    }
}
