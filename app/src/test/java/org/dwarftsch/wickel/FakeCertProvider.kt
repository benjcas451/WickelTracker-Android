package org.dwarftsch.wickel

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream

/**
 * Minimaler SAF-Provider für die Tests: ein Ordner "certs" mit client.crt und
 * client.key, damit [org.dwarftsch.wickel.data.CertSource] echte Bytes liest.
 */
class FakeCertProvider : ContentProvider() {

    private lateinit var ordner: File

    override fun onCreate(): Boolean {
        ordner = File(context!!.cacheDir, "certs").apply { mkdirs() }
        File(ordner, "client.crt").writeText("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n")
        File(ordner, "client.key").writeText("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val spalten = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = MatrixCursor(spalten)
        val kinder = uri.pathSegments.size >= 4 && uri.pathSegments[2] == "document" &&
            uri.pathSegments.getOrNull(3) != null && uri.path!!.endsWith("/children")
        val dokumente = if (kinder) listOf("certs/client.crt", "certs/client.key") else listOf(dokumentId(uri))
        for (id in dokumente) {
            cursor.addRow(
                spalten.map<String, Any?> { spalte ->
                    when (spalte) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> id.substringAfterLast('/')
                        DocumentsContract.Document.COLUMN_MIME_TYPE ->
                            if (id.contains('.')) "application/octet-stream" else DocumentsContract.Document.MIME_TYPE_DIR
                        DocumentsContract.Document.COLUMN_SIZE -> 64L
                        DocumentsContract.Document.COLUMN_FLAGS -> 0
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0L
                        else -> null
                    }
                }.toTypedArray(),
            )
        }
        return cursor
    }

    private fun dokumentId(uri: Uri): String =
        uri.pathSegments.getOrNull(3) ?: uri.pathSegments.getOrNull(1) ?: "certs"

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val datei = File(ordner, dokumentId(uri).substringAfterLast('/'))
        if (!datei.exists()) FileOutputStream(datei).use { it.write(ByteArray(0)) }
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(datei, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
