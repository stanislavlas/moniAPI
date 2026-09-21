// backend/src/main/kotlin/personalFinance/currency/CurrencyController.kt
package personalFinance.currency

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
