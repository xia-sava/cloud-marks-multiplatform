package to.sava.cloudmarksmultiplatform.update

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateUrlTest {
    // 配布物の URL は、リポジトリ xia-sava/cloud-marks-multiplatform の、リリースのタグ latest の下を指す
    @Test
    fun releaseAssetUrlPointsAtLatestRelease() {
        assertEquals(
            "https://github.com/xia-sava/cloud-marks-multiplatform/releases/download/latest/cloudmarks-setup.exe",
            releaseAssetUrl(UpdatePlatform.DESKTOP.assetName),
        )
        assertEquals(
            "https://github.com/xia-sava/cloud-marks-multiplatform/releases/download/latest/cloudmarks.apk",
            releaseAssetUrl(UpdatePlatform.ANDROID.assetName),
        )
    }

    // 更新情報は latest.json、その署名は latest.json.sig で、配布物と同じ場所に置く
    @Test
    fun updateInfoUrlsAreBesideReleases() {
        assertEquals(
            "https://github.com/xia-sava/cloud-marks-multiplatform/releases/download/latest/latest.json",
            UPDATE_INFO_URL,
        )
        assertEquals(
            "https://github.com/xia-sava/cloud-marks-multiplatform/releases/download/latest/latest.json.sig",
            UPDATE_INFO_SIGNATURE_URL,
        )
    }

    // 配布物の名前は、デスクトップが cloudmarks-setup.exe、Android が cloudmarks.apk
    @Test
    fun assetNamesArePerPlatform() {
        assertEquals(
            mapOf(UpdatePlatform.DESKTOP to "cloudmarks-setup.exe", UpdatePlatform.ANDROID to "cloudmarks.apk"),
            UpdatePlatform.entries.associateWith { it.assetName },
        )
    }

    // 更新情報の項目のキーは、デスクトップが desktop、Android が android
    @Test
    fun updateInfoKeysArePerPlatform() {
        assertEquals(
            mapOf(UpdatePlatform.DESKTOP to "desktop", UpdatePlatform.ANDROID to "android"),
            UpdatePlatform.entries.associateWith { it.key },
        )
    }
}
