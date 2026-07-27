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
package xyz.mithunc.motionphotograbber.exif

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A JPEG carrying no metadata whatsoever — what an extracted frame looks like before
 * this module touches it.
 *
 * `javax.imageio` is used rather than a checked-in fixture so the dimensions can vary
 * per test: proving that the output's `PixelXDimension` came from the output and not
 * from the source needs the two files to be measurably different sizes.
 *
 * Available because local unit tests run on the desktop JVM. Nothing in `main/` depends
 * on it, so this stays out of the Android runtime entirely.
 */
fun writeBlankJpeg(file: File, width: Int, height: Int): File {
    ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", file)
    return file
}

/**
 * The real motion photos in `samples/`, or empty in a fresh clone.
 *
 * That directory is git-ignored because the files carry real GPS data, so every test
 * that depends on it must skip rather than fail — see `samples/README.md`.
 */
fun sampleJpegs(): List<File> {
    val workingDir = System.getProperty("user.dir") ?: return emptyList()
    val dir = generateSequence(File(workingDir).absoluteFile) { it.parentFile }
        .map { File(it, "samples") }
        .firstOrNull { it.isDirectory }
        ?: return emptyList()
    return dir.listFiles { f: File -> f.isFile && f.name.lowercase().endsWith(".jpg") }
        ?.sortedBy { it.name }
        .orEmpty()
}
