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
package android.util

/**
 * A working `android.util.Pair` for local unit tests. **Do not delete: without it every
 * `ExifInterface.setAttribute` call in this module's tests throws.**
 *
 * Local unit tests run on the desktop JVM against AGP's *mockable* `android.jar`, which
 * strips every method body — constructors included. `ExifInterface.setAttribute` calls
 * `guessDataFormat`, which returns an `android.util.Pair` and immediately unboxes
 * `.first` / `.second`; against the mockable jar those fields are never assigned, so the
 * unboxing NPEs. (Verified by disassembling exifinterface 1.4.2; 46 references.)
 *
 * `unitTests.isReturnDefaultValues` cannot fix this on its own — it makes stub methods
 * return defaults rather than throw, but a constructor that returns without assigning
 * still leaves null fields. A real class in the test source set does fix it: test classes
 * precede the mockable jar on the unit-test classpath, so this definition wins.
 *
 * Nothing else in the JPEG read/write path needs the same treatment. `android.util.Log`
 * is fine as a no-op, `android.system.Os` is reached only from `isSeekableFD`,
 * `printAttributes` and `getThumbnailBytes`, and `MediaMetadataRetriever` is HEIF-only.
 */
class Pair<F, S>(@JvmField val first: F, @JvmField val second: S) {

    override fun equals(other: Any?): Boolean {
        if (other !is Pair<*, *>) return false
        return first == other.first && second == other.second
    }

    override fun hashCode(): Int = (first?.hashCode() ?: 0) xor (second?.hashCode() ?: 0)

    override fun toString(): String = "Pair{$first $second}"
}
