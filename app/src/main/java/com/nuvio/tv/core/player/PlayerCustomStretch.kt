package com.nuvio.tv.core.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Custom Stretch: find the black bars that are baked into the video frame (letterbox,
 * pillarbox or both) and stretch only the picture area over the whole screen.
 *
 * Plain "Stretch" scales the decoded frame to the screen, so bars inside the file stay
 * visible. Custom Stretch samples frames, measures the area that holds picture and maps
 * that area to the full player size. Nothing inside the picture area is cropped: only bar
 * edges that meet clearly brighter picture are trusted, and each edge keeps the thinnest
 * trusted bar, so a dark scene or a vignette can never make the picture smaller.
 */

/** Picture area inside the decoded frame, as fractions (0..1) of the frame size. */
internal data class VideoContentRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun union(other: VideoContentRect): VideoContentRect = VideoContentRect(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom),
    )

    /** True when this area shows at least all of [other] (no edge is further in). */
    fun covers(other: VideoContentRect, tolerance: Float = 0.004f): Boolean =
        left <= other.left + tolerance &&
            top <= other.top + tolerance &&
            right >= other.right - tolerance &&
            bottom >= other.bottom - tolerance

    fun isCloseTo(other: VideoContentRect, tolerance: Float = 0.004f): Boolean =
        abs(left - other.left) <= tolerance &&
            abs(top - other.top) <= tolerance &&
            abs(right - other.right) <= tolerance &&
            abs(bottom - other.bottom) <= tolerance

    companion object {
        val Full = VideoContentRect(0f, 0f, 1f, 1f)
    }
}

/**
 * mpv `video-crop` value for a picture area, in source pixels ([sourceWidth] x [sourceHeight]
 * from `video-params/w` and `video-params/h`). Used with `keepaspect=no`: mpv drops the bars
 * and stretches the rest over the window. mpv ignores video-zoom, video-scale and video-pan
 * when keepaspect is off, so cropping is the only way to do this inside mpv. The edges are
 * rounded outward, so no picture row or column is lost. Returns "" (no crop) for the whole
 * frame or unknown sizes.
 */
internal fun VideoContentRect?.toMpvVideoCrop(sourceWidth: Int, sourceHeight: Int): String {
    if (this == null || sourceWidth <= 0 || sourceHeight <= 0) return ""
    val x0 = floor(left * sourceWidth).toInt().coerceIn(0, sourceWidth - 1)
    val y0 = floor(top * sourceHeight).toInt().coerceIn(0, sourceHeight - 1)
    val x1 = ceil(right * sourceWidth).toInt().coerceIn(x0 + 1, sourceWidth)
    val y1 = ceil(bottom * sourceHeight).toInt().coerceIn(y0 + 1, sourceHeight)
    if (x0 == 0 && y0 == 0 && x1 == sourceWidth && y1 == sourceHeight) return ""
    return "${x1 - x0}x${y1 - y0}+$x0+$y0"
}

/**
 * Map a picture area measured on a cropped image (only [crop] of the frame, stretched to the
 * whole image) back to frame fractions.
 */
internal fun VideoContentRect.mapFromCrop(crop: VideoContentRect?): VideoContentRect {
    if (crop == null) return this
    return VideoContentRect(
        left = (crop.left + left * crop.width).coerceIn(0f, 1f),
        top = (crop.top + top * crop.height).coerceIn(0f, 1f),
        right = (crop.left + right * crop.width).coerceIn(0f, 1f),
        bottom = (crop.top + bottom * crop.height).coerceIn(0f, 1f),
    )
}

/**
 * One frame's measurement: the picture area plus, per edge (left, top, right, bottom), whether
 * that edge can be trusted. A bar edge is trusted only when a near-black bar meets clearly
 * brighter picture; a dark scene or a vignette fading to black is not trusted.
 */
internal class BarSample(
    val rect: VideoContentRect,
    val confident: BooleanArray,
    /** Coarse 4x4 grid of mean luma, to tell whether two samples show different frames. */
    val signature: IntArray = IntArray(0),
    /** True when this sample was measured on an mpv crop region and mapped back to the frame. */
    val viaCrop: Boolean = false,
    /** Width / height of the whole source frame (0 = unknown), to judge whether a crop is plausible. */
    val aspect: Float = 0f,
) {
    /** Thickness of the bar at each edge (left, top, right, bottom) as frame fractions. */
    val thickness: FloatArray
        get() = floatArrayOf(rect.left, rect.top, 1f - rect.right, 1f - rect.bottom)

    /**
     * Bars are centered, so use the thicker bar (and its confidence) on both sides of each axis.
     * For captures that include rendered subtitles: a subtitle inside the bottom bar must not
     * count as picture.
     */
    fun symmetric(): BarSample {
        val t = thickness
        val horizontalLeft = t[0] >= t[2]
        val verticalTop = t[1] >= t[3]
        val horizontal = if (horizontalLeft) t[0] else t[2]
        val vertical = if (verticalTop) t[1] else t[3]
        val horizontalConfident = if (horizontalLeft) confident[0] else confident[2]
        val verticalConfident = if (verticalTop) confident[1] else confident[3]
        return BarSample(
            VideoContentRect(horizontal, vertical, 1f - horizontal, 1f - vertical),
            booleanArrayOf(horizontalConfident, verticalConfident, horizontalConfident, verticalConfident),
            signature,
            viaCrop,
            aspect,
        )
    }

    /** See [VideoContentRect.mapFromCrop]. */
    fun mapFromCrop(crop: VideoContentRect?): BarSample =
        BarSample(
            rect.mapFromCrop(crop),
            confident,
            signature,
            viaCrop = crop != null,
            // The image showed only [crop]: the whole frame is wider or taller by the crop's shape.
            aspect = if (crop != null && aspect > 0f) aspect * crop.height / crop.width else aspect,
        )

    /** True when [other] shows a clearly different frame (not the same paused or static shot). */
    fun differsFrom(other: IntArray?, minDifference: Int = SIGNATURE_MIN_DIFFERENCE): Boolean {
        if (other == null || other.size != signature.size || signature.isEmpty()) return true
        var difference = 0
        for (i in signature.indices) difference += abs(signature[i] - other[i])
        return difference >= minDifference
    }

    companion object {
        private const val SIGNATURE_MIN_DIFFERENCE = 64
    }
}

/** A downscaled luma (0..255) picture of one video frame. */
internal class LumaFrame(
    val luma: ByteArray,
    val width: Int,
    val height: Int,
    /** True when the image is only the current mpv video-crop region, not the whole frame. */
    val cropped: Boolean = false,
) {
    fun detectContent(): BarSample? = BlackBarDetector.detect(luma, width, height)

    companion object {
        /**
         * Parse a native sample: width and height as little-endian UInt16, a cropped flag byte,
         * then width * height luma bytes.
         */
        fun fromNative(raw: ByteArray): LumaFrame? {
            if (raw.size <= HEADER_BYTES) return null
            val width = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
            val height = (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
            if (width <= 0 || height <= 0 || raw.size < HEADER_BYTES + width * height) return null
            return LumaFrame(
                luma = raw.copyOfRange(HEADER_BYTES, HEADER_BYTES + width * height),
                width = width,
                height = height,
                cropped = raw[4].toInt() != 0,
            )
        }

        const val HEADER_BYTES = 5
    }
}

internal object BlackBarDetector {
    /** Luma (0..255) above this value counts as picture. Limited-range black is 16. */
    private const val LUMA_LIMIT = 30

    /** A row or column holds picture when this fraction of its pixels is above [LUMA_LIMIT]. */
    private const val LINE_CONTENT_FRACTION = 0.02f

    /** Bars thinner than this fraction of the frame are ignored (no visible gain, avoids jitter). */
    private const val MIN_BAR_FRACTION = 0.006f

    /** A sample whose picture is smaller than this is a dark scene or a logo, not the picture area. */
    private const val MIN_CONTENT_WIDTH = 0.45f
    private const val MIN_CONTENT_HEIGHT = 0.35f

    /** A trusted bar is near black (baked bars are flat black; dark picture is noisier) ... */
    private const val BAR_MAX_MEAN = 12
    /** Bar mean including the corners (a logo there is allowed, picture is not). */
    private const val BAR_MAX_MEAN_WITH_CORNERS = 24
    /**
     * ... and the picture lines right next to it are clearly brighter (a sharp edge). The brightest
     * of the [EDGE_BAND_LINES] lines next to the bar counts, so a soft (scaled or blurred) edge row
     * between bar and picture does not hide the edge.
     */
    private const val EDGE_MIN_CONTRAST = 24
    /** At most this fraction of a bar may be brighter than [BAR_FLAT_LUMA] (hardsubs fit, picture does not). */
    private const val BAR_FLAT_LUMA = 26
    private const val BAR_MAX_LIT_FRACTION = 0.12f
    private const val EDGE_BAND_LINES = 3

    /**
     * Bars are found in the middle of each line only (this fraction is skipped at both ends).
     * Site logos and watermarks sit in the corners, often half inside a bar; they must not count
     * as picture. Text in the middle of a bar (hardsubs) still keeps that bar.
     */
    private const val CORNER_FRACTION = 0.2f

    /**
     * Measure the picture area in one luma sample ([width] x [height], row stride [stride]).
     * Returns null when the sample is too dark to tell (black frame, fade, dark scene).
     */
    fun detect(luma: ByteArray, width: Int, height: Int, stride: Int = width): BarSample? {
        if (width < 8 || height < 8 || luma.size < stride * (height - 1) + width) return null

        val rowThreshold = max(2, (width * LINE_CONTENT_FRACTION).toInt())
        val middleLeft = (width * CORNER_FRACTION).toInt()
        val middleRight = width - middleLeft
        var top = -1
        var bottom = -1
        for (y in 0 until height) {
            if (countBright(luma, y * stride + middleLeft, 1, middleRight - middleLeft) >= rowThreshold) {
                if (top < 0) top = y
                bottom = y
            }
        }
        if (top < 0) return null

        val rows = bottom - top + 1
        val columnThreshold = max(2, (rows * LINE_CONTENT_FRACTION).toInt())
        val middleTop = top + (rows * CORNER_FRACTION).toInt()
        val middleRows = rows - 2 * (rows * CORNER_FRACTION).toInt()
        var left = -1
        var right = -1
        for (x in 0 until width) {
            if (countBright(luma, middleTop * stride + x, stride, middleRows) >= columnThreshold) {
                if (left < 0) left = x
                right = x
            }
        }
        if (left < 0) return null

        // Edges in sample pixels; a bar thinner than MIN_BAR_FRACTION counts as no bar.
        if (left < width * MIN_BAR_FRACTION) left = 0
        if (top < height * MIN_BAR_FRACTION) top = 0
        if (width - 1 - right < width * MIN_BAR_FRACTION) right = width - 1
        if (height - 1 - bottom < height * MIN_BAR_FRACTION) bottom = height - 1
        val pictureWidth = right - left + 1
        val pictureHeight = bottom - top + 1
        if (pictureWidth < width * MIN_CONTENT_WIDTH || pictureHeight < height * MIN_CONTENT_HEIGHT) return null

        // Bar means skip the corners (logos); edge lines use the whole picture width or height.
        val barLeft = max(left, middleLeft)
        val barRight = min(right + 1, middleRight)
        val barTop = max(top, middleTop)
        val barBottom = min(bottom + 1, middleTop + middleRows)
        fun rowMean(y: Int, x0: Int, x1: Int): Int {
            var sum = 0L
            for (x in x0 until x1) sum += luma[y * stride + x].toInt() and 0xFF
            return if (x1 <= x0) 0 else (sum / (x1 - x0)).toInt()
        }
        fun columnMean(x: Int, y0: Int, y1: Int): Int {
            var sum = 0L
            for (y in y0 until y1) sum += luma[y * stride + x].toInt() and 0xFF
            return if (y1 <= y0) 0 else (sum / (y1 - y0)).toInt()
        }
        fun rowsMean(from: Int, toExclusive: Int, x0: Int = barLeft, x1: Int = barRight): Int =
            if (toExclusive <= from) 0 else (from until toExclusive).sumOf { rowMean(it, x0, x1) } / (toExclusive - from)
        fun columnsMean(from: Int, toExclusive: Int, y0: Int = barTop, y1: Int = barBottom): Int =
            if (toExclusive <= from) 0 else (from until toExclusive).sumOf { columnMean(it, y0, y1) } / (toExclusive - from)
        fun rowBar(from: Int, toExclusive: Int): Int =
            if (rowsMean(from, toExclusive, left, right + 1) > BAR_MAX_MEAN_WITH_CORNERS) 255 else rowsMean(from, toExclusive)
        fun columnBar(from: Int, toExclusive: Int): Int =
            if (columnsMean(from, toExclusive, top, bottom + 1) > BAR_MAX_MEAN_WITH_CORNERS) 255 else columnsMean(from, toExclusive)
        fun brightestRow(from: Int, toExclusive: Int): Int =
            (from until toExclusive).maxOfOrNull { rowMean(it, left, right + 1) } ?: 0
        fun brightestColumn(from: Int, toExclusive: Int): Int =
            (from until toExclusive).maxOfOrNull { columnMean(it, top, bottom + 1) } ?: 0
        fun trusted(barMean: Int, edgeMean: Int) = barMean <= BAR_MAX_MEAN && edgeMean - barMean >= EDGE_MIN_CONTRAST
        // A baked bar is flat black. A dark but textured picture edge (shadow, vignette, dark
        // ornament) has mean luma near black too, but holds many pixels above BAR_FLAT_LUMA.
        fun litFraction(from: Int, toExclusive: Int, rows: Boolean): Float {
            var lit = 0L
            var total = 0L
            for (line in from until toExclusive) {
                if (rows) {
                    for (x in barLeft until barRight) {
                        if ((luma[line * stride + x].toInt() and 0xFF) > BAR_FLAT_LUMA) lit++
                        total++
                    }
                } else {
                    for (y in barTop until barBottom) {
                        if ((luma[y * stride + line].toInt() and 0xFF) > BAR_FLAT_LUMA) lit++
                        total++
                    }
                }
            }
            return if (total == 0L) 0f else lit.toFloat() / total
        }
        fun flat(from: Int, toExclusive: Int, rows: Boolean) = litFraction(from, toExclusive, rows) <= BAR_MAX_LIT_FRACTION

        val band = EDGE_BAND_LINES
        val confident = booleanArrayOf(
            left == 0 || (flat(0, left, false) &&
                trusted(columnBar(0, left), brightestColumn(left, min(right + 1, left + band)))),
            top == 0 || (flat(0, top, true) &&
                trusted(rowBar(0, top), brightestRow(top, min(bottom + 1, top + band)))),
            right == width - 1 || (flat(right + 1, width, false) &&
                trusted(columnBar(right + 1, width), brightestColumn(max(left, right + 1 - band), right + 1))),
            bottom == height - 1 || (flat(bottom + 1, height, true) &&
                trusted(rowBar(bottom + 1, height), brightestRow(max(top, bottom + 1 - band), bottom + 1))),
        )
        val rect = VideoContentRect(
            left = left.toFloat() / width,
            top = top.toFloat() / height,
            right = (right + 1).toFloat() / width,
            bottom = (bottom + 1).toFloat() / height,
        )
        return BarSample(rect, confident, signature(luma, width, height, stride), aspect = width.toFloat() / height)
    }

    private fun signature(luma: ByteArray, width: Int, height: Int, stride: Int): IntArray {
        val grid = 4
        val out = IntArray(grid * grid)
        for (gy in 0 until grid) {
            for (gx in 0 until grid) {
                var sum = 0L
                var count = 0
                for (y in gy * height / grid until (gy + 1) * height / grid step 2) {
                    for (x in gx * width / grid until (gx + 1) * width / grid step 2) {
                        sum += luma[y * stride + x].toInt() and 0xFF
                        count++
                    }
                }
                out[gy * grid + gx] = if (count == 0) 0 else (sum / count).toInt()
            }
        }
        return out
    }

    /** Luma of packed 0xAARRGGBB pixels (Android Bitmap), BT.601 weights. */
    fun lumaFromArgb(pixels: IntArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        for (i in 0 until min(pixels.size, out.size)) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
        }
        return out
    }

    private fun countBright(luma: ByteArray, start: Int, step: Int, count: Int): Int {
        var bright = 0
        var index = start
        repeat(count) {
            if ((luma[index].toInt() and 0xFF) > LUMA_LIMIT) bright++
            index += step
        }
        return bright
    }
}

/**
 * Picture areas confirmed for streams played earlier in this app run, so a stream that comes
 * back (resume, next try, another episode of the same file) starts stretched at once instead of
 * measuring again. Least recently used entries are dropped first.
 */
internal object CustomStretchMemory {
    private const val MAX_ENTRIES = 64
    private val entries = LinkedHashMap<String, VideoContentRect>()

    @Synchronized
    fun get(key: String?): VideoContentRect? {
        if (key.isNullOrBlank()) return null
        val found = entries.remove(key) ?: return null
        entries[key] = found
        return found
    }

    @Synchronized
    fun put(key: String?, rect: VideoContentRect) {
        if (key.isNullOrBlank()) return
        entries.remove(key)
        entries[key] = rect
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }
}

/**
 * Collects samples for one title, for the whole time it plays, and decides when the stretch
 * geometry changes.
 *
 * Each edge keeps its last [WINDOW] trusted bar measurements and uses the thinnest of them (so a
 * dark scene or a vignette can never make the picture smaller). An edge is cropped only after
 * [CONFIRMATIONS] trusted samples from different frames. Per axis the thinner side wins, because
 * baked-in bars are centered. Bars on both axes (windowbox) are kept only when each is clearly
 * there: a thin second axis is a dark or vignetted frame edge, and cropping it would stretch the
 * other direction as well.
 *
 * Old measurements fall out of the window, so the geometry follows the title: bars that appear
 * later (a full-frame intro, then a letterboxed film), bars that change (mixed aspect ratios) and
 * bars that vanish (a wider scene). More picture is shown at once; less picture needs the same
 * result on [CHANGE_CONFIRMATIONS] checks in a row.
 */
internal class BlackBarTracker {
    private val recent = Array(4) { ArrayDeque<Float>() }
    private var lastEvidence: IntArray? = null
    /** Signatures of the different shots that gave evidence (see [MIN_DISTINCT_SHOTS]). */
    private val shotSignatures = ArrayDeque<IntArray>()
    /** Frames that counted as evidence (distinct shots, or a still shot seen long enough). */
    private var samples = 0
    /** Every usable sample taken, including repeats of the same shot. */
    private var attempts = 0
    /** Every check made, including those without a usable sample (dark scene, capture failed). */
    private var polls = 0
    private var staticRepeats = 0
    private var committed: VideoContentRect? = null
    private var pending: VideoContentRect? = null
    private var pendingCount = 0
    private var lastViaCrop = false
    private var sinceFullView = 0
    /** Checks in a row that confirmed the committed picture area. Spaces the checks out. */
    private var stableChecks = 0

    /**
     * Sample faster until every edge is confirmed (or enough samples were taken), and while a
     * change of the picture area waits for its second check. Repeats of one still shot (a title
     * card, a paused start) use up attempts, not the evidence budget.
     */
    val isCalibrating: Boolean
        get() = pending != null ||
            (polls < MAX_CALIBRATION_POLLS &&
                (committed == null || (attempts < MAX_CALIBRATION_ATTEMPTS && recent.any { it.size < CONFIRMATIONS })))

    val current: VideoContentRect? get() = committed

    /** Delay before the next check: fast while unsure, then slower the longer the answer holds. */
    val nextIntervalMs: Long
        get() = when {
            isCalibrating -> CALIBRATION_INTERVAL_MS
            stableChecks >= LONG_STABLE_CHECKS -> LONG_STABLE_INTERVAL_MS
            stableChecks >= SHORT_STABLE_CHECKS -> MID_STABLE_INTERVAL_MS
            else -> STEADY_INTERVAL_MS
        }

    /**
     * Start from a picture area confirmed earlier for the same stream: no calibration, no
     * unstretched start. The checks go on, so a wrong or changed answer is still corrected (more
     * picture at once, less picture after two matching checks).
     */
    fun seed(rect: VideoContentRect) {
        reset()
        committed = rect
        val t = floatArrayOf(rect.left, rect.top, 1f - rect.right, 1f - rect.bottom)
        for (edge in 0 until 4) repeat(CONFIRMATIONS) { recent[edge].addLast(t[edge].coerceAtLeast(0f)) }
        samples = CALIBRATION_SAMPLES
        attempts = MAX_CALIBRATION_ATTEMPTS
        polls = MAX_CALIBRATION_POLLS
        sinceFullView = PEEK_AFTER_SAMPLES - SEEDED_PEEK_AFTER
    }

    /**
     * True when the last samples only saw the cropped picture (mpv): a wider scene would stay
     * hidden outside the crop. The caller then shows the whole frame for one sample and calls
     * [notePeek].
     */
    val shouldPeek: Boolean
        get() = PEEK_ENABLED && lastViaCrop && sinceFullView >= PEEK_AFTER_SAMPLES &&
            committed?.let { !it.isCloseTo(VideoContentRect.Full) } == true

    fun notePeek() {
        sinceFullView = 0
    }

    fun reset() {
        recent.forEach { it.clear() }
        shotSignatures.clear()
        lastEvidence = null
        samples = 0
        attempts = 0
        polls = 0
        staticRepeats = 0
        committed = null
        pending = null
        pendingCount = 0
        lastViaCrop = false
        sinceFullView = 0
        stableChecks = 0
    }

    /** Feed one sample. Returns the new picture area when it changed, else null. */
    fun offer(sample: BarSample?): VideoContentRect? {
        polls++
        if (sample == null) return null
        attempts++
        lastViaCrop = sample.viaCrop
        if (sample.viaCrop) sinceFullView++ else sinceFullView = 0
        // A dark scene must not confirm itself, so a repeat of the same shot only counts after it
        // was seen STATIC_CONFIRM_REPEATS more times. Bars are only trusted with a sharp edge, so a
        // still frame that shows them (intro card, paused start) is real evidence.
        if (sample.differsFrom(lastEvidence)) {
            lastEvidence = sample.signature
            staticRepeats = 0
        } else {
            staticRepeats++
            if (staticRepeats < STATIC_CONFIRM_REPEATS) return null
            staticRepeats = 0
        }
        // Letterbox and pillarbox bars never take most of a frame. A "picture" that small is a
        // logo on black, a title card or a dark scene, and cropping to it would hide the film.
        if (sample.rect.width * sample.rect.height < MIN_PICTURE_AREA) return null
        samples++
        if (shotSignatures.none { !sample.differsFrom(it, SHOT_CHANGE_DIFFERENCE) }) {
            shotSignatures.addLast(sample.signature)
            while (shotSignatures.size > WINDOW) shotSignatures.removeFirst()
        }
        val measured = sample.thickness
        for (edge in 0 until 4) {
            if (!sample.confident[edge]) continue
            recent[edge].addLast(measured[edge].coerceAtLeast(0f))
            while (recent[edge].size > WINDOW) recent[edge].removeFirst()
        }
        if (samples < CALIBRATION_SAMPLES) return null
        // A dark scene stays the same for seconds, so three samples of it agree. Baked bars stay
        // the same in every shot: only evidence from different shots may crop.
        val enoughShots = shotSignatures.size >= MIN_DISTINCT_SHOTS
        val edge = FloatArray(4) { if (enoughShots) steadyBar(recent[it]) else UNSURE }
        // Baked bars are centred: two sides of one axis that disagree are not a bar.
        if (edge[0] >= 0f && edge[2] >= 0f && abs(edge[0] - edge[2]) > MAX_BAR_ASYMMETRY) { edge[0] = UNSURE; edge[2] = UNSURE }
        if (edge[1] >= 0f && edge[3] >= 0f && abs(edge[1] - edge[3]) > MAX_BAR_ASYMMETRY) { edge[1] = UNSURE; edge[3] = UNSURE }
        // No clear answer for an edge keeps what is committed: missing or mixed evidence must never
        // switch the stretch off and on again. Only trusted, agreeing measurements change it.
        val held = committed
        for (i in 0 until 4) if (edge[i] < 0f) {
            val bar = held?.let { floatArrayOf(it.left, it.top, 1f - it.right, 1f - it.bottom)[i] } ?: 0f
            edge[i] = if (bar > 0f) bar + EDGE_MARGIN else 0f
        }
        var horizontal = min(edge[0], edge[2])
        var vertical = min(edge[1], edge[3])
        if (horizontal > 0f && vertical > 0f) {
            if (horizontal < SECOND_AXIS_MIN_BAR) horizontal = 0f
            if (vertical < SECOND_AXIS_MIN_BAR) vertical = 0f
        }
        // Keep a hair of the bar: the edge pixel of a scaled frame is a blend of bar and picture.
        if (horizontal > EDGE_MARGIN) horizontal -= EDGE_MARGIN
        if (vertical > EDGE_MARGIN) vertical -= EDGE_MARGIN
        val rect = VideoContentRect(horizontal, vertical, 1f - horizontal, 1f - vertical)
        // A picture wider than any film format (or narrower than a 4:3 one) that the frame itself
        // is not: the crop ate real picture, for example a dark top of the scene.
        if (!plausible(sample.aspect, rect)) return null
        val previous = committed
        if (previous != null && previous.isCloseTo(rect)) {
            pending = null
            pendingCount = 0
            stableChecks++
            return null
        }
        stableChecks = 0
        if (previous != null) {
            // Any change of the picture area (more or less picture) waits for the same answer on
            // the next check, so one odd frame cannot flash the black bars or a crop.
            val waiting = pending
            if (waiting != null && waiting.isCloseTo(rect)) pendingCount++ else {
                pending = rect
                pendingCount = 1
            }
            if (pendingCount < CHANGE_CONFIRMATIONS) return null
        }
        pending = null
        pendingCount = 0
        committed = rect
        return rect
    }

    /**
     * Bar thickness for one edge: the thinnest recent trusted measurement, but only when the recent
     * ones agree. A baked bar has the same thickness in every shot; a dark scene, a night sky or a
     * vignette gives a different "bar" each time, and a crop made from it would hide picture.
     * Returns [UNSURE] when there is no clear answer.
     */
    private fun steadyBar(values: ArrayDeque<Float>): Float {
        if (values.size < CONFIRMATIONS) return UNSURE
        val smallest = values.min()
        if (values.max() - smallest > MAX_BAR_SPREAD || smallest > MAX_BAR_THICKNESS) return UNSURE
        return smallest
    }

    private fun plausible(sourceAspect: Float, rect: VideoContentRect): Boolean {
        if (sourceAspect <= 0f || rect.height <= 0f) return true
        val picture = sourceAspect * rect.width / rect.height
        if (picture > MAX_PICTURE_ASPECT && picture > sourceAspect + ASPECT_SLACK) return false
        if (picture < MIN_PICTURE_ASPECT && picture < sourceAspect - ASPECT_SLACK) return false
        return true
    }

    companion object {
        /** Marks an edge without a clear measurement (see steadyBar). */
        private const val UNSURE = -1f
        const val CALIBRATION_SAMPLES = 4
        /** Samples whose signatures differ by at least this much (sum over the 4x4 grid) show different shots. */
        const val SHOT_CHANGE_DIFFERENCE = 200
        const val MIN_DISTINCT_SHOTS = 2
        /** Widest film format is about 2.39:1 (a few reach 2.76); narrowest is 4:3. */
        const val MAX_PICTURE_ASPECT = 2.6f
        const val MIN_PICTURE_ASPECT = 1.2f
        const val ASPECT_SLACK = 0.08f
        const val CONFIRMATIONS = 3
        /** Largest difference between recent measurements of one bar (frame fraction). */
        const val MAX_BAR_SPREAD = 0.012f
        /** Largest difference between the two bars of one axis. */
        const val MAX_BAR_ASYMMETRY = 0.03f
        /** Thickest bar one side may have (2.39:1 inside 4:3 is about 0.33). */
        const val MAX_BAR_THICKNESS = 0.3f
        /** Trusted measurements kept per edge. Older ones are forgotten, so the picture area can change. */
        const val WINDOW = 4
        const val CHANGE_CONFIRMATIONS = 2
        const val MAX_CALIBRATION_ATTEMPTS = 12
        const val MAX_CALIBRATION_POLLS = 30
        const val STATIC_CONFIRM_REPEATS = 2
        const val SECOND_AXIS_MIN_BAR = 0.03f
        const val CALIBRATION_INTERVAL_MS = 1_200L
        /** Checks continue at this pace until the title ends. */
        const val STEADY_INTERVAL_MS = 3_000L
        /** Once the answer held for [SHORT_STABLE_CHECKS] / [LONG_STABLE_CHECKS] checks, look less often. */
        const val SHORT_STABLE_CHECKS = 10
        const val LONG_STABLE_CHECKS = 40
        const val MID_STABLE_INTERVAL_MS = 6_000L
        const val LONG_STABLE_INTERVAL_MS = 12_000L
        const val EDGE_MARGIN = 0.003f
        /** A seeded mpv stream is checked against the whole frame after this many cropped samples. */
        const val SEEDED_PEEK_AFTER = 3
        const val FIRST_SAMPLE_DELAY_MS = 800L
        /**
         * Looking at the whole frame means un-cropping it for a moment, which shows as a flash of
         * black bars. Off: the crop only ever follows what the cropped frame itself shows.
         */
        const val PEEK_ENABLED = false
        /** mpv only: show the whole frame for one sample after this many cropped samples (about 40 s). */
        const val PEEK_AFTER_SAMPLES = 13
        /** Smallest share of the frame a measured picture area may cover to be trusted. */
        const val MIN_PICTURE_AREA = 0.5f
        const val PEEK_SETTLE_MS = 220L

        /** Longest side of the sampled frame. Enough to place bar edges within ~0.3%. */
        const val SAMPLE_MAX_DIMENSION = 320
    }
}

internal fun Bitmap.toLumaFrame(): LumaFrame {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return LumaFrame(BlackBarDetector.lumaFromArgb(pixels, width, height), width, height)
}

/**
 * Capture what a video surface shows (SurfaceView through PixelCopy, TextureView through
 * getBitmap). View transforms (scale, translation) are not part of the capture.
 * Call on the main thread; [onResult] runs on the main thread.
 */
internal fun View.captureVideoLuma(
    maxDimension: Int = BlackBarTracker.SAMPLE_MAX_DIMENSION,
    onResult: (LumaFrame?) -> Unit,
) {
    if (!isAttachedToWindow || width <= 0 || height <= 0) {
        onResult(null)
        return
    }
    val scale = maxDimension.toFloat() / max(width, height)
    val captureWidth = (width * scale).roundToInt().coerceAtLeast(8)
    val captureHeight = (height * scale).roundToInt().coerceAtLeast(8)
    when (this) {
        is TextureView -> {
            val bitmap = if (isAvailable) runCatching { getBitmap(captureWidth, captureHeight) }.getOrNull() else null
            onResult(bitmap?.toLumaFrame())
            bitmap?.recycle()
        }

        is SurfaceView -> {
            if (holder.surface?.isValid != true) {
                onResult(null)
                return
            }
            val bitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
            runCatching {
                PixelCopy.request(
                    this,
                    bitmap,
                    { result ->
                        val frame = if (result == PixelCopy.SUCCESS) bitmap.toLumaFrame() else null
                        bitmap.recycle()
                        onResult(frame)
                    },
                    Handler(Looper.getMainLooper()),
                )
            }.onFailure {
                bitmap.recycle()
                onResult(null)
            }
        }

        else -> onResult(null)
    }
}

internal suspend fun View.captureVideoLuma(
    maxDimension: Int = BlackBarTracker.SAMPLE_MAX_DIMENSION,
): LumaFrame? = suspendCancellableCoroutine { continuation ->
    captureVideoLuma(maxDimension) { frame ->
        if (continuation.isActive) continuation.resume(frame)
    }
}
