// backend/src/test/kotlin/personalFinance/currency/CurrencyConversionServiceTest.kt
package personalFinance.currency

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import personalFinance.models.Amount
import java.math.BigDecimal

class CurrencyConversionServiceTest {

    private lateinit var service: CurrencyConversionService

    @BeforeEach
    fun setUp() {
        service = CurrencyConversionService()
        // Inject known rates and names to avoid real HTTP calls in unit tests
        service.injectForTest(
            rates = mapOf(
                "CZK_USD" to BigDecimal("0.04777"),
                "USD_CZK" to BigDecimal("20.937"),
                "EUR_USD" to BigDecimal("1.1592"),
                "USD_EUR" to BigDecimal("0.86270"),
                "CZK_EUR" to BigDecimal("0.04121"),
                "EUR_CZK" to BigDecimal("24.264"),
            ),
            names = mapOf(
                "EUR" to "Euro",
                "USD" to "United States Dollar",
                "CZK" to "Czech Koruna",
                "GBP" to "British Pound",
            )
        )
    }

    @Test
    fun `same currency returns identical amount`() {
        val input = Amount(BigDecimal("100.00"), "EUR")
        val result = service.convertAmount(input, "EUR")
        assertEquals(0, BigDecimal("100.00").compareTo(result.value))
        assertEquals("EUR", result.currency)
    }

    @Test
    fun `CZK to USD conversion uses direct rate`() {
        val input = Amount(BigDecimal("250.00"), "CZK")
        val result = service.convertAmount(input, "USD")
        // 250 * 0.04777 = 11.9425
        assertEquals(0, BigDecimal("11.9425").compareTo(result.value))
        assertEquals("USD", result.currency)
    }

    @Test
    fun `EUR to USD conversion`() {
        val input = Amount(BigDecimal("100.00"), "EUR")
        val result = service.convertAmount(input, "USD")
        // 100 * 1.1592 = 115.92
        assertEquals(0, BigDecimal("115.92").compareTo(result.value))
        assertEquals("USD", result.currency)
    }

    @Test
    fun `USD to EUR conversion`() {
        val input = Amount(BigDecimal("100.00"), "USD")
        val result = service.convertAmount(input, "EUR")
        // 100 * 0.86270 = 86.27
        assertEquals(0, BigDecimal("86.27").compareTo(result.value))
        assertEquals("EUR", result.currency)
    }

    @Test
    fun `unknown source currency returns original amount unchanged`() {
        val input = Amount(BigDecimal("50.00"), "XYZ")
        val result = service.convertAmount(input, "EUR")
        assertEquals(BigDecimal("50.00"), result.value)
        assertEquals("XYZ", result.currency)
    }

    @Test
    fun `isValidCurrency returns true for known codes`() {
        assertTrue(service.isValidCurrency("EUR"))
        assertTrue(service.isValidCurrency("USD"))
        assertTrue(service.isValidCurrency("GBP"))
    }

    @Test
    fun `isValidCurrency returns false for unknown codes`() {
        assertFalse(service.isValidCurrency("XYZ"))
        assertFalse(service.isValidCurrency(""))
    }

    @Test
    fun `getCurrencyNames returns injected names`() {
        val names = service.getCurrencyNames()
        assertEquals("Euro", names["EUR"])
        assertEquals("Czech Koruna", names["CZK"])
    }
}
