// backend/src/main/kotlin/moni/currency/CurrencyConversionService.kt
package moni.currency

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import moni.models.Amount
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

@Service
class CurrencyConversionService(
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .build(),
) {
    private val log = LoggerFactory.getLogger(CurrencyConversionService::class.java)

    companion object {
        private const val FRANKFURTER_BASE = "https://api.frankfurter.dev/v1"
    }

    // Minimum set of known-valid ISO 4217 codes used as a fallback when the
    // frankfurter.dev /currencies endpoint is unreachable (e.g. at startup or
    // during registration). Add more codes here as needed.
    private val fallbackCurrencies: Map<String, String> = mapOf(
        "CZK" to "Czech Koruna",
        "EUR" to "Euro",
        "USD" to "United States Dollar",
    )

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
        // Check live/cached names first; fall back to the hardcoded set so that
        // a transient API outage never blocks registrations for known currencies.
        return getNames().containsKey(code) || fallbackCurrencies.containsKey(code)
    }

    fun getCurrencyNames(): Map<String, String> = getNames()

    private fun getNames(): Map<String, String> =
        namesCache.get("all") { fetchNames() } ?: fallbackCurrencies

    private fun fetchRate(from: String, to: String): BigDecimal? {
        return try {
            val url = "$FRANKFURTER_BASE/latest?from=$from&to=$to"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.warn("frankfurter.dev rate fetch $from→$to returned HTTP ${response.statusCode()}")
                return null
            }
            val parsed = objectMapper.readValue<Map<String, Any>>(response.body())
            @Suppress("UNCHECKED_CAST")
            val rates = parsed["rates"] as? Map<String, Any> ?: return null
            val value = rates[to] ?: return null
            log.debug("Fetched rate $from→$to = $value")
            BigDecimal(value.toString())
        } catch (e: Exception) {
            log.warn("Failed to fetch rate $from→$to: ${e.message}")
            null
        }
    }

    private fun fetchNames(): Map<String, String>? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$FRANKFURTER_BASE/currencies"))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.warn("frankfurter.dev /currencies returned HTTP ${response.statusCode()}")
                return null
            }
            val names = objectMapper.readValue<Map<String, String>>(response.body())
            log.info("Currency names refreshed: ${names.size} currencies")
            names
        } catch (e: Exception) {
            log.warn("Failed to fetch currency names: ${e.message}")
            null
        }
    }
}
