package app.grammovka.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest

/**
 * Written by CI (tools/build_update_manifest.py) to the public "android" GitHub release,
 * after the APK it points at has been uploaded. Every build has a unique APK name.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val applicationId: String,
    val minSdk: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    fun validate(): UpdateManifest = apply {
        require(versionCode > 0 && versionName.isNotBlank() && versionName.length <= 80)
        require(applicationId == APP_ID && minSdk >= 28)
        require(sizeBytes in 1..MAX_APK_BYTES)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        val uri = URI(apkUrl)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1)
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null)
        require(uri.rawPath.matches(Regex("/glebanisimow/grammovka/releases/download/android/grammovka-[0-9]+-[0-9]+-[0-9]+\\.apk")))
    }

    fun isNewerThan(installedCode: Long): Boolean = versionCode > installedCode

    companion object {
        const val APP_ID = "app.grammovka"
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        const val URL = "https://github.com/glebanisimow/grammovka/releases/download/android/update.json"
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): UpdateManifest = json.decodeFromString<UpdateManifest>(text).validate()
    }
}

internal fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
