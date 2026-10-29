package org.stellartimelock.send.core

import java.math.BigDecimal

/** One-time phone app price. */
object AppPrice {
    const val TEXT = "$2.9999"

    fun label(amount: BigDecimal): String =
        "$" + amount.stripTrailingZeros().toPlainString()

    fun withApp(amount: BigDecimal): String = "${label(amount)} app"
}
