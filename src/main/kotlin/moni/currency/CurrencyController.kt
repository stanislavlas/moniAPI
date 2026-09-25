// backend/src/main/kotlin/moni/currency/CurrencyController.kt
package moni.currency

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/currencies")
class CurrencyController(
    private val currencyConversionService: CurrencyConversionService,
) {
    @GetMapping
    fun getCurrencies(): Map<String, String> {
        return currencyConversionService.getCurrencyNames()
    }
}
