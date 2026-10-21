package dev.tvlauncher.wallpaper

import java.io.File

/**
 * The photos shown as the wallpaper: whatever `scripts/wallpapers.sh` pushed into the
 * launcher's own folder on the TV (Android/data/<package>/files/wallpapers), read as-is.
 * No permissions are needed for an app's own folder, and adb can write to it.
 */
class WallpaperFolder(private val dir: File?) {
    /** The images there now, in name order; empty if the folder is missing. */
    fun list(): List<File> =
        dir?.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_TYPES }?.sortedBy { it.name }.orEmpty()

    fun find(name: String?): File? = name?.let { n -> dir?.let { File(it, n) } }?.takeIf { it.isFile }

    companion object {
        val IMAGE_TYPES = setOf("jpg", "jpeg", "png", "webp")
    }
}
