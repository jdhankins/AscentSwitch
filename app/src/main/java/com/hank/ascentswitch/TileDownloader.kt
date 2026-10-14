package com.hank.ascentswitch

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Suppress("unused")
object TileDownloader {
    private const val TAG = "TileDownloader"
    private const val BASE_URL = "https://huggingface.co/buckets/links-ads/fabdem/resolve/main/tiles/"

    private val activeDownloads = mutableSetOf<String>()

    @Suppress("unused")
    fun downloadTile(filename: String, targetDir: File): Boolean {
        synchronized(activeDownloads) {
            if (activeDownloads.contains(filename)) {
                Log.d(TAG, "Tile $filename is already downloading...")
                return false
            }
            activeDownloads.add(filename)
        }

        try {
            val targetFile = File(targetDir, filename)
            if (targetFile.exists() && (targetFile.length() > 0)) {
                Log.d(TAG, "Tile $filename already exists locally (${targetFile.length()} bytes)")
                return true
            }

            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val tempFile = File(targetDir, "$filename.tmp")
            val downloadUrl = "$BASE_URL$filename"

            Log.d(TAG, "Starting automatic download of $filename from $downloadUrl...")
            val url = URL(downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AscentSwitch-Karoo/1.0")
            }

            val responseCode = connection.responseCode
            if ((responseCode == HttpURLConnection.HTTP_OK) || (responseCode == HttpURLConnection.HTTP_MOVED_TEMP) || (responseCode == HttpURLConnection.HTTP_MOVED_PERM)) {
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (tempFile.exists() && (tempFile.length() > 0)) {
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    if (tempFile.renameTo(targetFile)) {
                        Log.d(TAG, "Successfully downloaded and saved $filename (${targetFile.length()} bytes)")
                        return true
                    } else {
                        Log.e(TAG, "Failed to rename $tempFile to $targetFile")
                    }
                }
            } else {
                Log.w(TAG, "Server returned HTTP $responseCode for tile $filename")
            }
            tempFile.delete()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading tile $filename: ${e.message}", e)
            return false
        } finally {
            synchronized(activeDownloads) {
                activeDownloads.remove(filename)
            }
        }
    }
}
