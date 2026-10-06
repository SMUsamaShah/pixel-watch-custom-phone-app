package com.usamashah.pixelvault

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** The privacy explanation shown from the system Health Connect permission UI. */
class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "Pixel Data Vault reads only the Health Connect types you allow, including " +
                    "historical and background data if you grant those permissions. Medical records are optional. " +
                    "It preserves individual measurements, timestamps, stages, units and source metadata. " +
                    "If you connect Google Health, it also reads your account through Google's official API. " +
                    "It saves ZIP files only to a destination you choose; daily updates require you to enable the switch. " +
                    "There is no developer server, analytics or advertising."
                setPadding(48, 64, 48, 64)
            }
        )
    }
}
