package com.swarmos.app.hands.android

import android.content.Context
import com.swarmos.app.hands.PhoneDriver

/** The drivers this package offers. The core asks each for ready(); an accessibility driver exists even while the service is off. */
object HandsDrivers {
    private var accessibility: AccessibilityDriver? = null

    @Synchronized
    fun list(ctx: Context): List<PhoneDriver> {
        val driver = accessibility ?: AccessibilityDriver(ctx).also { accessibility = it }
        return listOf(driver)
    }

    /** True while the owner has the accessibility service switched on and it is connected. */
    fun accessibilityOn(): Boolean = HandsAccessibilityService.instance != null
}
