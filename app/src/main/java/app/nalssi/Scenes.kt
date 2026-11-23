package app.nalssi

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipInputStream

/**
 * Widget landscape pictures (126 = 9 regions × 7 weathers × day/night) live in ONE file,
 * assets/scenes.zip, so the project stays under GitHub's 100-files-per-upload limit.
 * A picture is unpacked to app storage the first time it is needed and handed to the
 * home screen through ScenesProvider (content://app.nalssi.scenes/<name>.webp).
 */
object Scenes {
    const val AUTHORITY = "app.nalssi.scenes"
    private val NAME = Regex("scene_[a-z]+_[a-z]+_(day|night)\\.webp")

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "scenes_${BuildConfig.VERSION_CODE}")
        if (!d.exists()) {
            // pictures from an older app version may differ: clear them out
            ctx.filesDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("scenes_") }?.forEach { it.deleteRecursively() }
            d.mkdirs()
        }
        return d
    }

    /** The unpacked file for [name], or null if the archive has no such picture. */
    @Synchronized
    fun file(ctx: Context, name: String): File? {
        if (!NAME.matches(name)) return null
        val f = File(dir(ctx), name)
        if (f.exists() && f.length() > 0) return f
        try {
            ZipInputStream(ctx.assets.open("scenes.zip")).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name == name) {
                        val tmp = File(f.parentFile, "$name.tmp")
                        tmp.outputStream().use { zip.copyTo(it) }
                        tmp.renameTo(f)
                        return f
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    /** content:// address of the picture for this weather, falling back to the mountain-lake landscape. */
    fun uri(ctx: Context, theme: String, cat: String, day: Boolean): Uri {
        val dn = if (day) "day" else "night"
        val name = listOf("scene_${theme}_${cat}_$dn.webp", "scene_nature_${cat}_$dn.webp", "scene_nature_clear_day.webp")
            .firstOrNull { file(ctx, it) != null } ?: "scene_nature_clear_day.webp"
        return Uri.parse("content://$AUTHORITY/$name")
    }
}

/** Read-only access to the unpacked widget pictures for the launcher. */
class ScenesProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val f = Scenes.file(ctx, name) ?: throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/webp"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
