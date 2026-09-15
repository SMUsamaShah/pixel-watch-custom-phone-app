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
                text = "Pixel Data Vault reads your heart-rate records only after you grant access. " +
                    "It keeps the raw timestamps and BPM samples intact and does not upload them. " +
                    "Exports happen only when you choose a destination."
                setPadding(48, 64, 48, 64)
            }
        )
    }
}
