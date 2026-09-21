// backend/src/main/kotlin/personalFinance/currency/CurrencyConversionService.kt
package personalFinance.currency

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import personalFinance.models.Amount
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@Service
class CurrencyConversionService(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    private val log = LoggerFactory.getLogger(CurrencyConversionService::class.java)

    // Per-pair rate cache — key: "FROM_TO", value: direct exchange rate
    // Caffeine evicts entries automatically after 24 h; thread-safe by design
    private val rateCache: Cache<String, BigDecimal> = Caffeine.newBuilder()
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build()

    // Currency names map — single value, manual TTL
    @Volatile private var namesMap: Map<String, String> = emptyMap()
    @Volatile private var namesFetchedAt: Instant = Instant.EPOCH

    /** For unit tests only — injects rates and names directly into the caches. */
    fun injectForTest(rates: Map<String, BigDecimal>, names: Map<String, String>) {
        rates.forEach { (key, rate) -> rateCache.put(key, rate) }
        namesMap = names
        namesFetchedAt = Instant.now()
    }

    fun convertAmount(amount: Amount, targetCurrency: String): Amount {
        if (amount.currency == targetCurrency) return amount

        val cacheKey = "${amount.currency}_$targetCurrency"
        val rate = rateCache.get(cacheKey) {
            fetchRate(amount.currency, targetCurrency)
        } ?: return amount  // fetch failed and no cached value — return original

        return Amount(amount.value.multiply(rate), targetCurrency)
    }

    fun isValidCurrency(code: String): Boolean {
        if (code.isBlank()) return false
        ensureFreshNames()
        return namesMap.containsKey(code)
    }

    fun getCurrencyNames(): Map<String, String> {
        ensureFreshNames()
        return namesMap
    }

    private fun ensureFreshNames() {
        if (namesMap.isNotEmpty() && ChronoUnit.HOURS.between(namesFetchedAt, Instant.now()) < 24) return
        fetchNames()
    }

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

    private fun fetchNames() {
        try {
            val request = Request.Builder()
                .url("https://api.frankfurter.app/currencies")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.warn("frankfurter.app /currencies returned HTTP ${response.code}")
                    return
                }
                val body = response.body?.string() ?: return
                namesMap = objectMapper.readValue<Map<String, String>>(body)
                namesFetchedAt = Instant.now()
                log.info("Currency names refreshed: ${namesMap.size} currencies")
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch currency names: ${e.message}")
        }
    }
}
