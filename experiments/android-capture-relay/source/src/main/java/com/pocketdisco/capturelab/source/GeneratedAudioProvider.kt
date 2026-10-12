package com.pocketdisco.capturelab.source

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class GeneratedAudioProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun sample(uri: Uri): File {
        if (uri != URI && uri != MP3_URI) throw FileNotFoundException("Unknown sample")
        synchronized(GeneratedAudioProvider::class.java) {
            val app = requireNotNull(context)
            val name = if (uri == MP3_URI) "generated-pulse.mp3" else "pocketdisco-generated-tone.wav"
            val file = File(app.cacheDir, name)
            if (!file.isFile) {
                val bytes = if (uri == MP3_URI) app.assets.open(name).use { it.readBytes() } else GeneratedTone.wav(60)
                file.writeBytes(bytes)
            }
            return file
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("The sample is read-only")
        return ParcelFileDescriptor.open(sample(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        return when (uri) {
            URI -> "audio/wav"
            MP3_URI -> "audio/mpeg"
            else -> throw FileNotFoundException("Unknown sample")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = sample(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only sample")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only sample")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only sample")

    companion object {
        val URI: Uri = Uri.parse("content://com.pocketdisco.capturelab.source.sample/generated-tone.wav")
        val MP3_URI: Uri = Uri.parse("content://com.pocketdisco.capturelab.source.sample/generated-pulse.mp3")
    }
}
