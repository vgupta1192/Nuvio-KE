package com.nuvio.tv.core.update

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The update the latest release offers this TV (Spec 52 P6): one APK for one ABI, named
 * `NuvioKE-<versionName>-<versionCode>-<abi>.apk` by the release step (Spec 49 R6), with the
 * digest GitHub computed on upload. Stored as JSON (UpdateStore), so fields only ever get added.
 */
@Serializable
data class KnownRelease(
    val versionName: String,
    val versionCode: Int,
    val abi: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** Lowercase hex. */
    val sha256: String,
    /** The release body as plain text, capped at [MAX_NOTES_CHARS]. */
    val notes: String,
    /** ISO 8601, as GitHub sends it. */
    val publishedAt: String? = null,
    /** Spec 80 D2: the release's video ([releaseVideo]'s rules) and its poster, for the changelog page the panel opens. */
    val videoUrl: String? = null,
    val videoSizeBytes: Long = 0L,
    val posterUrl: String? = null,
    /** Fork builds: the CI run that produced the APK (0 = plain naming, upstream releases). */
    val forkBuild: Int = 0
)

/** What an asset name parsed to: [versionName] may itself carry hyphens ("0.9.0-ke51"). */
internal data class ParsedAssetName(
    val versionName: String,
    val versionCode: Int,
    val forkBuild: Int,
    val abi: String,
)

private val NAME_B_RUN = Regex("""^(.+)-b(\d+)$""")

/**
 * Parses the two release asset namings: upstream's `NuvioKE-<versionName>-<versionCode>-<abi>.apk`
 * and the fork CI's `NuvioKE-<versionName>-<versionCode>-b<run>-<abi>.apk`. Parsed from the right
 * (abi suffix, optional `-b<run>`, then the trailing versionCode digits), so a hyphenated
 * versionName like "0.9.0-ke51" splits unambiguously.
 */
internal fun parseAssetName(name: String): ParsedAssetName? {
    if (!name.startsWith("NuvioKE-") || !name.endsWith(".apk")) return null
    val middle = name.removePrefix("NuvioKE-").removeSuffix(".apk")
    val abi = when {
        middle.endsWith("-arm64-v8a") -> "arm64-v8a"
        middle.endsWith("-armeabi-v7a") -> "armeabi-v7a"
        else -> return null
    }
    var body = middle.removeSuffix("-$abi")
    var forkBuild = 0
    NAME_B_RUN.matchEntire(body)?.let {
        forkBuild = it.groupValues[2].toIntOrNull() ?: return null
        body = it.groupValues[1]
    }
    val lastDash = body.lastIndexOf('-')
    if (lastDash <= 0) return null
    val versionCode = body.substring(lastDash + 1).toIntOrNull() ?: return null
    if (versionCode <= 0) return null
    val versionName = body.substring(0, lastDash)
    if (versionName.isBlank()) return null
    return ParsedAssetName(versionName, versionCode, forkBuild, abi)
}
private val SHA256_DIGEST = Regex("""^sha256:([0-9a-fA-F]{64})$""")
internal const val MAX_ASSET_BYTES = 200L * 1024 * 1024
internal const val MAX_NOTES_CHARS = 8_000

/**
 * [release]'s APK for the first of [supportedAbis] (Build.SUPPORTED_ABIS order; Shield's 32-bit OS
 * lists armeabi-v7a first) it ships, else null.
 * An asset counts only when its name parses, GitHub finished storing it, its size is 1 B to
 * 200 MB, its digest is a SHA-256 and its URL is https: the download checks all of them.
 */
fun releaseOffer(release: GitHubReleaseDto, supportedAbis: List<String>): KnownRelease? {
    val offers = release.assets.mapNotNull { assetOffer(it, release) }
    return supportedAbis.firstNotNullOfOrNull { abi ->
        offers.filter { it.abi == abi }.maxWithOrNull(compareBy({ it.versionCode }, { it.forkBuild }))
    }
}

private fun assetOffer(asset: GitHubAssetDto, release: GitHubReleaseDto): KnownRelease? {
    val video = releaseVideoOf(release)
    val parsed = parseAssetName(asset.name ?: return null) ?: return null
    if (asset.state != "uploaded") return null
    val size = asset.size?.takeIf { it in 1..MAX_ASSET_BYTES } ?: return null
    val sha256 = SHA256_DIGEST.matchEntire(asset.digest ?: return null)?.groupValues?.get(1) ?: return null
    val url = asset.browserDownloadUrl?.takeIf { it.toHttpUrlOrNull()?.isHttps == true } ?: return null
    return KnownRelease(
        versionName = parsed.versionName,
        versionCode = parsed.versionCode,
        abi = parsed.abi,
        downloadUrl = url,
        sizeBytes = size,
        sha256 = sha256.lowercase(),
        notes = release.body.orEmpty().take(MAX_NOTES_CHARS),
        publishedAt = release.publishedAt,
        videoUrl = video?.url,
        videoSizeBytes = video?.sizeBytes ?: 0L,
        posterUrl = video?.posterUrl,
        forkBuild = parsed.forkBuild
    )
}

/** What one check learned. Only [Unreached] leaves no attempt behind (P7: no answer, retry next start). */
sealed interface CheckOutcome {
    /** 200: the latest release's offer for this TV, null when it has none. */
    data class Answered(val offer: KnownRelease?) : CheckOutcome

    /** 404: no release yet (or no such repo); any known release is dropped. */
    data object NoRelease : CheckOutcome

    /** Any other status, or a body that did not decode. */
    data object Failed : CheckOutcome

    /** No HTTP answer (no network, DNS, timeout). */
    data object Unreached : CheckOutcome
}

/** The outcome of an HTTP answer with [code] and the decoded [body]. */
fun checkOutcome(code: Int, body: GitHubReleaseDto?, supportedAbis: List<String>): CheckOutcome = when {
    code == 200 && body != null -> CheckOutcome.Answered(releaseOffer(body, supportedAbis))
    code == 404 -> CheckOutcome.NoRelease
    else -> CheckOutcome.Failed
}

internal const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

/** At most once a day; a last attempt in the future (the clock was set back after boot) is due. */
fun autoCheckDue(nowMs: Long, lastAttemptMs: Long): Boolean =
    lastAttemptMs <= 0L || lastAttemptMs > nowMs || nowMs - lastAttemptMs >= AUTO_CHECK_INTERVAL_MS

/**
 * Only a PUBLIC build checks (P6): the one made without a TMDB key, of the release build type
 * (IS_DEBUG_BUILD false), under the KE id. A private household build carries the key, so it is never
 * offered a keyless public APK; debug and benchmark (`.debug`) builds never check. A release build
 * signed with the debug key (CI_USE_DEBUG_SIGNING) does check; Android refuses its update (another
 * signer).
 *
 * Fork builds check regardless of the TMDB key: their feed is the fork's own releases
 * (UpdateFeed points at the fork repo), so a key-carrying household build is still offered the
 * fork CI's next build (keyed by FORK_BUILD, see [KnownRelease.forkBuild]).
 */
fun updateCheckSupported(builtInTmdbKey: String, debugBuild: Boolean, applicationId: String, forkBuild: Int = 0): Boolean =
    !debugBuild && applicationId == "com.nuvio.tv.ke" &&
        (builtInTmdbKey.isBlank() || forkBuild > 0)
