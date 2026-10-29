package org.stellartimelock.send.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * FlipSend's only purchase is the one-time Play unlock. Any marketplace or delivery fee that is
 * calculated later has to follow this rule: XLM only, 10% to the Project
 * Development Fund, and payouts through Chime. Passwords are never stored.
 */
object PaymentPolicy {
    const val ASSET = "XLM"
    const val PAYOUT = "Chime"
    val DEVELOPMENT_FUND_SHARE: BigDecimal = BigDecimal("0.10")

    data class Split(
        val developmentFund: BigDecimal,
        val recipient: BigDecimal,
        val asset: String = ASSET,
        val payout: String = PAYOUT
    )

    fun accepts(asset: String?): Boolean = asset?.trim()?.equals(ASSET, ignoreCase = true) == true

    fun mayStorePassword(@Suppress("UNUSED_PARAMETER") password: String?): Boolean = false

    fun split(amount: BigDecimal): Split {
        require(amount.signum() >= 0) { "Amount must be zero or more." }
        val fund = amount.multiply(DEVELOPMENT_FUND_SHARE).setScale(7, RoundingMode.HALF_UP)
        val recipient = amount.subtract(fund).setScale(7, RoundingMode.HALF_UP)
        return Split(fund, recipient)
    }
}
