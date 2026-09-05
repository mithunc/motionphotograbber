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

/** What a save has to do about location before it can write the frame. */
internal sealed interface SaveStep {

    /** Show the rationale and request `ACCESS_MEDIA_LOCATION`, then resume. */
    data object AskForLocation : SaveStep

    /** The permission is held but the open copy is redacted: re-read, then save. */
    data object RefreshThenSave : SaveStep

    /** Nothing to do about location — either it is already there or it is not coming. */
    data object SaveNow : SaveStep
}

/**
 * Decides which of the three save paths runs.
 *
 * Split out of the ViewModel because it is the only part of the decision with no Android
 * in it. The ViewModel's own wiring needs a real decoder to construct and so stays
 * device-only; three interacting booleans do not, and this is where the ordering that
 * matters lives.
 *
 * @param sourceHasLocation whether the open copy's GPS IFD is real rather than zeroed.
 * @param permissionHeld the *live* permission state, not a memory of having asked.
 * @param alreadyAsked whether the dialog has been shown once this process.
 */
internal fun nextSaveStep(
    sourceHasLocation: Boolean,
    permissionHeld: Boolean,
    alreadyAsked: Boolean,
): SaveStep = when {
    // Nothing a permission could add: this copy was already read with location.
    sourceHasLocation -> SaveStep.SaveNow

    // Held but unused — granted in system settings, or during an earlier session that
    // opened this photo before the grant. This must be tested before [alreadyAsked], or a
    // user who declined once would never benefit from granting it later by hand.
    permissionHeld -> SaveStep.RefreshThenSave

    // Asked and not granted. Saving without GPS is the answer; re-asking on every tap is
    // not.
    alreadyAsked -> SaveStep.SaveNow

    else -> SaveStep.AskForLocation
}
