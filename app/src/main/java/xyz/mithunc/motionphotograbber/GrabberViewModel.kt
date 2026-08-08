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

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mithunc.motionphotograbber.extract.MotionPhotoPreviewPlayer
import xyz.mithunc.motionphotograbber.extract.PreviewState
import xyz.mithunc.motionphotograbber.motionphoto.MotionPhoto
import xyz.mithunc.motionphotograbber.motionphoto.MotionPhotoParser
import xyz.mithunc.motionphotograbber.ui.LoadError
import xyz.mithunc.motionphotograbber.ui.UiState

/** Something that happened once and should not be replayed into state. */
sealed interface GrabberEvent {

    data class SaveFinished(val result: SaveResult) : GrabberEvent

    /**
     * The user is saving a photo whose location was redacted, and the permission that would
     * un-redact it has not been asked for yet. The UI shows the rationale and requests it.
     */
    data object NeedsLocationPermission : GrabberEvent
}

/**
 * Holds everything the one screen needs, and owns the objects that outlive a rotation.
 *
 * The preview player and the cached copy of the source both survive configuration
 * changes here, which is the point: reopening an 8 MB file and re-preparing a decoder
 * every time the device turns would be visible.
 */
@UnstableApi
class GrabberViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /**
         * How close to the shutter-press frame counts as parked on it, as a fraction of
         * the clip.
         *
         * A magnet rather than a tolerance: the position snaps to exactly the default
         * once inside it, so "at the default frame" stays an equality test and the save
         * path never has to decide how near is near enough. Two percent of a 2.6 s clip
         * is ~50 ms, about a thumb's width of slider.
         */
        private const val SNAP_FRACTION = 0.02f

        private const val LOCATION_PERMISSION = Manifest.permission.ACCESS_MEDIA_LOCATION
    }

    /** One open photo: the file, what parsing found in it, and the decoder on it. */
    private class Session(
        var source: SourceFile,
        var found: MotionPhoto.Found,
        val player: MotionPhotoPreviewPlayer,
    )

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<GrabberEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var session: Session? = null
    private var previewJob: Job? = null

    /**
     * Held so the player can be reattached when a load finishes after the surface exists,
     * which is the normal order for a share intent. Nulled on dispose, so it does not
     * outlive the composable that made it.
     */
    private var surfaceView: SurfaceView? = null

    /**
     * What [loadIfNeeded] has already opened.
     *
     * A rotation destroys the composition, so the `LaunchedEffect` that delivers a share
     * intent runs again with the same Uri — without this the file would be re-copied and
     * re-parsed on every turn of the device, resetting the scrub position each time.
     */
    private var loadedUri: Uri? = null

    /**
     * Whether the location permission has been requested during this process.
     *
     * Asked at most once: a user who declined should be able to keep saving without the
     * dialog reappearing on every tap. A grant made later in system settings is still picked
     * up, because the gate re-checks the real permission state rather than trusting this.
     */
    private var locationAsked = false

    /** The `Software` string of a save paused waiting on the permission result. */
    private var pendingSaveSoftware: String? = null

    /** Opens [uri], unless it is already the open one. */
    fun loadIfNeeded(uri: Uri) {
        if (loadedUri == uri) return
        load(uri)
    }

    fun load(uri: Uri) {
        loadedUri = uri
        viewModelScope.launch {
            closeSession()
            _uiState.value = UiState.Loading

            val context = getApplication<Application>()
            // Read with location straight away when the permission is already held, so the
            // common repeat-user case needs no re-read at save time.
            val withLocation = hasLocationPermission()
            val opened = withContext(Dispatchers.IO) {
                SourceFile.copyFrom(context, uri, withLocation)
            }
            val source = when (opened) {
                is SourceFile.Result.Opened -> opened.source
                is SourceFile.Result.Failed -> {
                    _uiState.value = UiState.Failed(LoadError.Unreadable(opened.reason))
                    return@launch
                }
            }

            val found = when (val parsed = withContext(Dispatchers.IO) { MotionPhotoParser.parse(source.file) }) {
                is MotionPhoto.Found -> parsed
                is MotionPhoto.NotMotionPhoto -> return@launch fail(LoadError.NotAMotionPhoto)
                is MotionPhoto.NotSupported -> return@launch fail(LoadError.UnsupportedFlavor(parsed.flavor))
                is MotionPhoto.Malformed -> return@launch fail(LoadError.Malformed(parsed.reason))
            }

            // ExoPlayer wants a Looper thread, so it is built here on the main dispatcher
            // rather than alongside the IO above.
            val player = MotionPhotoPreviewPlayer.create(context, source.file, found.videoByteRange)
            session = Session(source, found, player)
            surfaceView?.let(player::attachTo)
            observe(player, found)
        }
    }

    private fun fail(error: LoadError) {
        SourceFile.clearCache(getApplication())
        _uiState.value = UiState.Failed(error)
    }

    private fun observe(player: MotionPhotoPreviewPlayer, found: MotionPhoto.Found) {
        previewJob = viewModelScope.launch {
            launch {
                player.state.collect { previewState ->
                    when (previewState) {
                        is PreviewState.Preparing -> Unit
                        is PreviewState.Failed ->
                            _uiState.value = UiState.Failed(LoadError.DecodeFailed(previewState.reason))
                        is PreviewState.Ready -> {
                            // Ready arrives again after each seek settles; only the first one
                            // establishes the scrubber, or every seek would reset the thumb.
                            if (_uiState.value !is UiState.Ready) {
                                val default = found.defaultFrameTimestampUs
                                    ?.let { (it / 1_000L).coerceIn(0L, previewState.durationMs) }
                                val start = default ?: 0L
                                _uiState.value = UiState.Ready(
                                    durationMs = previewState.durationMs,
                                    positionMs = start,
                                    defaultFramePositionMs = default,
                                    atDefaultFrame = default != null,
                                    previewAspectRatio = player.aspectRatio.value,
                                )
                                player.seekTo(start)
                            }
                        }
                    }
                }
            }
            // The video's shape arrives on its own schedule, generally just after the first
            // frame decodes, so it is folded in separately rather than waited for.
            launch {
                player.aspectRatio.collect { ratio ->
                    (_uiState.value as? UiState.Ready)?.let {
                        _uiState.value = it.copy(previewAspectRatio = ratio)
                    }
                }
            }
        }
    }

    fun onScrub(positionMs: Long) {
        val state = _uiState.value as? UiState.Ready ?: return
        val snapped = snap(positionMs, state)
        _uiState.value = state.copy(
            positionMs = snapped,
            atDefaultFrame = snapped == state.defaultFramePositionMs,
        )
        // Straight through: the player coalesces these itself, issuing the newest position as
        // soon as the previous one has painted. Debouncing here would only add lag on top.
        session?.player?.seekTo(snapped)
    }

    private fun snap(positionMs: Long, state: UiState.Ready): Long {
        val clamped = positionMs.coerceIn(0L, state.durationMs)
        val default = state.defaultFramePositionMs ?: return clamped
        val window = (state.durationMs * SNAP_FRACTION).toLong().coerceAtLeast(1L)
        return if (kotlin.math.abs(clamped - default) <= window) default else clamped
    }

    fun save(software: String) {
        val current = session ?: return
        val state = _uiState.value as? UiState.Ready ?: return
        if (state.saving) return

        // Location is redacted at read time, so a permission held now only helps via a
        // re-read. Both branches below exist: asking is not the only way the permission can
        // become available.
        if (!current.source.hasLocation) {
            if (hasLocationPermission()) {
                // Granted out of band — in system settings, or during an earlier session that
                // loaded this photo before the grant. Nothing to ask; just re-read and save.
                viewModelScope.launch {
                    refreshSourceWithLocation()
                    performSave(software)
                }
                return
            }
            if (!locationAsked) {
                locationAsked = true
                pendingSaveSoftware = software
                viewModelScope.launch { _events.send(GrabberEvent.NeedsLocationPermission) }
                return
            }
        }
        performSave(software)
    }

    /**
     * Continues a save that stopped to ask for the location permission.
     *
     * A denial is not an error: the frame still saves, just without GPS. That is the same
     * outcome the app had before the permission existed.
     */
    fun onLocationPermissionResult(granted: Boolean) {
        val software = pendingSaveSoftware ?: return
        pendingSaveSoftware = null
        viewModelScope.launch {
            if (granted) refreshSourceWithLocation()
            performSave(software)
        }
    }

    /**
     * Re-reads the source now that location is available, and re-parses it.
     *
     * The ranges will almost certainly be identical — redaction zeroes the GPS IFD in place
     * without changing the file's length — but deriving byte offsets from an assumption about
     * how a privacy filter happens to work is exactly the kind of shortcut that produces a
     * corrupt save, so the parse is redone.
     */
    private suspend fun refreshSourceWithLocation() {
        val current = session ?: return
        val context = getApplication<Application>()
        val refreshed = withContext(Dispatchers.IO) { current.source.refresh(context, withLocation = true) }
        if (refreshed !is SourceFile.Result.Opened) return
        val parsed = withContext(Dispatchers.IO) { MotionPhotoParser.parse(refreshed.source.file) }
        if (parsed !is MotionPhoto.Found) return
        current.source = refreshed.source
        current.found = parsed
    }

    private fun performSave(software: String) {
        val current = session ?: return
        val state = _uiState.value as? UiState.Ready ?: return
        _uiState.value = state.copy(saving = true)

        // Read on this thread, before any dispatch: the player is main-thread-only, and
        // where it actually landed is what the user is looking at.
        val positionMs = if (state.atDefaultFrame) state.positionMs else current.player.settledPositionMs

        viewModelScope.launch {
            val result = FrameSaver(getApplication()).save(
                source = current.source,
                found = current.found,
                positionMs = positionMs,
                atDefaultFrame = state.atDefaultFrame,
                software = software,
            )
            (_uiState.value as? UiState.Ready)?.let { _uiState.value = it.copy(saving = false) }
            _events.send(GrabberEvent.SaveFinished(result))
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), LOCATION_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    fun attachSurface(view: SurfaceView) {
        surfaceView = view
        val player = session?.player ?: return
        player.attachTo(view)
        // Re-seek so the new surface actually gets a frame. The surface is created by the
        // composable *after* state goes Ready, so the opening seek has already happened by
        // the time it exists — and the same gap reopens on every rotation. Without this the
        // preview can come up black until the user touches the slider.
        (_uiState.value as? UiState.Ready)?.let { player.seekTo(it.positionMs) }
    }

    fun detachSurface() {
        surfaceView = null
        session?.player?.detach()
    }

    private fun closeSession() {
        previewJob?.cancel()
        previewJob = null
        session?.player?.close()
        session = null
        pendingSaveSoftware = null
        SourceFile.clearCache(getApplication())
    }

    override fun onCleared() {
        super.onCleared()
        closeSession()
    }
}
