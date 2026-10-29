package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteDesktopGateTest {
    @Test
    fun disabledBuildRefusesDesktopOpsAndKeepsPhoneControl() {
        RemotePhrases.install(RemotePhrases.neutral)
        assertFalse(RemoteDesktopGate.allow(false, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_STATUS, RemoteControl.FROM_PHONE))
        assertFalse(RemoteDesktopGate.allow(false, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_WATCH, RemoteControl.FROM_PHONE))
        assertFalse(RemoteDesktopGate.allow(false, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_REQUEST, RemoteControl.FROM_PHONE))
        assertFalse(RemoteDesktopGate.allow(false, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_REQUEST, ""))
        assertTrue(RemoteDesktopGate.allow(false, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_REQUEST, RemoteControl.FROM_PC))
        assertTrue(RemoteDesktopGate.allow(false, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_DRIVE, RemoteControl.FROM_PC))
        assertTrue(RemoteDesktopGate.allow(false, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_STOP, RemoteControl.FROM_PHONE))
        assertTrue(RemoteDesktopGate.allow(false, CompanionServices.TERMINAL, "run", RemoteControl.FROM_PHONE))
        assertEquals("", RemoteDesktopGate.label(false))
        assertEquals(RemoteDesktopGate.SETTINGS_LEAD_HIDDEN, RemoteDesktopGate.settingsLead(false))
        assertFalse(RemoteDesktopGate.UNAVAILABLE.contains("remote desktop", ignoreCase = true))
        assertFalse(RemoteDesktopGate.phoneControlDetail(false).contains("remote desktop", ignoreCase = true))
    }

    @Test
    fun enabledBuildKeepsDesktopAndUsesTheBetaLabel() {
        SideloadRemoteWords.install()
        assertTrue(RemoteDesktopGate.allow(true, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_REQUEST, RemoteControl.FROM_PHONE))
        assertTrue(RemoteDesktopGate.allow(true, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_WATCH, RemoteControl.FROM_PC))
        assertTrue(RemoteDesktopGate.allow(true, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_STATUS, ""))
        assertEquals("Remote desktop (beta)", RemoteDesktopGate.label(true))
        assertEquals(RemoteDesktopGate.LINE, RemoteDesktop.LINE)
        assertTrue(RemoteDesktopGate.phoneControlDetail(true).contains("Remote desktop does not use it."))
    }
}
