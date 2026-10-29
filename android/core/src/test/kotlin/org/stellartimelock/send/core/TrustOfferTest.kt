package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 1.5.58: Fortress 1.4.21 was sent from the already-paired PC and the phone
 * showed Trust new key although the PC presented the saved pin.
 */
class TrustOfferTest {
    private val pcPin = "dd8d04bc" + "bd".repeat(24) + "284df455"
    private val phonePin = "24c159d8" + "ab".repeat(28)
    private val otherPin = "c".repeat(64)

    @Test
    fun alreadyPairedPeerPresentingTheSavedPinNeverPrompts() {
        assertFalse(TrustNewKey.shouldOffer(pcPin, pcPin, phonePin))
        assertFalse(TrustNewKey.shouldOffer(pcPin, pcPin, null))
        assertFalse(TrustNewKey.shouldPrompt(true, ErrorCodes.KEY_CHANGED, "The paired device key changed.", pcPin, pcPin))
        assertFalse(TrustNewKey.offerStillValid(TrustOffer("PC", "c2c4e158-dcc8-44ff-8769-c0e053b916f5", pcPin, ""), pcPin, phonePin))
    }

    @Test
    fun anUppercaseOfferForTheSavedPinIsStillTheSavedPin() {
        val upper = TrustOffer("PC", "c2c4e158-dcc8-44ff-8769-c0e053b916f5", pcPin.uppercase(), "")
        assertFalse(TrustNewKey.offerStillValid(upper, pcPin, phonePin))
    }

    @Test
    fun aStaleOfferIsDroppedOnceTheSavedPinMatchesIt() {
        val offer = TrustOffer("PC", "c2c4e158-dcc8-44ff-8769-c0e053b916f5", otherPin, "")
        assertTrue(TrustNewKey.offerStillValid(offer, pcPin, phonePin))
        assertFalse(TrustNewKey.offerStillValid(offer, otherPin, phonePin))
        assertFalse(TrustNewKey.offerStillValid(null, pcPin, phonePin))
    }

    @Test
    fun thisPhonesOwnKeyIsNeverOfferedAsThePcKey() {
        assertFalse(TrustNewKey.shouldOffer(phonePin, pcPin, phonePin))
        assertFalse(TrustNewKey.offerStillValid(TrustOffer("PC", "x", phonePin, ""), pcPin, phonePin))
    }

    @Test
    fun malformedOrMissingPinsNeverPrompt() {
        assertFalse(TrustNewKey.shouldOffer("none", pcPin, phonePin))
        assertFalse(TrustNewKey.shouldOffer("", pcPin, phonePin))
        assertFalse(TrustNewKey.shouldOffer(null, pcPin, phonePin))
        assertFalse(TrustNewKey.shouldOffer("dd8d04bc", pcPin, phonePin))
        assertFalse(TrustNewKey.shouldOffer(otherPin, null, phonePin))
        assertFalse(TrustNewKey.shouldOffer(otherPin, "", phonePin))
        assertFalse(TrustNewKey.shouldOffer(otherPin, "dd8d04bc", phonePin))
    }

    @Test
    fun aRealMismatchOfWellFormedPinsStillPrompts() {
        assertTrue(TrustNewKey.shouldOffer(otherPin, pcPin, phonePin))
        assertTrue(TrustNewKey.shouldOffer(otherPin, pcPin, null))
        assertTrue(TrustNewKey.shouldPrompt(true, ErrorCodes.KEY_CHANGED, "The paired device key changed.", otherPin, pcPin))
        assertTrue(TrustNewKey.shouldRaiseKeyChanged(otherPin, pcPin))
    }
}
