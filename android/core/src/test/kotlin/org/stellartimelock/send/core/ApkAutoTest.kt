package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApkAutoTest {
    @Test
    fun silentUpdateRequiresAndroid12InstallerSignatureAndTargetSdk() {
        assertTrue(ApkAuto.canUpdateSilently(31, true, true, ApkSignatures.MATCH, 29))
        assertTrue(ApkAuto.canUpdateSilently(32, true, true, ApkSignatures.MATCH, 29))
        assertTrue(ApkAuto.canUpdateSilently(33, true, true, ApkSignatures.MATCH, 30))
        assertTrue(ApkAuto.canUpdateSilently(34, true, true, ApkSignatures.MATCH, 31))
        assertTrue(ApkAuto.canUpdateSilently(35, true, true, ApkSignatures.MATCH, 33))
        assertTrue(ApkAuto.canUpdateSilently(36, true, true, ApkSignatures.MATCH, 34))
        assertTrue(ApkAuto.canUpdateSilently(37, true, true, ApkSignatures.MATCH, 35))
        assertTrue(ApkBatch.silentUpdate(35, true, true, ApkSignatures.MATCH, 33))

        assertFalse(ApkAuto.canUpdateSilently(30, true, true, ApkSignatures.MATCH, 35))
        assertFalse(ApkAuto.canUpdateSilently(35, false, true, ApkSignatures.MATCH, 35))
        assertFalse(ApkAuto.canUpdateSilently(35, true, false, ApkSignatures.MATCH, 35))
        assertFalse(ApkAuto.canUpdateSilently(35, true, true, ApkSignatures.DIFFER, 35))
        assertFalse(ApkAuto.canUpdateSilently(35, true, true, "", 35))
        assertFalse(ApkAuto.canUpdateSilently(35, true, true, ApkSignatures.MATCH, 32))
        assertFalse(ApkAuto.canUpdateSilently(34, true, true, ApkSignatures.MATCH, 30))
        assertFalse(ApkAuto.canUpdateSilently(37, true, true, ApkSignatures.MATCH, 34))
        assertFalse(ApkAuto.canUpdateSilently(35, true, true, ApkSignatures.MATCH, -1))
    }

    @Test
    fun updatingFlipSendItselfCanBeSilentWhenTheSignatureAndTargetSdkMatch() {
        assertTrue(ApkAuto.canUpdateSilently(35, true, installedBySelf = false, ApkSignatures.MATCH, 35, updatingSelf = true))
        assertFalse(ApkAuto.canUpdateSilently(35, true, false, ApkSignatures.DIFFER, 35, updatingSelf = true))
        assertFalse(ApkAuto.canUpdateSilently(35, false, false, ApkSignatures.MATCH, 35, updatingSelf = true))
    }

    @Test
    fun planReplacesInstalledAppsAndNeverUninstallsProtectedOnes() {
        val market = apk("m", "Market", installed = true, bySelf = true, signature = ApkSignatures.MATCH, target = 34, code = 3, installedCode = 1)
        val profile = apk("p", "Profile", installed = true, bySelf = true, signature = ApkSignatures.MATCH, target = 34, code = 8, installedCode = 2)
        val keyboard = apk("k", "Keyboard", installed = true, bySelf = true, signature = ApkSignatures.MATCH, target = 35, code = 9, installedCode = 4)
        val fresh = apk("f", "Fresh", installed = false, bySelf = false, signature = "", target = 35, code = 1, installedCode = -1)
        val other = apk("o", "Other", installed = true, bySelf = false, signature = ApkSignatures.MATCH, target = 35, code = 4, installedCode = 1)
        val mismatch = apk("x", "Mismatch", installed = true, bySelf = true, signature = ApkSignatures.DIFFER, target = 35, code = 6, installedCode = 1)
        val lowSdk = apk("l", "Legacy", installed = true, bySelf = true, signature = ApkSignatures.MATCH, target = 28, code = 6, installedCode = 1)
        val older = apk("d", "Old", installed = true, bySelf = true, signature = ApkSignatures.MATCH, target = 35, code = 1, installedCode = 9)
        val current = apk("c", "Current", installed = true, bySelf = true, signature = ApkSignatures.MATCH, target = 35, code = 4, installedCode = 4)
        val self = apk(
            "s", "FlipSend", installed = true, bySelf = false, signature = ApkSignatures.MATCH, target = 35,
            code = 10, installedCode = 8, packageName = "org.stellartimelock.stellarsend"
        )

        val plan = ApkAuto.plan(
            listOf(market, fresh, profile, other, keyboard, mismatch, lowSdk, older, current, self),
            deviceSdk = 35,
            selfPackage = "org.stellartimelock.stellarsend"
        )

        assertEquals(listOf("Market", "Profile", "Keyboard", "FlipSend"), plan.silent.map { it.appName })
        assertEquals(listOf("Fresh", "Other", "Legacy"), plan.ready.map { it.appName })
        assertEquals(listOf("Mismatch"), plan.replace.map { it.appName })
        assertTrue(plan.skipped.isEmpty())
        assertTrue(plan.uninstalls)
        assertFalse(ApkAuto.notifyIncomingApps())
        assertEquals(
            listOf("Mismatch", "Market", "Profile", "Keyboard", "Fresh", "Other", "Legacy", "FlipSend"),
            plan.enqueueOrder("org.stellartimelock.stellarsend").map { it.appName }
        )
        assertEquals("Updated: Market, Profile, Keyboard", ApkAuto.updatedLine(listOf("Market", "Profile", "Keyboard")))
        assertEquals("Updated: Market", ApkAuto.updatedLine(listOf(" Market ")))
        assertEquals("", ApkAuto.updatedLine(emptyList()))
        assertEquals("1 app ready to install", ApkAuto.readyLine(1))
        assertEquals("3 apps ready to install", ApkAuto.readyLine(3))
        assertEquals("", ApkAuto.readyLine(0))
    }

    @Test
    fun planIgnoresAnOlderCopyOfTheSamePackage() {
        val old = apk("old", "Notes", installed = true, bySelf = true, signature = ApkSignatures.MATCH, target = 35, code = 3, installedCode = 1, packageName = "app.notes")
        val neu = apk("new", "Notes", installed = true, bySelf = true, signature = ApkSignatures.MATCH, target = 35, code = 8, installedCode = 1, packageName = "app.notes")
        val plan = ApkAuto.plan(listOf(old, neu), deviceSdk = 35, selfPackage = "org.stellartimelock.stellarsend")
        assertEquals(listOf("new"), plan.silent.map { it.id })
        assertTrue(plan.replace.isEmpty())
        assertTrue(plan.ready.isEmpty())
    }

    @Test
    fun aBatchIsSettledOnlyAfterEveryFileIsSaved() {
        val open = ReceiveBoard()
        open.noteProgress("t", "A.apk", 1, 1, batchIndex = 1, batchCount = 3)
        open.noteFile("1", "A.apk", "apk", "/inbox/A.apk")
        assertFalse(ApkAuto.batchSettled(open.snap()))
        open.noteFile("2", "B.apk", "apk", "/inbox/B.apk")
        open.noteFile("3", "C.apk", "apk", "/inbox/C.apk")
        open.noteIdle()
        assertTrue(ApkAuto.batchSettled(open.snap()))

        val single = ReceiveBoard()
        single.noteFile("1", "One.apk", "apk", "/inbox/One.apk")
        single.noteIdle()
        assertTrue(ApkAuto.batchSettled(single.snap()))
        assertFalse(ApkAuto.batchSettled(ReceiveBoard().snap()))
    }

    @Test
    fun incomingFilesFromThePairedPcAreAcceptedAndUnpairedSendersStillAsk() {
        assertTrue(ApkAuto.autoAcceptOffer("Market.apk", paired = true))
        assertTrue(ApkAuto.autoAcceptOffer("Folder/App.APK", paired = true))
        assertTrue(ApkAuto.autoAcceptOffer("notes.txt", paired = true))
        assertTrue(ApkAuto.autoAcceptOffer("photo.jpg", paired = true))
        assertTrue(ApkAuto.autoAcceptOffer("notes.apk.txt", paired = true))
        assertFalse(ApkAuto.autoAcceptOffer("Market.apk", paired = false))
        assertFalse(ApkAuto.autoAcceptOffer("notes.txt", paired = false))
        assertFalse(ApkAuto.autoAcceptOffer("photo.jpg", paired = false))
    }

    @Test
    fun dataAppsAreSkippedOnlyWhenAnInPlaceUpdateWouldWipeData() {
        assertTrue(
            ApkAuto.skipProtected("com.stellartimelock", alreadyInstalled = true, ApkSignatures.DIFFER)
        )
        assertTrue(
            ApkAuto.skipProtected("com.stellartimelock.stellaros", alreadyInstalled = true, ApkSignatures.DIFFER)
        )
        assertTrue(
            ApkAuto.skipProtected("com.stellartimelock.stellarvault", alreadyInstalled = true, "")
        )
        assertTrue(ApkAuto.skipProtected("app.textpipe", alreadyInstalled = true, ApkSignatures.DIFFER))
        assertTrue(
            ApkAuto.skipProtected("app.stellar.deliverydriver", alreadyInstalled = true, ApkSignatures.DIFFER)
        )
        assertFalse(
            ApkAuto.skipProtected("com.stellartimelock", alreadyInstalled = true, ApkSignatures.MATCH)
        )
        assertFalse(
            ApkAuto.skipProtected("com.stellartimelock", alreadyInstalled = false, ApkSignatures.DIFFER)
        )
        assertFalse(ApkAuto.skipProtected("app.notes", alreadyInstalled = true, ApkSignatures.DIFFER))
    }

    @Test
    fun incomingPcAppsAlwaysStartAnInstallAndNeverWaitForAFlipSendTap() {
        assertFalse(ApkAuto.waitsForFlipSendTap())
        assertFalse(ApkAuto.abandonsIfAndroidAsks())
        assertTrue(ApkAuto.requestNoUserActionForIncoming())
        assertTrue(ApkAuto.startIncomingSession())
        val press = apk(
            "press", "Press", installed = true, bySelf = false, signature = ApkSignatures.MATCH,
            target = 35, code = 9, installedCode = 4, packageName = "app.stellar.keyboard"
        )
        val plan = ApkAuto.plan(listOf(press), deviceSdk = 35, selfPackage = "org.stellartimelock.stellarsend")
        assertEquals(listOf("press"), plan.ready.map { it.id })
        assertTrue(plan.replace.isEmpty())
        assertTrue(plan.skipped.isEmpty())
        assertEquals(listOf("press"), plan.enqueueOrder("org.stellartimelock.stellarsend").map { it.id })
        assertTrue(ApkAuto.startIncomingSession())
        assertFalse(ApkAuto.waitsForFlipSendTap())
    }

    @Test
    fun planSkipsProtectedDataAppsAndInstallsFlipSendLast() {
        val wallet = apk(
            "w", "Wallet", installed = true, bySelf = false, signature = ApkSignatures.DIFFER,
            target = 35, code = 9, installedCode = 2, packageName = "com.stellartimelock"
        )
        val market = apk("m", "Market", installed = true, bySelf = true, signature = ApkSignatures.MATCH, target = 34, code = 3, installedCode = 1)
        val self = apk(
            "s", "FlipSend", installed = true, bySelf = false, signature = ApkSignatures.MATCH, target = 35,
            code = 10, installedCode = 8, packageName = "org.stellartimelock.stellarsend"
        )
        val freshWallet = apk(
            "fw", "Wallet", installed = false, bySelf = false, signature = "",
            target = 35, code = 1, installedCode = -1, packageName = "com.stellartimelock"
        )
        val plan = ApkAuto.plan(
            listOf(self, wallet, market),
            deviceSdk = 35,
            selfPackage = "org.stellartimelock.stellarsend"
        )
        assertEquals(listOf("Market", "FlipSend"), plan.silent.map { it.appName })
        assertTrue(plan.replace.isEmpty())
        assertEquals(listOf("w"), plan.skipped.map { it.id })
        assertEquals("Skipped: cannot update without wiping data", ApkAuto.SKIP_PROTECTED)
        assertEquals(listOf("Market", "FlipSend"), plan.enqueueOrder("org.stellartimelock.stellarsend").map { it.appName })
        assertFalse(plan.uninstalls)

        val firstInstall = ApkAuto.plan(listOf(freshWallet), deviceSdk = 35, selfPackage = "org.stellartimelock.stellarsend")
        assertEquals(listOf("fw"), firstInstall.ready.map { it.id })
        assertTrue(firstInstall.skipped.isEmpty())

        val sameSignerWallet = apk(
            "sw", "Wallet", installed = true, bySelf = false, signature = ApkSignatures.MATCH,
            target = 35, code = 9, installedCode = 2, packageName = "com.stellartimelock"
        )
        val inPlace = ApkAuto.plan(listOf(sameSignerWallet), deviceSdk = 35, selfPackage = "org.stellartimelock.stellarsend")
        assertEquals(listOf("sw"), inPlace.ready.map { it.id })
        assertTrue(inPlace.replace.isEmpty())
        assertTrue(inPlace.skipped.isEmpty())
    }

    @Test
    fun incomingUpdatesReplaceTheOldCopyAndProtectedAppsAreNeverUninstalled() {
        assertTrue(ApkAuto.shouldReplace("app.notes", alreadyInstalled = true, selfPackage = "org.stellartimelock.stellarsend", signatureMatch = ApkSignatures.DIFFER))
        assertFalse(ApkAuto.shouldReplace("app.notes", alreadyInstalled = true, selfPackage = "org.stellartimelock.stellarsend", signatureMatch = ApkSignatures.MATCH))
        assertFalse(ApkAuto.shouldReplace("app.notes", alreadyInstalled = false, selfPackage = "org.stellartimelock.stellarsend", signatureMatch = ApkSignatures.DIFFER))
        assertFalse(ApkAuto.shouldReplace("org.stellartimelock.stellarsend", alreadyInstalled = true, selfPackage = "org.stellartimelock.stellarsend", signatureMatch = ApkSignatures.DIFFER))
        assertTrue(ApkAuto.neverUninstall("org.stellartimelock.stellarsend", "org.stellartimelock.stellarsend"))
        assertFalse(ApkAuto.neverUninstall("org.stellartimelock.stellarsend"))
        for (pkg in listOf(
            "com.stellartimelock",
            "com.stellartimelock.stellaros",
            "com.stellartimelock.stellarvault",
            "app.textpipe",
            "app.stellar.deliverydriver"
        )) {
            assertTrue(ApkAuto.neverUninstall(pkg))
            assertFalse(ApkAuto.shouldReplace(pkg, alreadyInstalled = true, selfPackage = "org.stellartimelock.stellarsend"))
        }
        assertFalse(ApkAuto.neverUninstall("app.notes"))
        assertFalse(ApkAuto.waitsForFlipSendTap())
        assertFalse(SelfUpdate.mayUninstallSelf())
        assertFalse(SelfUpdate.requestSilent(eligible = true, installerInForeground = false))
    }

    @Test
    fun installStatusArrivesByBroadcastAndBackgroundPendingPostsANotification() {
        assertEquals("broadcast", ApkInstallDelivery.STATUS_CALLBACK)
        assertFalse(ApkInstallDelivery.openConfirmNow(appVisible = false))
        assertTrue(ApkInstallDelivery.openConfirmNow(appVisible = true))
        assertTrue(ApkInstallDelivery.notifyConfirm(pendingUserAction = true, appVisible = false))
        assertFalse(ApkInstallDelivery.notifyConfirm(pendingUserAction = true, appVisible = true))
        assertFalse(ApkInstallDelivery.notifyConfirm(pendingUserAction = false, appVisible = false))
    }

    @Test
    fun thePcInstallSwitchIsPlainJsonAndDefaultsOn() {
        assertTrue(PcInstallPreference.DEFAULT)
        assertEquals(true, PcInstallPreference.decode(PcInstallPreference.encode(true)))
        assertEquals(false, PcInstallPreference.decode(PcInstallPreference.encode(false)))
        assertEquals(null, PcInstallPreference.decode(ByteArray(0)))
        assertEquals(null, PcInstallPreference.decode("not json".toByteArray()))
        val text = PcInstallPreference.encode(false).toString(Charsets.UTF_8)
        assertTrue(text.contains("autoInstallFromPc"))
        assertFalse(text.contains("keystore"))
    }

    private fun apk(
        id: String,
        name: String,
        installed: Boolean,
        bySelf: Boolean,
        signature: String,
        target: Int,
        code: Long,
        installedCode: Long,
        packageName: String = "app.$id"
    ) = ReceiveFile(
        id = id,
        name = "$name.apk",
        kind = "apk",
        path = "/inbox/$name.apk",
        appName = name,
        packageName = packageName,
        incomingVersion = "2",
        incomingCode = code,
        installedVersion = if (installed) "1" else "",
        installedCode = if (installed) installedCode else -1,
        hasInstalled = installed,
        signatureMatch = signature,
        targetSdk = target,
        installedBySelf = bySelf
    )
}
