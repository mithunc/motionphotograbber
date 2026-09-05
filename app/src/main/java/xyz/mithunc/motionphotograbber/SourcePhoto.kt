/*
 * Copyright 2026 Mithun Chaubey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.mithunc.motionphotograbber

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * A shared or picked motion photo, copied somewhere the core modules can reach it.
 *
 * All three core modules take a [File]: the parser wants random access, Media3 wants a
 * file descriptor it can subrange, and `ExifInterface` wants a path it can rewrite in
 * place. A `content://` Uri gives none of those. That is a deliberate property of those
 * modules — it is what lets them be tested on the JVM against real sample files — so the
 * copy is the price of it, paid once here rather than worked around three times.
 *
 * **The copy is where location metadata is won or lost.** Android zeroes the GPS IFD on
 * every `ContentResolver` read — MediaStore and SAF alike — unless the app holds
 * `ACCESS_MEDIA_LOCATION`. Measured on device 2026-07-26: the redacted copy is
 * byte-identical to the original apart from 41 bytes of GPS IFD, at the same total length,
 * so nothing downstream can detect it, let alone recover it. [withLocation] selects which
 * kind of copy this is, and [refresh] exists so a permission granted later can be acted on
 * without making the user pick the file again.
 */
class SourcePhoto private constructor(
    val file: File,
    /** The source's own name, used to derive the saved file's. */
    val displayName: String,
    private val uri: Uri,
    /** Whether this copy was read with location access, i.e. whether its GPS IFD is real. */
    val hasLocation: Boolean,
) {

    /** The outcome of copying a source into the cache. */
    sealed interface Result {

        data class Opened(val source: SourcePhoto) : Result

        /**
         * [reason] carries the underlying failure, not a restatement of the question.
         * Discarding it once cost real debugging time: a share that failed on a missing Uri
         * grant was indistinguishable from a disk error until `dumpsys` was consulted.
         */
        data class Failed(val reason: String) : Result
    }

    companion object {
        /**
         * Copies [uri] into the app's cache.
         *
         * Blocking; call it off the main thread.
         *
         * @param withLocation pass true only when `ACCESS_MEDIA_LOCATION` is actually held.
         *   It turns on [MediaStore.setRequireOriginal], which throws on read without the
         *   permission — so an optimistic true here fails louder than it helps.
         */
        fun copyFrom(context: Context, uri: Uri, withLocation: Boolean): Result {
            // Only the destination is derived before the try, because every catch block
            // deletes it. Everything that touches the Uri belongs inside: querying it and
            // opening it fail the same ways, for the same reasons, and a throw from the
            // query used to escape the catch written for exactly that scenario.
            val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            val destination = File(dir, if (withLocation) SLOT_LOCATED else SLOT_REDACTED)

            return try {
                val name = displayNameOf(context, uri) ?: "shared.jpg"
                val readFrom = if (withLocation) requireOriginal(uri) else uri
                context.contentResolver.openInputStream(readFrom).use { input ->
                    if (input == null) {
                        return Result.Failed("the provider returned no data for this photo")
                    }
                    destination.outputStream().use(input::copyTo)
                }
                Result.Opened(SourcePhoto(destination, name, uri, withLocation))
            } catch (e: IOException) {
                destination.delete()
                Result.Failed(e.reason())
            } catch (e: SecurityException) {
                // The grant that came with a share intent can be gone by the time we read,
                // e.g. once the sending app has been killed.
                destination.delete()
                Result.Failed(e.reason())
            } catch (e: UnsupportedOperationException) {
                // setRequireOriginal raises this when the permission is not actually held.
                destination.delete()
                Result.Failed(e.reason())
            } catch (e: IllegalArgumentException) {
                // Providers raise this for a Uri they do not recognize, and a Uri arriving
                // on a share intent is untrusted input like any other.
                destination.delete()
                Result.Failed(e.reason())
            }
        }

        /**
         * Asks MediaStore for the unredacted file.
         *
         * Only meaningful for MediaStore's own authority. SAF documents come from a
         * different provider, which has no equivalent opt-in — whether the permission alone
         * un-redacts those is unverified, so this deliberately leaves such a Uri untouched
         * rather than transforming it into something the provider will not recognize.
         *
         * That guard is load-bearing, not defensive tidiness. Measured 2026-09-05: Google
         * Photos shares through its *own* provider, never a `content://media` Uri, so this
         * returns the Uri unchanged and `setRequireOriginal` is never called on the one path
         * most shares actually take. Rewriting it would break that path. Photos redacts by
         * the receiving app's permission regardless, so the re-read still recovers GPS.
         */
        private fun requireOriginal(uri: Uri): Uri =
            if (uri.authority == MediaStore.AUTHORITY) MediaStore.setRequireOriginal(uri) else uri

        /**
         * Removes every cached copy.
         *
         * Clears the directory rather than the instances it knows about, so a slot added later
         * cannot be forgotten here and quietly accumulate a copy of the user's photos.
         */
        fun clearCache(context: Context) {
            File(context.cacheDir, CACHE_SUBDIR).deleteRecursively()
        }

        private fun displayNameOf(context: Context, uri: Uri): String? =
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
                }

        private const val CACHE_SUBDIR = "sources"

        /**
         * Two slots, keyed by whether the copy carries location.
         *
         * They are separate files rather than one reused name because a located re-read
         * happens *while the preview player holds the redacted file open*. Rewriting
         * underneath it would leave a prefetching decoder reading a half-written file — and
         * "a paused player does not read" is an assumption about Media3's buffering that this
         * project has no business relying on. Writing elsewhere makes the question moot.
         */
        private const val SLOT_REDACTED = "source.jpg"
        private const val SLOT_LOCATED = "source-located.jpg"
    }

    /**
     * Re-copies from the original Uri, so a location grant can be applied to a photo the
     * user already opened rather than making them pick it again.
     */
    fun refresh(context: Context, withLocation: Boolean): Result =
        copyFrom(context, uri, withLocation)
}
