package org.stellartimelock.stellarsend

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.stellartimelock.send.ConsentReceiver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConsentReceiverTest {
    @Test
    fun manifestRegistersConsentReceiver() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, ConsentReceiver::class.java),
            0
        )
        assertEquals(ConsentReceiver::class.java.name, info.name)
    }
}
