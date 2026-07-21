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

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Presents a byte range of a larger file as if it were a standalone file.
 *
 * A motion photo stores its still and its video in one file, so the video can be decoded
 * in place rather than copied out to a temporary MP4. Media3 exposes no "read only these
 * bytes" hook on [androidx.media3.inspector.frame.FrameExtractor] itself, but its
 * `Builder` accepts a `MediaSource.Factory`, which is in turn built from a
 * `DataSource.Factory` — so the byte range is applied one level down, here.
 *
 * Every position the extractor asks for is relative to the start of the video; this
 * class translates it into an absolute position in the source file. Media3 therefore
 * sees a file that begins at `ftyp` and ends at the clip's last byte, and never learns
 * that a JPEG precedes it.
 */
@UnstableApi
class SubrangeDataSource(
    private val upstream: DataSource,
    private val rangeStart: Long,
    private val rangeLength: Long,
) : DataSource {

    /** Produces [SubrangeDataSource] instances over a fixed range of the source file. */
    @UnstableApi
    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val rangeStart: Long,
        private val rangeLength: Long,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SubrangeDataSource(upstreamFactory.createDataSource(), rangeStart, rangeLength)
    }

    override fun open(dataSpec: DataSpec): Long {
        val remaining = rangeLength - dataSpec.position
        require(remaining >= 0) {
            "position ${dataSpec.position} is past the end of the $rangeLength byte range"
        }
        // Cap the read so the extractor can never run off the end of the clip into
        // whatever follows it in the container.
        val cappedLength = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            remaining
        } else {
            minOf(dataSpec.length, remaining)
        }

        val rebased = dataSpec.buildUpon()
            .setPosition(rangeStart + dataSpec.position)
            .setLength(cappedLength)
            .build()
        upstream.open(rebased)

        // Report the clipped length rather than whatever upstream returned, so Media3
        // treats the clip's final byte as EOF.
        return cappedLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun close() {
        upstream.close()
    }
}
