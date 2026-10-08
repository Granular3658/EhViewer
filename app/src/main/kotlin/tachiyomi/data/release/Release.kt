package tachiyomi.data.release

import com.hippo.ehviewer.util.AppConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Release(
    @SerialName("tag_name") val version: String,
    @SerialName("body") val info: String,
    @SerialName("html_url") val releaseLink: String,
    @SerialName("assets") val assets: List<Assets>,
) {
    fun getDownloadLink(): String {
        val asset = assets.find { AppConfig.matchVariant(it.name) } ?: assets[0]
        return asset.url
    }
}

@Serializable
data class Assets(
    @SerialName("browser_download_url")
    val url: String,

    val name: String,
)
