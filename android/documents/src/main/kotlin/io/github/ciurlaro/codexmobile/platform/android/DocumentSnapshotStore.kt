package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import java.io.File

internal class DocumentSnapshotStore(context: Context) {
    val directory = File(context.noBackupFilesDir, "document-snapshots").apply {
        check(isDirectory || mkdirs()) { "Unable to prepare document snapshot storage" }
    }
}
