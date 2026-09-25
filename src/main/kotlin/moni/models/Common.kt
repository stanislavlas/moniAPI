package moni.models

import java.math.BigDecimal

/** ISO 4217 currency code, e.g. "EUR", "USD", "CZK". Plain string — no enum. */
typealias Currency = String

data class Amount(
    val value: BigDecimal,
    val currency: Currency,
) {
    operator fun plus(amount: Amount): Amount {
        require(currency == amount.currency) {
            "Cannot add amounts in different currencies: $currency vs ${amount.currency}"
        }
        return Amount(currency = this.currency, value = this.value + amount.value)
    }

    operator fun minus(amount: Amount): Amount {
        require(currency == amount.currency) {
            "Cannot subtract amounts in different currencies: $currency vs ${amount.currency}"
        }
        return Amount(currency = this.currency, value = this.value - amount.value)
    }
}

enum class TransactionType {
    EXPENSE,
    INCOME,
    INVESTMENT,

    @com.fasterxml.jackson.annotation.JsonEnumDefaultValue
    UNSUPPORTED,
}
