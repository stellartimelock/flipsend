package org.stellartimelock.stellarsend

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.stellartimelock.send.CopyToPcActivity
import org.stellartimelock.send.CopyToPcService
import org.stellartimelock.send.CopyToPcWork
import org.stellartimelock.send.FortressBotDelivery
import org.stellartimelock.send.core.CopyToPc
import org.stellartimelock.send.core.FortressBotHandoff

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CopyToPcActivityTest {
    @Test
    fun fortressLoginExtrasBecomeAUsernamePasswordPair() {
        val intent = fortressLogin()
        val request = CopyToPcWork.requestFrom(intent)
        assertEquals("alex\nhunter2", request.clipboardText)
        assertEquals(30, request.clearAfterSeconds)
        assertEquals(CopyToPc.SOURCE, request.source)
        assertEquals(CopyToPc.KIND_LOGIN, request.kind)
        assertNull(intent.getStringExtra(CopyToPc.EXTRA_PASSWORD))
        assertNull(intent.getStringExtra(CopyToPc.EXTRA_USERNAME))
    }

    @Test
    fun aCardCopiesThePairAndLeavesOptionalExtrasOffTheClipboard() {
        val intent = Intent(CopyToPc.ACTION).apply {
            putExtra(CopyToPc.EXTRA_KIND, CopyToPc.KIND_CARD)
            putExtra(CopyToPc.EXTRA_USERNAME, "4111111111111111")
            putExtra(CopyToPc.EXTRA_PASSWORD, "123")
            putExtra(CopyToPc.EXTRA_HOLDER, "ALEX S")
            putExtra(CopyToPc.EXTRA_NUMBER, "4111111111111111")
            putExtra(CopyToPc.EXTRA_EXP_MONTH, "12")
            putExtra(CopyToPc.EXTRA_EXP_YEAR, "29")
            putExtra(CopyToPc.EXTRA_CVV, "123")
            putExtra(CopyToPc.EXTRA_SOURCE, CopyToPc.SOURCE)
            putExtra(CopyToPc.EXTRA_CLEAR_AFTER_SECONDS, 30)
        }
        val request = CopyToPcWork.requestFrom(intent)
        assertEquals("4111111111111111\n123", request.clipboardText)
        assertNull(intent.getStringExtra(CopyToPc.EXTRA_CVV))
    }

    @Test
    fun rejectedCallerStartsForegroundBeforeValidation() {
        val intent = Intent(CopyToPc.ACTION_SEND_FILE).apply {
            putExtra(CopyToPc.EXTRA_KIND, CopyToPc.KIND_FILE)
            putExtra(CopyToPc.EXTRA_CONTENT, "{}")
            putExtra(CopyToPc.EXTRA_NAME, FortressBotHandoff.RESPONSE_NAME)
        }
        val controller = Robolectric.buildService(CopyToPcService::class.java, intent).create()
        val service = controller.get()
        service.onStartCommand(intent, 0, 1)
        val shadow = Shadows.shadowOf(service)
        assertEquals(CopyToPcService.NOTICE_ID, shadow.lastForegroundNotificationId)
        assertNotNull(shadow.lastForegroundNotification)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val info = app.packageManager.getServiceInfo(
            ComponentName(app, CopyToPcService::class.java),
            PackageManager.GET_META_DATA
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, info.foregroundServiceType)
        service.stopSelf()
    }

    @Test
    fun aCallerThatIsNotFortressIsRefusedAndTheSecretIsStripped() {
        val activityIntent = Intent(CopyToPc.ACTION).apply {
            putExtra(CopyToPc.EXTRA_USERNAME, "alex")
            putExtra(CopyToPc.EXTRA_PASSWORD, "never-log-this-secret")
            putExtra(CopyToPc.EXTRA_TITLE, "Bank")
        }
        val activity = Robolectric.buildActivity(CopyToPcActivity::class.java, activityIntent).setup().get()
        assertNull(activity.intent.getStringExtra(CopyToPc.EXTRA_PASSWORD))
        val serviceIntent = Intent(CopyToPc.ACTION).apply {
            putExtra(CopyToPc.EXTRA_PASSWORD, "never-log-this-secret")
        }
        val service = Robolectric.buildService(CopyToPcService::class.java, serviceIntent).create().get()
        service.onStartCommand(serviceIntent, 0, 1)
        assertNull(serviceIntent.getStringExtra(CopyToPc.EXTRA_PASSWORD))
        activity.finish()
        service.stopSelf()
    }

    @Test
    fun theServiceAcceptsTheStellarVaultSourceWhenTheCallerUidIsHidden() {
        val request = CopyToPcWork.requestFrom(fortressLogin())
        assertTrue(CopyToPc.allow(null, request.source))
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(CopyToPc.PC_NOT_CONNECTED, CopyToPcWork.deliver(app, request, null).message)
    }

    @Test
    fun theExportedActionUsesTheFortressName() {
        assertEquals("org.stellartimelock.stellarsend.action.COPY_TO_PC", CopyToPc.ACTION)
        assertEquals("org.stellartimelock.stellarsend.action.SEND_FILE_TO_PC", CopyToPc.ACTION_SEND_FILE)
        assertTrue(CopyToPcActivity::class.java.name.endsWith("CopyToPcActivity"))
        assertTrue(CopyToPcService::class.java.name.endsWith("CopyToPcService"))
    }

    @Test
    fun aFortressFileKindCapturesTheUriAndWipesIt() {
        val uri = Uri.parse("content://com.stellartimelock.stellarvault.files/bot/fortress-bot-response.json")
        val intent = Intent(CopyToPc.ACTION_SEND_FILE).apply {
            putExtra(CopyToPc.EXTRA_KIND, CopyToPc.KIND_FILE)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(CopyToPc.EXTRA_FILENAME, FortressBotHandoff.RESPONSE_NAME)
            putExtra(CopyToPc.EXTRA_SOURCE, CopyToPc.SOURCE)
        }
        val request = CopyToPcWork.requestFrom(intent)
        assertTrue(request.fileKind)
        assertEquals(uri.toString(), request.uri)
        assertEquals(FortressBotHandoff.RESPONSE_NAME, request.filename)
        assertNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
        assertNull(intent.getStringExtra(CopyToPc.EXTRA_FILENAME))
        assertNull(intent.getStringExtra(CopyToPc.EXTRA_URI))
        assertNull(intent.data)
    }

    @Test
    fun aFortressFileKindReadsInlineContentAndWipesIt() {
        val payload = """{"v":1,"ct":"SECRET_PAYLOAD_XYZ"}"""
        val intent = Intent(CopyToPc.ACTION_SEND_FILE).apply {
            putExtra(CopyToPc.EXTRA_KIND, CopyToPc.KIND_FILE)
            putExtra(CopyToPc.EXTRA_NAME, FortressBotHandoff.RESPONSE_NAME)
            putExtra(CopyToPc.EXTRA_CONTENT, payload)
            putExtra(CopyToPc.EXTRA_SOURCE, CopyToPc.SOURCE)
        }
        val request = CopyToPcWork.requestFrom(intent)
        assertTrue(request.fileKind)
        assertEquals(FortressBotHandoff.RESPONSE_NAME, request.filename)
        assertEquals(payload, request.content)
        assertNull(request.clipboardText)
        assertNull(request.uri)
        assertNull(intent.getStringExtra(CopyToPc.EXTRA_CONTENT))
        assertNull(intent.getStringExtra(CopyToPc.EXTRA_NAME))
        assertFalse(FortressBotHandoff.containsPayload(CopyToPc.NOT_FORTRESS, payload))
    }

    @Test
    fun copyToPcFileKindAlsoReadsInlineContent() {
        val payload = """{"v":1,"ct":"SECRET_PAYLOAD_XYZ"}"""
        val intent = Intent(CopyToPc.ACTION).apply {
            putExtra(CopyToPc.EXTRA_KIND, CopyToPc.KIND_FILE)
            putExtra(CopyToPc.EXTRA_NAME, FortressBotHandoff.RESPONSE_NAME)
            putExtra(CopyToPc.EXTRA_CONTENT, payload)
            putExtra(CopyToPc.EXTRA_SOURCE, CopyToPc.SOURCE)
        }
        val request = CopyToPcWork.requestFrom(intent)
        assertTrue(request.fileKind)
        assertEquals(payload, request.content)
        assertEquals(FortressBotHandoff.RESPONSE_NAME, request.filename)
        assertNull(request.clipboardText)
        assertNull(intent.getStringExtra(CopyToPc.EXTRA_CONTENT))
    }

    @Test
    fun sendFileToPcWithoutKindIsStillAFileSend() {
        val uri = Uri.parse("content://com.stellartimelock.stellarvault.files/bot/fortress-bot-response.json")
        val intent = Intent(FortressBotHandoff.ACTION_SEND_FILE_TO_PC).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(CopyToPc.EXTRA_FILENAME, FortressBotHandoff.RESPONSE_NAME)
        }
        val request = CopyToPcWork.requestFrom(intent)
        assertTrue(request.fileKind)
        assertEquals(uri.toString(), request.uri)
    }

    @Test
    fun aFileKindFromACallerThatIsNotFortressIsRefused() {
        val request = CopyToPc.request(
            username = null,
            password = null,
            kind = CopyToPc.KIND_FILE,
            uri = "content://com.other.app/secret.json",
            filename = FortressBotHandoff.RESPONSE_NAME
        )
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(CopyToPc.NOT_FORTRESS, CopyToPcWork.deliver(app, request, "com.other.app").message)
        assertEquals(CopyToPc.NOT_FORTRESS, CopyToPcWork.deliver(app, request, null).message)
        assertFalse(FortressBotHandoff.allowFileCaller("com.other.app", signed = true))
    }

    @Test
    fun botHandoffIntentNamesFortressAndIncludesNameAndContent() {
        val payload = """{"v":1,"ct":"SECRET_PAYLOAD_XYZ"}"""
        val uri = Uri.parse("content://org.stellartimelock.stellarsend.stellarsend.files/handoff/fortress-bot-request.json")
        val intent = FortressBotDelivery.intent(uri, "fortress-bot-request.json", payload)
        assertEquals(FortressBotHandoff.ACTION_BOT_HANDOFF, intent.action)
        assertEquals(CopyToPc.FORTRESS_PACKAGE, intent.`package`)
        assertEquals(uri, intent.data)
        assertEquals(FortressBotHandoff.MIME_JSON, intent.type)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM))
        assertEquals("fortress-bot-request.json", intent.getStringExtra(FortressBotHandoff.EXTRA_NAME))
        assertEquals("fortress-bot-request.json", intent.getStringExtra(FortressBotHandoff.EXTRA_FILENAME))
        assertEquals(payload, intent.getStringExtra(FortressBotHandoff.EXTRA_CONTENT))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(FortressBotHandoff.logLine("Handed", "fortress-bot-request.json").contains("SECRET_PAYLOAD_XYZ"))
    }

    @Test
    fun exportedServiceAndActivityResolveSendFileToPc() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val send = Intent(FortressBotHandoff.ACTION_SEND_FILE_TO_PC).setPackage(app.packageName)
        val copy = Intent(CopyToPc.ACTION).setPackage(app.packageName)
        assertTrue(app.packageManager.queryIntentServices(send, 0).isNotEmpty())
        assertTrue(app.packageManager.queryIntentServices(copy, 0).isNotEmpty())
        val activitySend = Intent(FortressBotHandoff.ACTION_SEND_FILE_TO_PC).setPackage(app.packageName).addCategory(Intent.CATEGORY_DEFAULT)
        val activityCopy = Intent(CopyToPc.ACTION).setPackage(app.packageName).addCategory(Intent.CATEGORY_DEFAULT)
        assertTrue(app.packageManager.queryIntentActivities(activitySend, 0).isNotEmpty())
        assertTrue(app.packageManager.queryIntentActivities(activityCopy, 0).isNotEmpty())
    }

    private fun fortressLogin(): Intent = Intent(CopyToPc.ACTION).apply {
        setPackage(CopyToPc.FLIP_SEND_PACKAGE)
        addCategory(Intent.CATEGORY_DEFAULT)
        putExtra(CopyToPc.EXTRA_USERNAME, "alex")
        putExtra(CopyToPc.EXTRA_PASSWORD, "hunter2")
        putExtra(CopyToPc.EXTRA_TITLE, "Bank")
        putExtra(CopyToPc.EXTRA_CLEAR_AFTER_SECONDS, 30)
        putExtra(CopyToPc.EXTRA_SOURCE, CopyToPc.SOURCE)
        putExtra(CopyToPc.EXTRA_KIND, CopyToPc.KIND_LOGIN)
    }
}
