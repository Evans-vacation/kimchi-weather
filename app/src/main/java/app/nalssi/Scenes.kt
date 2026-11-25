package app.nalssi

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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

/**
 * "내 사진" widget backgrounds: the chosen gallery photo is shrunk (long side ≤ 1280 px),
 * turned upright and stored privately as photos/photo_<widgetId>.jpg, then served by ScenesProvider.
 */
object Photos {
    private val NAME = Regex("photo_\\d+\\.jpg")

    private fun dir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }

    fun file(ctx: Context, id: Int) = File(dir(ctx), "photo_$id.jpg")

    fun fileByName(ctx: Context, name: String): File? =
        if (NAME.matches(name)) File(dir(ctx), name).takeIf { it.exists() } else null

    fun has(ctx: Context, id: Int) = file(ctx, id).let { it.exists() && it.length() > 0 }

    /** content:// address; the ?v= part changes when the photo changes so the launcher reloads it. */
    fun uri(ctx: Context, id: Int): Uri? {
        val f = file(ctx, id)
        if (!f.exists()) return null
        return Uri.parse("content://${Scenes.AUTHORITY}/${f.name}?v=${f.lastModified()}")
    }

    fun delete(ctx: Context, id: Int) {
        file(ctx, id).delete()
    }

    /** Blocking: copies [src] (a picked image) into the widget's photo file. */
    fun save(ctx: Context, id: Int, src: Uri): Boolean {
        return try {
            val cr = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return false
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
            val bmp = cr.openInputStream(src)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return false
            val rotation = try {
                cr.openInputStream(src)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (e: Exception) {
                0f
            }
            val long = maxOf(bmp.width, bmp.height)
            val scale = if (long > 1280) 1280f / long else 1f
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val f = file(ctx, id)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.renameTo(f)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/** Read-only access to the unpacked widget pictures for the launcher. */
class ScenesProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val f = Photos.fileByName(ctx, name) ?: Scenes.file(ctx, name) ?: throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment?.endsWith(".jpg") == true) "image/jpeg" else "image/webp"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
