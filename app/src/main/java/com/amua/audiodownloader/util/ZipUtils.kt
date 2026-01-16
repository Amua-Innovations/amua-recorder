package com.amua.audiodownloader.util

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Utility for creating zip files from directories.
 */
object ZipUtils {

    private const val TAG = "ZipUtils"
    private const val BUFFER_SIZE = 8192

    /**
     * Create a zip file from a directory.
     *
     * @param sourceDirectory The directory to zip
     * @param outputFile The output zip file
     * @param fileFilter Optional filter to include only certain files
     * @return true if successful, false otherwise
     */
    fun zipDirectory(
        sourceDirectory: File,
        outputFile: File,
        fileFilter: ((File) -> Boolean)? = null
    ): Boolean {
        if (!sourceDirectory.exists() || !sourceDirectory.isDirectory) {
            Log.e(TAG, "Source is not a valid directory: ${sourceDirectory.absolutePath}")
            return false
        }

        val filesToZip = sourceDirectory.listFiles()?.filter { file ->
            fileFilter?.invoke(file) ?: true
        } ?: emptyList()

        if (filesToZip.isEmpty()) {
            Log.w(TAG, "No files to zip in ${sourceDirectory.absolutePath}")
            return false
        }

        return try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zipOut ->
                for (file in filesToZip) {
                    addFileToZip(file, file.name, zipOut)
                }
            }
            Log.i(TAG, "Created zip file: ${outputFile.absolutePath} with ${filesToZip.size} files")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create zip file", e)
            outputFile.delete() // Clean up partial file
            false
        }
    }

    /**
     * Add a file or directory to a zip output stream.
     */
    private fun addFileToZip(file: File, entryName: String, zipOut: ZipOutputStream) {
        if (file.isDirectory) {
            val children = file.listFiles() ?: return
            for (child in children) {
                addFileToZip(child, "$entryName/${child.name}", zipOut)
            }
        } else {
            BufferedInputStream(FileInputStream(file), BUFFER_SIZE).use { input ->
                val entry = ZipEntry(entryName)
                entry.time = file.lastModified()
                zipOut.putNextEntry(entry)

                val buffer = ByteArray(BUFFER_SIZE)
                var len: Int
                while (input.read(buffer).also { len = it } > 0) {
                    zipOut.write(buffer, 0, len)
                }
                zipOut.closeEntry()
            }
        }
    }

    /**
     * Get the size of a zip file in a formatted string.
     */
    fun getFormattedFileSize(file: File): String {
        val bytes = file.length()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
