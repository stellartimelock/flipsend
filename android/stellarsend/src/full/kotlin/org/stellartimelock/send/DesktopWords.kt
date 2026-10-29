package org.stellartimelock.send

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import org.stellartimelock.send.core.RemotePhrases
import org.stellartimelock.send.core.RemoteWords

/**
 * Sideload and Play beta copy. This class is not in the production Play source set.
 */
class DesktopWords : ContentProvider() {
    override fun onCreate(): Boolean {
        install()
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        @JvmStatic
        fun install() {
            RemotePhrases.install(words)
        }

        private val words = RemoteWords(
            label = "Remote desktop",
            betaLabel = "Remote desktop (beta)",
            settingsLead = "Remote desktop, TV, folders, and received files are here.",
            needPair = "Pair a computer before remote desktop.",
            allowed = "Remote desktop is on. Tap Disconnect to end it.",
            denied = "Remote desktop was not allowed.",
            stopped = "Remote desktop stopped.",
            activeLine = "Remote desktop active - Disconnect",
            updatePhone = "Update the FlipSend app on this phone, then tap Remote desktop again.",
            accessibilityHint = "Phone control is off. Open phone control settings when you want the computer to tap this phone. Remote desktop does not need it.",
            accessibilityDescription = "FlipSend uses this so your paired computer can tap, swipe, and type on this phone after you tap Allow. Remote desktop does not use this.",
            iosLimit = "Remote desktop is not available on iPhone. You can still send files and watch a shared screen.",
            ready = "Remote desktop is ready.",
            failed = "Remote desktop didn't start. Tap it again.",
            checklistDetail = "This lets the computer tap and type on this phone. Remote desktop does not use it.",
            failedFallback = "Remote desktop failed.",
            allowTitle = "Allow remote desktop?",
            linkNeedle = "remote desktop"
        )
    }
}
