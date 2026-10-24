package com.infinity.comicreader.net

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Network shares in a backup. Their passwords are sealed (AES-GCM) with a key derived
 * (PBKDF2) from the app's device ID, which stays the same after a reinstall on this
 * device and differs on any other: restored here they work again; restored elsewhere,
 * or exported without passwords, a share that had one comes back with it lost, so it is
 * asked for when the share is opened.
 */
object ShareBackup {
    private const val ITERATIONS = 120_000

    /** Most iterations a backup may ask for, so a crafted one cannot stall a restore. */
    private const val MAX_ITERATIONS = 1_000_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val CONTEXT = "emanata-share-passwords:"
    private val ID = Regex("[0-9A-Za-z]{1,32}")

    /** This app's device ID (Android's ANDROID_ID); null when the platform gives none. */
    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()?.takeIf { it.isNotBlank() }

    /** [shares] as a backup holds them; passwords sealed for [deviceId], or left out [withPasswords] false or without an ID. */
    fun export(shares: List<Share>, deviceId: String?, withPasswords: Boolean, random: SecureRandom = SecureRandom()): JSONObject {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val sealing = withPasswords && deviceId != null && shares.any { it.password.isNotEmpty() }
        val key = if (sealing) keyOf(deviceId!!, salt, ITERATIONS) else null
        val box = key?.let { k -> AesGcmBox { k } }
        val list = JSONArray()
        for (s in shares) {
            val o = JSONObject().put("id", s.id).put("name", s.name).put("user", s.user)
                .put("host", s.path.host).put("port", s.path.port).put("dir", s.path.dir).put("thumbnails", s.thumbnails)
            when (val p = s.path) {
                is SharePath -> o.put("kind", "smb").put("share", p.share)
                is DavUrl -> o.put("kind", "webdav").put("secure", p.secure)
            }
            val had = s.password.isNotEmpty() || s.passwordLost
            o.put("hasPassword", had)
            if (box != null && s.password.isNotEmpty()) {
                o.put("password", Base64.getEncoder().encodeToString(box.seal(s.password.toByteArray(Charsets.UTF_8), boundTo(s))))
            }
            list.put(o)
        }
        return JSONObject().put("salt", Base64.getEncoder().encodeToString(salt)).put("iterations", ITERATIONS).put("list", list)
    }

    /**
     * The shares [data] holds, passwords opened for [deviceId]; one that cannot be opened
     * (another device, or left out) comes back lost. Malformed entries are skipped.
     */
    fun import(data: JSONObject, deviceId: String?): List<Share> {
        val list = data.optJSONArray("list") ?: return emptyList()
        val salt = runCatching { Base64.getDecoder().decode(data.optString("salt")) }.getOrNull()
        val iterations = data.optInt("iterations", ITERATIONS).coerceIn(1, MAX_ITERATIONS)
        // Derived only when a password is there to open: it takes a moment.
        val box: AesGcmBox? by lazy {
            if (deviceId == null || salt == null || salt.isEmpty()) null else keyOf(deviceId, salt, iterations).let { k -> AesGcmBox { k } }
        }
        val out = ArrayList<Share>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { ID.matches(it) } ?: continue
            val host = o.optString("host").takeIf { it.isNotBlank() } ?: continue
            val dir = o.optString("dir")
            val path: ShareAddress = when (o.optString("kind")) {
                "smb" -> {
                    val port = o.optInt("port", SharePath.DEFAULT_PORT).takeIf { it in 1..65535 } ?: continue
                    SharePath(host, port, o.optString("share").takeIf { it.isNotBlank() } ?: continue, dir)
                }
                "webdav" -> {
                    val secure = o.optBoolean("secure", true)
                    if (!secure && !DavUrl.isPrivate(host)) continue
                    DavUrl(secure, host, o.optInt("port", DavUrl.defaultPort(secure)).takeIf { it in 1..65535 } ?: continue, dir)
                }
                else -> continue
            }
            val share = Share(id, o.optString("name").ifBlank { host }, path, o.optString("user"), "", thumbnails = o.optBoolean("thumbnails", true))
            val sealed = o.optString("password").takeIf { it.isNotEmpty() }
            // Opens only for the server and user it was sealed for: one edited in the file gets none.
            val password = sealed?.let { s ->
                runCatching { box?.open(Base64.getDecoder().decode(s), boundTo(share))?.let { String(it, Charsets.UTF_8) } }.getOrNull()
            }
            val had = o.optBoolean("hasPassword", sealed != null)
            out += share.copy(password = password.orEmpty(), passwordLost = had && password == null)
        }
        return out
    }

    /**
     * [restored] as it replaces [local], the share with its id here: a password that works
     * here for the same server and user stays, so neither a backup without passwords nor
     * an older one undoes it.
     */
    fun merged(restored: Share, local: Share?): Share {
        if (local == null || local.password.isEmpty() || local.passwordLost) return restored
        if (local.user != restored.user || ShareForm.serverOf(local.path) != ShareForm.serverOf(restored.path)) return restored
        return restored.copy(password = local.password, passwordLost = false)
    }

    /** What a sealed password is bound to: the share and the server and user it signs in to. */
    private fun boundTo(share: Share): ByteArray {
        val p = share.path
        val fields = listOf(
            share.id, if (p is DavUrl) "webdav" else "smb", p.host, p.port.toString(),
            (p as? SharePath)?.share.orEmpty(), share.user, ((p as? DavUrl)?.secure ?: false).toString()
        )
        return fields.joinToString("\u0000").toByteArray(Charsets.UTF_8)
    }

    private fun keyOf(deviceId: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec((CONTEXT + deviceId).toCharArray(), salt, iterations, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
