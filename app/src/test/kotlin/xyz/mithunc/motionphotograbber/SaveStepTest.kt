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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The location gate, in isolation.
 *
 * This is the whole reason [nextSaveStep] was lifted out of `GrabberViewModel`: the
 * ViewModel builds a real `MotionPhotoPreviewPlayer` on construction, so even Robolectric
 * cannot reach the gate, and every wrong answer here is a save that silently loses GPS.
 */
class SaveStepTest {

    private class Case(
        val hasLocation: Boolean,
        val permissionHeld: Boolean,
        val alreadyAsked: Boolean,
        val expected: SaveStep,
    ) {
        override fun toString(): String =
            "hasLocation=$hasLocation permissionHeld=$permissionHeld alreadyAsked=$alreadyAsked"
    }

    @Test
    fun `every combination of the three inputs maps to the documented step`() {
        // Written out rather than generated. A table derived from the same rules the
        // implementation uses would agree with any bug those rules contained; this one has
        // to be read against the documentation to be written down at all.
        val cases = listOf(
            Case(hasLocation = true, permissionHeld = true, alreadyAsked = true, expected = SaveStep.SaveNow),
            Case(hasLocation = true, permissionHeld = true, alreadyAsked = false, expected = SaveStep.SaveNow),
            Case(hasLocation = true, permissionHeld = false, alreadyAsked = true, expected = SaveStep.SaveNow),
            Case(hasLocation = true, permissionHeld = false, alreadyAsked = false, expected = SaveStep.SaveNow),
            Case(hasLocation = false, permissionHeld = true, alreadyAsked = true, expected = SaveStep.RefreshThenSave),
            Case(hasLocation = false, permissionHeld = true, alreadyAsked = false, expected = SaveStep.RefreshThenSave),
            Case(hasLocation = false, permissionHeld = false, alreadyAsked = true, expected = SaveStep.SaveNow),
            Case(hasLocation = false, permissionHeld = false, alreadyAsked = false, expected = SaveStep.AskForLocation),
        )

        assertEquals(8, cases.size, "three booleans have eight combinations; one is unlisted")
        cases.forEach { case ->
            assertEquals(
                case.expected,
                nextSaveStep(case.hasLocation, case.permissionHeld, case.alreadyAsked),
                case.toString(),
            )
        }
    }

    @Test
    fun `a permission granted after the dialog was declined still triggers a re-read`() {
        // The order of the two middle branches. Testing alreadyAsked first would send a user
        // who declined once, then granted the permission in system settings, straight to a
        // save with no GPS — with no way left to ask for one.
        assertEquals(
            SaveStep.RefreshThenSave,
            nextSaveStep(sourceHasLocation = false, permissionHeld = true, alreadyAsked = true),
        )
    }

    @Test
    fun `a copy that already carries location never asks for the permission`() {
        // The common repeat-user case: load() reads with location up front when the
        // permission is held, so save must not re-copy an 8 MB file to learn nothing.
        assertEquals(
            SaveStep.SaveNow,
            nextSaveStep(sourceHasLocation = true, permissionHeld = false, alreadyAsked = false),
        )
    }

    @Test
    fun `a declined dialog is not asked again`() {
        assertEquals(
            SaveStep.SaveNow,
            nextSaveStep(sourceHasLocation = false, permissionHeld = false, alreadyAsked = true),
        )
    }
}
