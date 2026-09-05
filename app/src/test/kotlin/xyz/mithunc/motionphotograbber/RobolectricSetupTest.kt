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
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves the Robolectric setup actually runs, rather than merely resolving.
 *
 * **Why a test exists purely to test the test infrastructure.** Robolectric's runner is
 * JUnit 4 and everything else in this project is JUnit 6 Jupiter, so these classes reach
 * the JUnit Platform through `junit-vintage-engine`. If that engine is absent or fails to
 * discover them, nothing runs and `./gradlew test` still reports success — the setup would
 * be silently inert, and the first sign would be a bug reaching a device that a test was
 * supposed to have caught. A green build is therefore not evidence on its own; this class
 * appearing in the test report is.
 *
 * Reading a string resource rather than asserting something trivial is deliberate: it
 * exercises the runner, the Android runtime *and* `isIncludeAndroidResources` together,
 * which is the whole surface `:app`'s real tests will stand on.
 *
 * Written in JUnit 4 because Robolectric offers no supported Jupiter runner. The Jupiter
 * tests in the `core-` modules are unaffected; both engines report into one run.
 */
@RunWith(RobolectricTestRunner::class)
class RobolectricSetupTest {

    @Test
    fun `the Android runtime is present and app resources resolve`() {
        val context: Context = ApplicationProvider.getApplicationContext()

        assertEquals("Motion Photo Grabber", context.getString(R.string.app_name))
    }
}
