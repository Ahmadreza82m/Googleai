package com.example.engine

import android.content.Context
import android.net.Uri
import com.example.util.StorageUtils
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ArchiveEntryItem(
    val name: String,
    val uncompressedSize: Long,
    val compressedSize: Long,
    val isDirectory: Boolean,
    val isEncrypted: Boolean
)

data class ArchiveMetadata(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long,
    val fileSizeFormatted: String,
    val format: String, // "ZIP" or "RAR"
    val entryCount: Int,
    val isEncrypted: Boolean,
    val isMultiPart: Boolean = false,
    val partNumber: Int? = null,
    val missingPartName: String? = null,
    val entries: List<ArchiveEntryItem> = emptyList()
)

data class ProgressState(
    val currentFileName: String,
    val processedCount: Int,
    val totalCount: Int,
    val percentage: Int
)

sealed class ArchiveException(message: String) : Exception(message) {
    class InvalidPassword : ArchiveException("PASSWORD_INCORRECT")
    class CorruptedFile : ArchiveException("FILE_CORRUPTED")
    class MissingPart(val partName: String) : ArchiveException("MISSING_PART")
    class UnsupportedFormat : ArchiveException("UNSUPPORTED_FORMAT")
    class Cancelled : ArchiveException("CANCELLED")
}

object ArchiveEngine {

    /**
     * Inspects a ZIP or RAR archive and gathers metadata and file tree list.
     */
    suspend fun inspectArchive(context: Context, uri: Uri): ArchiveMetadata = withContext(Dispatchers.IO) {
        val fileName = StorageUtils.getFileName(context, uri)
        val fileSize = StorageUtils.getFileSize(context, uri)
        val formattedSize = StorageUtils.formatFileSize(fileSize)
        val lowerName = fileName.lowercase()

        val isRar = lowerName.endsWith(".rar") || lowerName.matches(Regex(""".*\.r\d{2}$"""))
        val format = if (isRar) "RAR" else "ZIP"

        if (isRar) {
            inspectRarArchive(context, uri, fileName, fileSize, formattedSize)
        } else {
            inspectZipArchive(context, uri, fileName, fileSize, formattedSize)
        }
    }

    private fun inspectZipArchive(
        context: Context,
        uri: Uri,
        fileName: String,
        fileSize: Long,
        formattedSize: String
    ): ArchiveMetadata {
        val entries = mutableListOf<ArchiveEntryItem>()
        var isEncrypted = false

        // First attempt with Zip4j using temp file
        val tempFile = StorageUtils.createTempFileFromUri(context, uri, "inspect_zip")
        try {
            val zipFile = ZipFile(tempFile)
            if (zipFile.isValidZipFile) {
                isEncrypted = zipFile.isEncrypted
                val headerList = zipFile.fileHeaders
                for (header in headerList) {
                    if (header.isEncrypted) isEncrypted = true
                    entries.add(
                        ArchiveEntryItem(
                            name = header.fileName,
                            uncompressedSize = header.uncompressedSize,
                            compressedSize = header.compressedSize,
                            isDirectory = header.isDirectory,
                            isEncrypted = header.isEncrypted
                        )
                    )
                }
                return ArchiveMetadata(
                    uri = uri,
                    fileName = fileName,
                    fileSize = fileSize,
                    fileSizeFormatted = formattedSize,
                    format = "ZIP",
                    entryCount = entries.size,
                    isEncrypted = isEncrypted,
                    entries = entries
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            tempFile.delete()
        }

        // Fallback using standard ZipInputStream
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry: ZipEntry? = zipStream.nextEntry
                    while (entry != null) {
                        entries.add(
                            ArchiveEntryItem(
                                name = entry.name,
                                uncompressedSize = if (entry.size >= 0) entry.size else 0L,
                                compressedSize = if (entry.compressedSize >= 0) entry.compressedSize else 0L,
                                isDirectory = entry.isDirectory,
                                isEncrypted = false
                            )
                        )
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return ArchiveMetadata(
            uri = uri,
            fileName = fileName,
            fileSize = fileSize,
            fileSizeFormatted = formattedSize,
            format = "ZIP",
            entryCount = entries.size,
            isEncrypted = isEncrypted,
            entries = entries
        )
    }

    private fun inspectRarArchive(
        context: Context,
        uri: Uri,
        fileName: String,
        fileSize: Long,
        formattedSize: String
    ): ArchiveMetadata {
        val (isMultiPart, partNum) = StorageUtils.parseMultiPartRarInfo(fileName)
        val entries = mutableListOf<ArchiveEntryItem>()
        var isEncrypted = false

        val tempFile = StorageUtils.createTempFileFromUri(context, uri, "inspect_rar")
        try {
            Archive(tempFile).use { archive ->
                if (archive.isEncrypted) {
                    isEncrypted = true
                }
                var fileHeader: FileHeader? = archive.nextFileHeader()
                while (fileHeader != null) {
                    if (fileHeader.isEncrypted) isEncrypted = true
                    entries.add(
                        ArchiveEntryItem(
                            name = fileHeader.fileNameString ?: "unnamed",
                            uncompressedSize = fileHeader.unpSize,
                            compressedSize = fileHeader.packSize,
                            isDirectory = fileHeader.isDirectory,
                            isEncrypted = fileHeader.isEncrypted
                        )
                    )
                    fileHeader = archive.nextFileHeader()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            tempFile.delete()
        }

        return ArchiveMetadata(
            uri = uri,
            fileName = fileName,
            fileSize = fileSize,
            fileSizeFormatted = formattedSize,
            format = "RAR",
            entryCount = entries.size,
            isEncrypted = isEncrypted,
            isMultiPart = isMultiPart,
            partNumber = partNum,
            entries = entries
        )
    }

    /**
     * Extracts a ZIP archive to a target output directory.
     */
    suspend fun extractZip(
        context: Context,
        zipUri: Uri,
        outputDir: File,
        password: String? = null,
        onProgress: (ProgressState) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val tempZipFile = StorageUtils.createTempFileFromUri(context, zipUri, "extract_zip")
        var processed = 0

        try {
            val zipFile = if (!password.isNullOrEmpty()) {
                ZipFile(tempZipFile, password.toCharArray())
            } else {
                ZipFile(tempZipFile)
            }

            if (!zipFile.isValidZipFile) {
                throw ArchiveException.CorruptedFile()
            }

            if (zipFile.isEncrypted && password.isNullOrEmpty()) {
                throw ArchiveException.InvalidPassword()
            }

            val headers = zipFile.fileHeaders
            val total = headers.size

            if (!outputDir.exists()) outputDir.mkdirs()

            for (header in headers) {
                if (!isActive) throw ArchiveException.Cancelled()

                onProgress(
                    ProgressState(
                        currentFileName = header.fileName,
                        processedCount = processed,
                        totalCount = total,
                        percentage = if (total > 0) ((processed.toFloat() / total) * 100).toInt() else 0
                    )
                )

                try {
                    zipFile.extractFile(header, outputDir.absolutePath)
                } catch (e: Exception) {
                    val msg = e.message?.lowercase() ?: ""
                    if (msg.contains("wrong password") || msg.contains("password")) {
                        throw ArchiveException.InvalidPassword()
                    } else {
                        throw ArchiveException.CorruptedFile()
                    }
                }

                processed++
            }

            onProgress(
                ProgressState(
                    currentFileName = "Complete",
                    processedCount = total,
                    totalCount = total,
                    percentage = 100
                )
            )

            return@withContext total
        } finally {
            tempZipFile.delete()
        }
    }

    /**
     * Extracts a RAR archive to a target output directory.
     */
    suspend fun extractRar(
        context: Context,
        rarUri: Uri,
        outputDir: File,
        password: String? = null,
        onProgress: (ProgressState) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val tempRarFile = StorageUtils.createTempFileFromUri(context, rarUri, "extract_rar")
        var processed = 0

        try {
            val archive = Archive(tempRarFile, password)
            if (archive.isEncrypted && password.isNullOrEmpty()) {
                archive.close()
                throw ArchiveException.InvalidPassword()
            }

            val headers = mutableListOf<FileHeader>()
            var fh = archive.nextFileHeader()
            while (fh != null) {
                headers.add(fh)
                fh = archive.nextFileHeader()
            }

            val total = headers.size
            if (!outputDir.exists()) outputDir.mkdirs()

            archive.close()

            // Re-open for extraction
            Archive(tempRarFile, password).use { rarArchive ->
                var fileHeader = rarArchive.nextFileHeader()
                while (fileHeader != null) {
                    if (!isActive) throw ArchiveException.Cancelled()

                    val entryName = fileHeader.fileNameString ?: "file_$processed"
                    onProgress(
                        ProgressState(
                            currentFileName = entryName,
                            processedCount = processed,
                            totalCount = total,
                            percentage = if (total > 0) ((processed.toFloat() / total) * 100).toInt() else 0
                        )
                    )

                    val outFile = File(outputDir, entryName)
                    if (fileHeader.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            rarArchive.extractFile(fileHeader, fos)
                        }
                    }

                    processed++
                    fileHeader = rarArchive.nextFileHeader()
                }
            }

            onProgress(
                ProgressState(
                    currentFileName = "Complete",
                    processedCount = total,
                    totalCount = total,
                    percentage = 100
                )
            )

            return@withContext processed
        } catch (e: ArchiveException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("password") || msg.contains("header encrypted")) {
                throw ArchiveException.InvalidPassword()
            } else {
                throw ArchiveException.CorruptedFile()
            }
        } finally {
            tempRarFile.delete()
        }
    }

    /**
     * Creates a ZIP archive from a list of source files or URIs with optional AES-256 password protection.
     */
    suspend fun createZip(
        context: Context,
        sourceUris: List<Uri>,
        outputZipFile: File,
        password: String? = null,
        onProgress: (ProgressState) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val total = sourceUris.size
        if (total == 0) return@withContext false

        if (outputZipFile.exists()) outputZipFile.delete()

        val zipFile = if (!password.isNullOrEmpty()) {
            ZipFile(outputZipFile, password.toCharArray())
        } else {
            ZipFile(outputZipFile)
        }

        val zipParameters = ZipParameters()
        if (!password.isNullOrEmpty()) {
            zipParameters.isEncryptFiles = true
            zipParameters.encryptionMethod = EncryptionMethod.AES
            zipParameters.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
        }

        var processed = 0
        val tempFilesToClean = mutableListOf<File>()

        try {
            for (uri in sourceUris) {
                if (!isActive) throw ArchiveException.Cancelled()

                val fileName = StorageUtils.getFileName(context, uri)
                onProgress(
                    ProgressState(
                        currentFileName = fileName,
                        processedCount = processed,
                        totalCount = total,
                        percentage = if (total > 0) ((processed.toFloat() / total) * 100).toInt() else 0
                    )
                )

                val tempFile = StorageUtils.createTempFileFromUri(context, uri, "zip_input")
                tempFilesToClean.add(tempFile)

                zipParameters.fileNameInZip = fileName
                zipFile.addFile(tempFile, zipParameters)

                processed++
            }

            onProgress(
                ProgressState(
                    currentFileName = "Complete",
                    processedCount = total,
                    totalCount = total,
                    percentage = 100
                )
            )

            return@withContext true
        } finally {
            tempFilesToClean.forEach { it.delete() }
        }
    }
}
