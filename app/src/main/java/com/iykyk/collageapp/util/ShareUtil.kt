package com.iykyk.collageapp.util

import android.content.Context
import android.content.Intent
import android.net.Uri

object ShareUtil {

    /** Shares an already-saved image (e.g. the Uri returned by MediaStoreSaver) via the system share sheet. */
    fun shareImage(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share collage").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
