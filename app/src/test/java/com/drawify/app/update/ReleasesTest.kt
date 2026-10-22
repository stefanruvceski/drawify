package com.drawify.app.update

import com.drawify.app.update.Releases.Asset
import com.drawify.app.update.Releases.GithubRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleasesTest {

    private fun apk(name: String = "drawify.apk") = Asset(name, "https://example.com/$name", 1234L)

    private fun release(
        tag: String,
        draft: Boolean = false,
        body: String = "",
        assets: List<Asset> = listOf(apk()),
    ) = GithubRelease(tag, draft, body, assets)

    @Test
    fun buildFromTag_parsesAndroidTags() {
        assertEquals(42, Releases.buildFromTag("android-b42"))
        assertEquals(1, Releases.buildFromTag("android-b1"))
        assertEquals(1000, Releases.buildFromTag("android-b1000"))
    }

    @Test
    fun buildFromTag_rejectsOtherTags() {
        assertNull(Releases.buildFromTag("v1.0.2"))
        assertNull(Releases.buildFromTag("ios-b42"))
        assertNull(Releases.buildFromTag("android-b"))
        assertNull(Releases.buildFromTag("android-b0"))
        assertNull(Releases.buildFromTag("android-b-3"))
        assertNull(Releases.buildFromTag("android-b4x"))
        assertNull(Releases.buildFromTag("android-b 4"))
        assertNull(Releases.buildFromTag("Android-b4"))
        assertNull(Releases.buildFromTag("android-b99999999999"))
    }

    @Test
    fun pickLatest_choosesHighestBuildRegardlessOfOrder() {
        val latest = Releases.pickLatest(
            listOf(release("android-b5"), release("android-b12"), release("android-b9"))
        )
        assertEquals(12, latest?.build)
        assertEquals("android-b12", latest?.tag)
        assertEquals("https://example.com/drawify.apk", latest?.apkUrl)
    }

    @Test
    fun pickLatest_skipsDrafts() {
        val latest = Releases.pickLatest(
            listOf(release("android-b20", draft = true), release("android-b7"))
        )
        assertEquals(7, latest?.build)
    }

    @Test
    fun pickLatest_skipsReleasesWithoutApk() {
        val latest = Releases.pickLatest(
            listOf(
                release("android-b30", assets = emptyList()),
                release("android-b29", assets = listOf(apk("notes.txt"))),
                release("android-b8"),
            )
        )
        assertEquals(8, latest?.build)
    }

    @Test
    fun pickLatest_skipsOtherTagPrefixes() {
        val latest = Releases.pickLatest(
            listOf(release("v1.0.99"), release("web-b50"), release("android-b3"))
        )
        assertEquals(3, latest?.build)
    }

    @Test
    fun pickLatest_returnsNullWhenNothingQualifies() {
        assertNull(Releases.pickLatest(emptyList()))
        assertNull(
            Releases.pickLatest(
                listOf(release("v1.0.2"), release("android-b4", draft = true), release("android-b5", assets = emptyList()))
            )
        )
    }

    @Test
    fun pickLatest_findsApkAmongOtherAssetsAndKeepsFirstNoteLine() {
        val latest = Releases.pickLatest(
            listOf(
                release(
                    "android-b6",
                    body = "\n  Add tilt correction  \nSecond line",
                    assets = listOf(apk("checksums.txt"), apk("Drawify.APK")),
                )
            )
        )
        assertEquals("https://example.com/Drawify.APK", latest?.apkUrl)
        assertEquals("Add tilt correction", latest?.notes)
    }

    @Test
    fun newerThan_comparesWithInstalledBuild() {
        val latest = Releases.pickLatest(listOf(release("android-b10")))
        assertEquals(10, Releases.newerThan(latest, 9)?.build)
        assertNull(Releases.newerThan(latest, 10))
        assertNull(Releases.newerThan(latest, 11))
        assertNull(Releases.newerThan(null, 1))
    }

    @Test
    fun parse_readsGithubResponse() {
        val json = """
            [
              {"tag_name": "android-b4", "draft": false, "body": null,
               "assets": [{"name": "drawify.apk", "browser_download_url": "https://x/4.apk", "size": 99}]},
              {"tag_name": "android-b5", "draft": true, "body": "wip", "assets": []},
              {"tag_name": "v1.0.2", "body": "old", "assets": [{"name": "drawify-1.0.2.apk", "browser_download_url": "https://x/old.apk"}]}
            ]
        """.trimIndent()
        val releases = Releases.parse(json)
        assertEquals(3, releases.size)
        assertEquals("", releases[0].body)
        assertEquals(99L, releases[0].assets[0].size)
        assertEquals(true, releases[1].draft)
        assertEquals(-1L, releases[2].assets[0].size)

        val latest = Releases.pickLatest(releases)
        assertEquals(4, latest?.build)
        assertEquals("https://x/4.apk", latest?.apkUrl)
    }
}
