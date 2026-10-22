package com.drawify.app.update

import org.json.JSONArray

/**
 * Self-update for the sideloaded build.
 *
 * Every push to main builds a signed APK in GitHub Actions and publishes it as a GitHub Release
 * tagged `android-b<N>` (N = versionCode). The app reads the repo's releases, picks the newest
 * such release with an APK attached and offers it when N is higher than the installed build.
 *
 * This file holds the pure logic (no Android APIs) so it can be unit-tested on the JVM.
 */
object Releases {

    const val TAG_PREFIX = "android-b"

    data class Asset(val name: String, val url: String, val size: Long)

    data class GithubRelease(
        val tag: String,
        val draft: Boolean,
        val body: String,
        val assets: List<Asset>,
    )

    data class ReleaseInfo(
        val build: Int,
        val tag: String,
        val apkUrl: String,
        val apkSize: Long,
        val notes: String,
    )

    /** Build number from a tag like `android-b42`, or null for anything else. */
    fun buildFromTag(tag: String): Int? {
        if (!tag.startsWith(TAG_PREFIX)) return null
        val digits = tag.substring(TAG_PREFIX.length)
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        val n = digits.toIntOrNull() ?: return null
        return if (n > 0) n else null
    }

    /** The newest published Android release that has an APK attached, or null. */
    fun pickLatest(releases: List<GithubRelease>): ReleaseInfo? {
        var best: ReleaseInfo? = null
        for (r in releases) {
            if (r.draft) continue
            val build = buildFromTag(r.tag) ?: continue
            val apk = r.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: continue
            if (best == null || build > best.build) {
                best = ReleaseInfo(build, r.tag, apk.url, apk.size, firstLine(r.body))
            }
        }
        return best
    }

    /** [latest] if it is newer than the installed [installedBuild], otherwise null. */
    fun newerThan(latest: ReleaseInfo?, installedBuild: Long): ReleaseInfo? =
        latest?.takeIf { it.build > installedBuild }

    /** First non-blank line of the release notes, for a one-line summary. */
    fun firstLine(body: String): String =
        body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""

    /** Parses the GitHub `GET /repos/{owner}/{repo}/releases` response. */
    fun parse(json: String): List<GithubRelease> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val r = array.getJSONObject(i)
            val assetsJson = r.optJSONArray("assets")
            val assets = if (assetsJson == null) {
                emptyList()
            } else {
                (0 until assetsJson.length()).map { j ->
                    val a = assetsJson.getJSONObject(j)
                    Asset(
                        name = a.optString("name"),
                        url = a.optString("browser_download_url"),
                        size = a.optLong("size", -1L),
                    )
                }
            }
            GithubRelease(
                tag = r.optString("tag_name"),
                draft = r.optBoolean("draft", false),
                body = if (r.isNull("body")) "" else r.optString("body"),
                assets = assets,
            )
        }
    }
}
