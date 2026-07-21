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
package xyz.mithunc.motionphotograbber.extract

import android.graphics.Bitmap
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import xyz.mithunc.motionphotograbber.motionphoto.MotionPhoto
import xyz.mithunc.motionphotograbber.motionphoto.MotionPhotoParser
import java.io.File
import java.io.FileOutputStream

/**
 * Pins down how `FrameExtractor` treats the embedded clip's rotation and track choice.
 *
 * Media3 documents neither, so both were measured on a physical Pixel (2026-07-20)
 * rather than assumed. Across all three samples the declared track was 1440x1080 and
 * every decoded bitmap came back 1080x1440 — dimensions swapped. **Media3 applies the
 * `tkhd` rotation itself, so this project must not apply it again**; doing so would
 * produce 180°-wrong frames.
 *
 * The assertions below exist to catch a future Media3 upgrade silently changing that.
 * If this test starts failing after a version bump, the extraction path needs revisiting
 * before the bump ships — do not adjust the expectations to match.
 *
 * Note a 90° and a 270° rotation both swap the dimensions, so the shape check proves
 * rotation was *applied*, not that it was applied in the correct *direction*. That is
 * what the PNGs written by this test are for — see below.
 *
 * **Setup** — `samples/` is gitignored because those files carry real GPS, so they are
 * pushed to the device rather than committed.
 *
 * **Do not run this with `connectedAndroidTest`.** That task uninstalls the test APK when
 * it finishes, and uninstalling deletes `/sdcard/Android/data/<pkg>/` — taking both the
 * pushed samples and this test's output with it. The samples vanish before the next run
 * (the test then skips, which reads as success) and the PNGs vanish before they can be
 * pulled. Install once and drive the run directly instead:
 * ```
 * ./gradlew :core-extract:installDebugAndroidTest
 * adb shell mkdir -p /sdcard/Android/data/xyz.mithunc.motionphotograbber.extract.test/files/
 * adb push samples/PXL_*.MP.jpg \
 *   /sdcard/Android/data/xyz.mithunc.motionphotograbber.extract.test/files/
 * adb shell am instrument -w \
 *   xyz.mithunc.motionphotograbber.extract.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d -s MotionPhotoProbe
 * ```
 * Skips rather than fails when nothing has been pushed — so a skip means the push is
 * missing, not that the behavior is fine.
 *
 * Each decoded frame is written to `frames/` beside the samples, for the visual upright
 * check the dimension assertions cannot make:
 * ```
 * adb pull /sdcard/Android/data/xyz.mithunc.motionphotograbber.extract.test/files/frames/
 * ```
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class FrameExtractorOrientationTest {

    companion object {
        private const val TAG = "MotionPhotoProbe"

        /** Far enough into the clip to be a mid-clip frame rather than the shutter frame. */
        private const val PROBE_POSITION_MS = 500L

        /**
         * How far the returned frame may sit from the requested position.
         *
         * Generous on purpose: the point is not to pin down seek accuracy but to catch a
         * single-sample track being selected, which pins every result at 0 ms regardless
         * of what was asked for. Observed drift on real samples was 8-36 ms.
         */
        private const val POSITION_TOLERANCE_MS = 250L
    }

    @Test
    fun mediaThreeAppliesRotationAndPicksTheRealClipTrack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDir = context.getExternalFilesDir(null)
        val samples = filesDir
            ?.listFiles { f: File -> f.isFile && f.name.lowercase().endsWith(".jpg") }
            ?.sortedBy { it.name }
            .orEmpty()

        assumeFalse(
            "No samples pushed to $filesDir; see the class docs.",
            samples.isEmpty(),
        )

        val frameDir = File(filesDir, "frames").apply { mkdirs() }

        for (sample in samples) {
            val parsed = MotionPhotoParser.parse(sample)
            if (parsed !is MotionPhoto.Found) {
                Log.w(TAG, "${sample.name}: not a parseable motion photo ($parsed)")
                continue
            }

            MotionPhotoFrameExtractor.create(context, sample, parsed.videoByteRange).use { extractor ->
                val info = extractor.readVideoInfo()
                val frame = runBlocking { extractor.frameAt(PROBE_POSITION_MS) }

                Log.i(TAG, "=== ${sample.name} ===")
                Log.i(TAG, "  declared: ${info.declaredWidth}x${info.declaredHeight}")
                Log.i(TAG, "  rotation: ${info.rotationDegrees} deg")
                Log.i(TAG, "  duration: ${info.durationMs} ms")
                Log.i(TAG, "  BITMAP:   ${frame.bitmap.width}x${frame.bitmap.height}")
                Log.i(TAG, "  frame at: ${frame.presentationTimeMs} ms")

                writePng(frame.bitmap, File(frameDir, "${sample.name}.png"))

                // A quarter turn swaps the axes; a half turn or none leaves them alone.
                val quarterTurn = info.rotationDegrees == 90 || info.rotationDegrees == 270
                val expectedWidth =
                    if (quarterTurn) info.declaredHeight else info.declaredWidth
                val expectedHeight =
                    if (quarterTurn) info.declaredWidth else info.declaredHeight

                assertEquals(
                    "${sample.name}: expected Media3 to have applied the " +
                        "${info.rotationDegrees} deg rotation",
                    "${expectedWidth}x$expectedHeight",
                    "${frame.bitmap.width}x${frame.bitmap.height}",
                )

                // A file may carry a second, single-sample video track. Selecting it
                // would peg every request at 0 ms, so a frame landing near the requested
                // position is evidence the real clip track was decoded. Stated as an
                // invariant rather than as per-file dimensions, since samples/ differs
                // per machine.
                if (info.durationMs > PROBE_POSITION_MS + POSITION_TOLERANCE_MS) {
                    assertTrue(
                        "${sample.name}: frame came back at ${frame.presentationTimeMs} ms " +
                            "for a ${PROBE_POSITION_MS} ms request, which suggests a " +
                            "single-sample track was selected",
                        Math.abs(frame.presentationTimeMs - PROBE_POSITION_MS) <=
                            POSITION_TOLERANCE_MS,
                    )
                }
            }
        }
    }

    private fun writePng(bitmap: Bitmap, target: File) {
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
