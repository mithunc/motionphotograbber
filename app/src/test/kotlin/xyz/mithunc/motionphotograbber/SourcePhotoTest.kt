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

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException

/**
 * [SourcePhoto.copyFrom] against providers that misbehave the way real ones do.
 *
 * **Why these are worth writing.** `copyFrom` used to query the Uri for its display name
 * and call `setRequireOriginal` *above* its own `try`, so a revoked share grant threw
 * `SecurityException` one line above the catch written for that exact scenario — straight
 * out through `GrabberViewModel`'s bare `viewModelScope.launch`. Every one of these paths
 * was device-only to verify before Robolectric was added.
 */
@RunWith(RobolectricTestRunner::class)
class SourcePhotoTest {

    private lateinit var context: Context
    private val uri: Uri = Uri.parse("content://$AUTHORITY/photo/1")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        SourcePhoto.clearCache(context)
    }

    @Test
    fun `a grant revoked before the query is reported, not thrown`() {
        register(RevokedGrantProvider::class.java)

        val result = SourcePhoto.copyFrom(context, uri, withLocation = false)

        val failed = assertFailed(result)
        assertTrue(
            "the reason must carry the underlying failure: ${failed.reason}",
            failed.reason.contains("revoked", ignoreCase = true),
        )
    }

    @Test
    fun `a Uri the provider does not recognize is reported, not thrown`() {
        // Providers raise IllegalArgumentException for an unrecognized Uri, and a Uri
        // arriving on a share intent is untrusted input like any other.
        register(UnknownUriProvider::class.java)

        assertFailed(SourcePhoto.copyFrom(context, uri, withLocation = false))
    }

    @Test
    fun `a provider that cannot open the file is reported, not thrown`() {
        register(NoDataProvider::class.java)

        assertFailed(SourcePhoto.copyFrom(context, uri, withLocation = false))
    }

    @Test
    fun `a working provider yields a cached copy carrying the source's name`() {
        // Guards the widened try against having broken the path that matters most: if
        // moving the query inside it had changed the happy case, every other assertion
        // here would still pass.
        register(WorkingProvider::class.java)

        val result = SourcePhoto.copyFrom(context, uri, withLocation = false)

        val opened = result as? SourcePhoto.Result.Opened
            ?: error("expected Opened, got $result")
        assertEquals(DISPLAY_NAME, opened.source.displayName)
        assertEquals(false, opened.source.hasLocation)
        assertArrayEquals(PHOTO_BYTES, opened.source.file.readBytes())
    }

    @Test
    fun `a failed copy leaves no half-written file behind`() {
        // The caller's next step is to parse this file, and a file that exists is one it
        // could parse. A zero-byte leftover would read as a corrupt photo.
        register(NoDataProvider::class.java)

        assertFailed(SourcePhoto.copyFrom(context, uri, withLocation = false))

        val cached = File(context.cacheDir, "sources").listFiles().orEmpty()
        assertTrue("left behind: ${cached.map(File::getName)}", cached.isEmpty())
    }

    // --- helpers ---

    private fun assertFailed(result: SourcePhoto.Result): SourcePhoto.Result.Failed =
        result as? SourcePhoto.Result.Failed ?: error("expected Failed, got $result")

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue(
            "expected ${expected.size} bytes, got ${actual.size}",
            expected.contentEquals(actual),
        )
    }

    private fun <T : ContentProvider> register(provider: Class<T>) {
        Robolectric.buildContentProvider(provider).create(AUTHORITY)
    }

    // --- providers ---

    /** Everything not overridden is unreachable in these tests. */
    abstract class StubProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            args: Array<out String>?,
        ): Int = 0

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
            .apply { addRow(arrayOf(DISPLAY_NAME)) }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
            throw FileNotFoundException("no such photo")
    }

    /** The sending app was killed, so the grant that came with the share intent is gone. */
    class RevokedGrantProvider : StubProvider() {
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sortOrder: String?,
        ): Cursor = throw SecurityException("permission for $uri was revoked")
    }

    class UnknownUriProvider : StubProvider() {
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sortOrder: String?,
        ): Cursor = throw IllegalArgumentException("unknown URI $uri")
    }

    /** Answers the name query, then has nothing to hand over. */
    class NoDataProvider : StubProvider()

    class WorkingProvider : StubProvider() {
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val context: Context = ApplicationProvider.getApplicationContext()
            val backing = File(context.cacheDir, "provider-backing.jpg")
            backing.writeBytes(PHOTO_BYTES)
            return ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    companion object {
        /**
         * Deliberately not `MediaStore.AUTHORITY`: `requireOriginal` only rewrites
         * MediaStore's own Uris, so a third-party authority keeps these tests on the path
         * a share intent actually takes.
         */
        private const val AUTHORITY = "xyz.mithunc.motionphotograbber.test.photos"

        private const val DISPLAY_NAME = "PXL_20260717_011104182.MP.jpg"

        private val PHOTO_BYTES: ByteArray =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(32) +
                byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }
}
