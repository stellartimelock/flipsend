package org.stellartimelock.stellarsend

import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class StellarTimeLockPromoTest {
    @Test
    fun homeShowsSiteAndPlayPills() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val home = activity.findViewById<View>(R.id.transferPage)
        val site = activity.findViewById<View>(R.id.stlPromoSite)
        val play = activity.findViewById<View>(R.id.stlPromo)
        assertEquals(View.VISIBLE, home.visibility)
        assertEquals(View.VISIBLE, site.visibility)
        assertEquals(View.VISIBLE, play.visibility)
        assertTrue(isChildOf(home, site))
        assertTrue(isChildOf(home, play))

        assertEquals("StellarTimeLock", activity.findViewById<TextView>(R.id.stlPromoTitle).text.toString())
        assertEquals("Play Store", activity.findViewById<TextView>(R.id.stlPromoOpen).text.toString())
        assertEquals("StellarTimeLock", site.contentDescription)
        assertEquals("Play Store", play.contentDescription)

        val content = activity.findViewById<View>(android.R.id.content)
        val metrics = activity.resources.displayMetrics
        content.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
        )
        content.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        val minHeight = (48 * metrics.density).toInt() - 1
        assertTrue("site pill ${site.height} < 48dp", site.height >= minHeight)
        assertTrue("play pill ${play.height} < 48dp", play.height >= minHeight)

        val min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, activity.resources.displayMetrics) - 1
        assertTrue(activity.findViewById<TextView>(R.id.stlPromoTitle).textSize >= min)
        assertTrue(activity.findViewById<TextView>(R.id.stlPromoOpen).textSize >= min)

        site.performClick()
        val siteNext = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, siteNext.action)
        assertEquals(StellarTimeLockPromo.SITE, siteNext.dataString)

        play.performClick()
        val playNext = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, playNext.action)
        assertEquals(StellarTimeLockPromo.PLAY, playNext.dataString)
    }

    private fun isChildOf(parent: View, child: View): Boolean {
        var walk: android.view.ViewParent? = child.parent
        while (walk != null) {
            if (walk === parent) return true
            walk = walk.parent
        }
        return false
    }
}
