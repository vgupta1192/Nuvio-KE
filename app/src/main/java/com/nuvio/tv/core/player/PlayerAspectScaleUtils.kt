package com.nuvio.tv.core.player

import android.graphics.Rect
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.media3.ui.PlayerView
import com.nuvio.tv.R

enum class AspectMode(@param:StringRes val labelResId: Int) {
    ORIGINAL(R.string.player_aspect_fit),
    FULL_SCREEN(R.string.player_aspect_crop),
    STRETCH(R.string.player_aspect_stretch),
    CUSTOM_STRETCH(R.string.player_aspect_custom_stretch),
    SLIGHT_ZOOM(R.string.player_aspect_mode_slight_zoom),
    CINEMA_ZOOM(R.string.player_aspect_mode_cinema_zoom),
    VERTICAL_STRETCH(R.string.player_aspect_fit_height),
    HORIZONTAL_STRETCH(R.string.player_aspect_fit_width)
}

internal fun nextAspectMode(current: AspectMode): AspectMode {
    val modes = AspectMode.entries
    val nextIndex = (modes.indexOf(current) + 1) % modes.size
    return modes[nextIndex]
}

internal fun aspectModeLabel(mode: AspectMode, getString: (Int) -> String): String =
    getString(mode.labelResId)

internal data class AspectScale(val scaleX: Float, val scaleY: Float)

internal fun aspectModeNeedsVideoAspect(mode: AspectMode): Boolean {
    return when (mode) {
        AspectMode.FULL_SCREEN,
        AspectMode.STRETCH,
        AspectMode.CUSTOM_STRETCH,
        AspectMode.VERTICAL_STRETCH,
        AspectMode.HORIZONTAL_STRETCH -> true

        AspectMode.ORIGINAL,
        AspectMode.SLIGHT_ZOOM,
        AspectMode.CINEMA_ZOOM -> false
    }
}

internal fun readViewAspectRatio(width: Int, height: Int): Float {
    return if (width > 0 && height > 0) {
        width.toFloat() / height.toFloat()
    } else {
        0f
    }
}

internal fun readExoVideoAspectRatio(playerView: PlayerView): Float? {
    val videoSize = playerView.player?.videoSize
    return if ((videoSize?.height ?: 0) > 0) {
        ((videoSize?.width ?: 0).toFloat() * (videoSize?.pixelWidthHeightRatio ?: 1f)) /
            videoSize!!.height.toFloat()
    } else {
        null
    }
}

internal fun resolveAspectScale(mode: AspectMode, viewAspect: Float, videoAspect: Float?): AspectScale {
    if (viewAspect <= 0f) {
        return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
    }

    return when (mode) {
        AspectMode.ORIGINAL -> AspectScale(scaleX = 1.0f, scaleY = 1.0f)

        AspectMode.FULL_SCREEN -> {
            val safeVideoAspect = videoAspect?.takeIf { it > 0f }
                ?: return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
            val uniformScale = if (safeVideoAspect > viewAspect) {
                safeVideoAspect / viewAspect
            } else {
                viewAspect / safeVideoAspect
            }
            AspectScale(scaleX = uniformScale, scaleY = uniformScale)
        }

        AspectMode.STRETCH,
        AspectMode.CUSTOM_STRETCH -> {
            val safeVideoAspect = videoAspect?.takeIf { it > 0f }
                ?: return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
            if (safeVideoAspect > viewAspect) {
                AspectScale(scaleX = 1.0f, scaleY = safeVideoAspect / viewAspect)
            } else {
                AspectScale(scaleX = viewAspect / safeVideoAspect, scaleY = 1.0f)
            }
        }

        AspectMode.SLIGHT_ZOOM -> AspectScale(scaleX = 1.15f, scaleY = 1.15f)

        AspectMode.CINEMA_ZOOM -> AspectScale(scaleX = 1.33f, scaleY = 1.33f)

        AspectMode.VERTICAL_STRETCH -> {
            val safeVideoAspect = videoAspect?.takeIf { it > 0f }
                ?: return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
            if (safeVideoAspect > viewAspect) {
                val uniformScale = safeVideoAspect / viewAspect
                AspectScale(scaleX = uniformScale, scaleY = uniformScale)
            } else {
                AspectScale(scaleX = 1.0f, scaleY = 1.0f)
            }
        }

        AspectMode.HORIZONTAL_STRETCH -> {
            val safeVideoAspect = videoAspect?.takeIf { it > 0f }
                ?: return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
            if (safeVideoAspect < viewAspect) {
                val uniformScale = viewAspect / safeVideoAspect
                AspectScale(scaleX = uniformScale, scaleY = uniformScale)
            } else {
                AspectScale(scaleX = 1.0f, scaleY = 1.0f)
            }
        }
    }
}

internal fun applyExoAspectMode(playerView: PlayerView, mode: AspectMode) {
    val contentFrame = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
    val surfaceView = resolveVideoSurfaceView(playerView)
    val targetView = contentFrame ?: surfaceView ?: playerView
    val viewAspect = readViewAspectRatio(playerView.width, playerView.height)
    val videoAspect = readExoVideoAspectRatio(playerView)

    resetAspectTransform(playerView)
    contentFrame?.let(::resetAspectTransform)
    surfaceView?.let(::resetAspectTransform)

    applyAspectScale(targetView, mode, viewAspect, videoAspect)
    centerTargetInPlayer(playerView, targetView)
    if (mode == AspectMode.CUSTOM_STRETCH && targetView !== playerView) {
        (playerView.getTag(R.id.player_view_custom_stretch_tag) as? VideoContentRect)
            ?.let { applyCustomStretchRect(targetView, it) }
    }
}

/**
 * Custom Stretch on top of the plain stretch: the target view shows the whole frame and
 * already covers the player, so scale it once more so the picture area covers the player.
 */
private fun applyCustomStretchRect(targetView: View, rect: VideoContentRect) {
    if (rect.width <= 0f || rect.height <= 0f || targetView.width <= 0 || targetView.height <= 0) return
    val scaleX = targetView.scaleX / rect.width
    val scaleY = targetView.scaleY / rect.height
    targetView.scaleX = scaleX
    targetView.scaleY = scaleY
    targetView.translationX -= ((rect.left + rect.right) / 2f - 0.5f) * targetView.width * scaleX
    targetView.translationY -= ((rect.top + rect.bottom) / 2f - 0.5f) * targetView.height * scaleY
}

/** Picture area found by Custom Stretch for this player view (null = whole frame). */
internal fun setExoCustomStretchRect(playerView: PlayerView, rect: VideoContentRect?) {
    playerView.setTag(R.id.player_view_custom_stretch_tag, rect)
}

/** The surface ExoPlayer renders into; captures of it are not affected by view transforms. */
internal fun exoVideoSurfaceView(playerView: PlayerView): View? = resolveVideoSurfaceView(playerView)

internal fun addExoAspectLayoutChangeListener(
    playerView: PlayerView,
    listener: View.OnLayoutChangeListener
): () -> Unit {
    val targets = linkedSetOf<View>()
    targets.add(playerView)
    playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.let(targets::add)
    resolveVideoSurfaceView(playerView)?.let(targets::add)
    targets.forEach { it.addOnLayoutChangeListener(listener) }
    return {
        targets.forEach { it.removeOnLayoutChangeListener(listener) }
    }
}

private fun applyAspectScale(targetView: View, mode: AspectMode, viewAspect: Float, videoAspect: Float?) {
    val scale = resolveAspectScale(
        mode = mode,
        viewAspect = viewAspect,
        videoAspect = videoAspect
    )
    targetView.scaleX = scale.scaleX
    targetView.scaleY = scale.scaleY
}

private fun resetAspectTransform(view: View) {
    view.scaleX = 1.0f
    view.scaleY = 1.0f
    view.translationX = 0.0f
    view.translationY = 0.0f
    if (view.width > 0) {
        view.pivotX = view.width / 2.0f
    }
    if (view.height > 0) {
        view.pivotY = view.height / 2.0f
    }
}

private fun centerTargetInPlayer(playerView: PlayerView, targetView: View) {
    if (
        targetView === playerView ||
        playerView.width <= 0 ||
        playerView.height <= 0 ||
        targetView.width <= 0 ||
        targetView.height <= 0
    ) {
        return
    }

    val targetRect = Rect(0, 0, targetView.width, targetView.height)
    playerView.offsetDescendantRectToMyCoords(targetView, targetRect)
    val playerCenterX = playerView.width / 2.0f
    val playerCenterY = playerView.height / 2.0f
    val targetCenterX = targetRect.left + targetRect.width() / 2.0f
    val targetCenterY = targetRect.top + targetRect.height() / 2.0f
    targetView.translationX = playerCenterX - targetCenterX
    targetView.translationY = playerCenterY - targetCenterY
}

private fun resolveVideoSurfaceView(playerView: PlayerView): View? {
    return findVideoSurfaceView(playerView)
}

private fun findVideoSurfaceView(view: View): View? {
    return when (view) {
        is SurfaceView, is TextureView -> view
        is ViewGroup -> {
            for (index in 0 until view.childCount) {
                val child = findVideoSurfaceView(view.getChildAt(index))
                if (child != null) {
                    return child
                }
            }
            null
        }

        else -> null
    }
}
