package com.example.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

object StorageUtils {

    fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(sizeBytes.toDouble()) / log10(1024.0)).toInt()
        val df = DecimalFormat("#,##0.#")
        return "${df.format(sizeBytes / 1024.0.pow(digitGroups.toDouble()))} ${units[digitGroups]}"
    }

    fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            result = cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "archive_file"
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            return cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return try {
            val file = File(uri.path ?: "")
            if (file.exists()) file.length() else 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Copies a Uri content to a temporary cache file so underlying Java C/C++ libraries (Zip4j / Junrar)
     * can perform random access reads on standard File paths without loading everything into heap memory.
     */
    fun createTempFileFromUri(context: Context, uri: Uri, prefix: String = "temp_archive"): File {
        val fileName = getFileName(context, uri)
        val ext = if (fileName.contains(".")) fileName.substring(fileName.lastIndexOf(".")) else ".tmp"
        val tempFile = File.createTempFile(prefix, ext, context.cacheDir)
        tempFile.deleteOnExit()

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return tempFile
    }

    /**
     * Checks if a filename matches multi-part RAR patterns:
     * - name.part1.rar, name.part01.rar, name.part001.rar
     * - name.r00, name.r01
     */
    fun parseMultiPartRarInfo(fileName: String): Pair<Boolean, Int?> {
        val lower = fileName.lowercase()
        val partMatch = Regex("""\.part(\d+)\.rar$""").find(lower)
        if (partMatch != null) {
            val partNum = partMatch.groupValues[1].toIntOrNull()
            return Pair(true, partNum)
        }
        val rMatch = Regex("""\.r(\d{2})$""").find(lower)
        if (rMatch != null) {
            val partNum = rMatch.groupValues[1].toIntOrNull()
            return Pair(true, partNum)
        }
        return Pair(false, null)
    }

    fun clearTempCache(context: Context) {
        try {
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("temp_") || file.name.endsWith(".tmp")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
