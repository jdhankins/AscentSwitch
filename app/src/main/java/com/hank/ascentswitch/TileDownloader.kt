package com.hank.ascentswitch

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

@Suppress("unused")
object TileDownloader {
    private const val TAG = "TileDownloader"
    private const val BASE_URL = "https://github.com/jdhankins/AscentSwitch/releases/download/v1.0.0-topo-tiles/"

    private val activeDownloads = mutableSetOf<String>()

    @Suppress("unused")
    fun downloadTile(filename: String, targetDir: File): Boolean {
        synchronized(activeDownloads) {
            if (activeDownloads.contains(filename)) {
                AppLogger.d(TAG, "Tile $filename is already downloading...")
                return false
            }
            activeDownloads.add(filename)
        }

        try {
            val targetFile = File(targetDir, filename)
            if (targetFile.exists() && (targetFile.length() > 0)) {
                AppLogger.d(TAG, "Tile $filename already exists locally (${targetFile.length()} bytes)")
                return true
            }

            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val candidateUrls = listOf(
                "$BASE_URL$filename",
                "$BASE_URL$filename.zip",
                "$BASE_URL${filename.substringBefore(".")}.zip",
            )

            for (downloadUrl in candidateUrls) {
                AppLogger.d(TAG, "Attempting tile download from: $downloadUrl")
                val isZip = downloadUrl.endsWith(".zip", ignoreCase = true)
                val tempFile = File(targetDir, if (isZip) "$filename.zip.tmp" else "$filename.tmp")

                try {
                    val connection = openConnectionWithRedirects(downloadUrl)
                    if (connection == null) {
                        AppLogger.w(TAG, "Connection failed for $downloadUrl (null or max redirects)")
                        continue
                    }

                    val responseCode = connection.responseCode
                    AppLogger.d(TAG, "HTTP response code for $downloadUrl: $responseCode")

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        connection.inputStream.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        if (tempFile.exists() && (tempFile.length() > 0)) {
                            if (isZip) {
                                var extracted = false
                                ZipInputStream(tempFile.inputStream()).use { zipInput ->
                                    var entry = zipInput.nextEntry
                                    while (entry != null) {
                                        if (entry.name.endsWith(".hgt", ignoreCase = true) || entry.name.equals(filename, ignoreCase = true)) {
                                            FileOutputStream(targetFile).use { out ->
                                                zipInput.copyTo(out)
                                            }
                                            extracted = true
                                            break
                                        }
                                        entry = zipInput.nextEntry
                                    }
                                }
                                tempFile.delete()
                                if (extracted && targetFile.exists() && (targetFile.length() > 0)) {
                                    AppLogger.d(TAG, "Successfully extracted $filename (${targetFile.length()} bytes)")
                                    return true
                                }
                            } else {
                                if (targetFile.exists()) {
                                    targetFile.delete()
                                }
                                if (tempFile.renameTo(targetFile)) {
                                    AppLogger.d(TAG, "Successfully downloaded $filename (${targetFile.length()} bytes)")
                                    return true
                                } else {
                                    AppLogger.e(TAG, "Failed to rename temp file to $targetFile")
                                }
                            }
                        }
                    } else {
                        AppLogger.w(TAG, "Server returned HTTP $responseCode for $downloadUrl")
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Exception downloading from $downloadUrl: ${e.message}", e)
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
            }
            return false
        } finally {
            synchronized(activeDownloads) {
                activeDownloads.remove(filename)
            }
        }
    }

    private fun openConnectionWithRedirects(initialUrl: String): HttpURLConnection? {
        var currentUrl = initialUrl
        var redirects = 0
        val maxRedirects = 5

        while (redirects < maxRedirects) {
            val url = URL(currentUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AscentSwitch-Karoo/1.0")
            }

            val code = connection.responseCode
            AppLogger.d(TAG, "Connection step $redirects: URL=$currentUrl, ResponseCode=$code")

            if ((code == HttpURLConnection.HTTP_MOVED_TEMP) ||
                (code == HttpURLConnection.HTTP_MOVED_PERM) ||
                (code == HttpURLConnection.HTTP_SEE_OTHER) ||
                (code == 307)
            ) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (!newUrl.isNullOrBlank()) {
                    AppLogger.d(TAG, "Redirecting to: $newUrl")
                    currentUrl = newUrl
                    redirects++
                    continue
                }
            }
            return connection
        }
        return null
    }
}
