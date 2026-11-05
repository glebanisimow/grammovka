package app.grammovka.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private fun json(
        url: String = "https://github.com/glebanisimow/grammovka/releases/download/android/grammovka-1000-77-1.apk",
        appId: String = "app.grammovka",
    ) = """
        {"versionCode":1000,"versionName":"0.1.0","applicationId":"$appId","minSdk":28,
         "apkUrl":"$url","sha256":"${"a".repeat(64)}","sizeBytes":123456}
    """.trimIndent()

    @Test
    fun parsesManifestWrittenByCi() {
        val m = UpdateManifest.parse(json())
        assertEquals(1000L, m.versionCode)
        assertTrue(m.isNewerThan(999))
    }

    @Test
    fun rejectsForeignPackageOrHost() {
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(json(appId = "app.other")) }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(json(url = "https://example.com/glebanisimow/grammovka/releases/download/android/grammovka-1-1-1.apk"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(json(url = "http://github.com/glebanisimow/grammovka/releases/download/android/grammovka-1-1-1.apk"))
        }
    }
}
