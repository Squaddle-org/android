package org.squaddle.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.squaddle.app.BuildConfig
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * `github` flavor's real implementation (see `fdroid`/`play`'s own copies for why this class has
 * no shared interface in `main`). Manual only - [checkForUpdate] is only ever called from a
 * Settings-screen button tap, never on a timer/at startup, matching this app's existing
 * zero-background-phone-home posture everywhere else.
 *
 * `latest.json`'s schema (hosted at [MANIFEST_URL], updated by a PR the release workflow opens
 * in the `website` repo): `{"versionCode": int, "versionName": string, "downloadUrl": string,
 * "sha256": string (lowercase hex), "minSupportedVersionCode": int, "notes": {"en": string, "de":
 * string}}`.
 */
public class SelfUpdateChecker {
    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val body = URL(MANIFEST_URL).openStream().use { it.readBytes().decodeToString() }
            val manifest = json.decodeFromString<LatestManifest>(body)
            if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                UpdateCheckResult.UpToDate
            } else {
                val notes = if (Locale.getDefault().language == "de") manifest.notes.de else manifest.notes.en
                UpdateCheckResult.UpdateAvailable(
                    versionName = manifest.versionName,
                    notes = notes.ifBlank { manifest.notes.en },
                    downloadUrl = manifest.downloadUrl,
                    sha256 = manifest.sha256.lowercase(),
                )
            }
        } catch (e: Exception) {
            UpdateCheckResult.CheckFailed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** Downloads [update]'s APK into memory, verifies its SHA-256 against [update]'s own
     * manifest-declared digest BEFORE touching [PackageInstaller] at all (a hash mismatch never
     * reaches the installer), then commits a [PackageInstaller.Session] - the system's own
     * install-confirmation UI still appears, same as any other install; this never bypasses it
     * and isn't meant to. */
    public suspend fun downloadAndInstall(context: Context, update: UpdateCheckResult.UpdateAvailable): InstallOutcome = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = URL(update.downloadUrl).openStream().use { raw ->
                DigestInputStream(raw, digest).use { it.readBytes() }
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha256 != update.sha256) {
                return@withContext InstallOutcome.Failed("downloaded file does not match the expected checksum")
            }

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("update", 0, bytes.size.toLong()).use { out ->
                    out.write(bytes)
                    session.fsync(out)
                }
                // No completion callback is read here - commit() itself launches the system's
                // install-confirmation UI (PendingIntent with no extra flags needed for that),
                // and this screen has nothing further to do once that's handed off.
                val pendingIntent = PendingIntent.getBroadcast(
                    context, sessionId, Intent("org.squaddle.app.SELF_UPDATE_STATUS"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                session.commit(pendingIntent.intentSender)
            }
            InstallOutcome.Launched
        } catch (e: SecurityException) {
            InstallOutcome.Failed("missing install-unknown-apps permission - enable it for Squaddle in system settings")
        } catch (e: Exception) {
            InstallOutcome.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    public companion object {
        internal const val MANIFEST_URL: String = "https://squaddle.org/android/latest.json"
    }
}

@Serializable
private data class LatestManifest(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val minSupportedVersionCode: Int = 0,
    val notes: Notes = Notes(),
) {
    @Serializable
    data class Notes(val en: String = "", val de: String = "")
}
