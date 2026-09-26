package app.depart

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer build and hands it to Android's installer. */
object Updater {
    fun check(): JSONObject {
        val out = JSONObject().put("available", false).put("current", BuildConfig.VERSION_NAME)
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return out.put("error", "This copy wasn't built on GitHub, so it can't look for updates.")
        return try {
            val j = JSONObject(Traffic.get(
                "https://api.github.com/repos/$repo/releases/latest",
                mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "Depart")
            ))
            val code = j.optString("tag_name").filter { it.isDigit() }.toIntOrNull() ?: 0
            var url = ""
            val assets = j.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
                }
            }
            out.put("available", code > BuildConfig.VERSION_CODE && url.isNotEmpty())
                .put("code", code).put("name", j.optString("name")).put("notes", j.optString("body")).put("url", url)
        } catch (e: Exception) {
            out.put("error", "Couldn't reach GitHub (${e.message}).")
        }
    }

    fun install(c: Context, url: String, progress: (JSONObject) -> Unit) {
        try {
            val dir = File(c.cacheDir, "updates").apply { mkdirs() }
            val f = File(dir, "depart.apk")
            val con = URL(url).openConnection() as HttpURLConnection
            con.instanceFollowRedirects = true
            con.setRequestProperty("User-Agent", "Depart")
            val total = con.contentLengthLong
            con.inputStream.use { input ->
                f.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last && pct % 5 == 0) { last = pct; progress(JSONObject().put("progress", pct)) }
                        }
                    }
                }
            }
            con.disconnect()
            val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
            c.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            progress(JSONObject().put("done", true))
        } catch (e: Exception) {
            progress(JSONObject().put("error", e.message ?: "Download failed"))
        }
    }
}
