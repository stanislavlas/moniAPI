package personalFinance.models

import java.math.BigDecimal

/** ISO 4217 currency code, e.g. "EUR", "USD", "CZK". Plain string — no enum. */
typealias Currency = String

data class Amount(
    val value: BigDecimal,
    val currency: Currency,
) {
    operator fun plus(amount: Amount): Amount {
        return Amount(currency = this.currency, value = this.value + amount.value)
    }

    operator fun minus(amount: Amount): Amount {
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
