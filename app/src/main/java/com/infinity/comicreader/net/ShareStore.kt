package com.infinity.comicreader.net

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * The network shares the user added, in the order added, kept in their own
 * preferences file; passwords are sealed by [box] (the Android Keystore) and never
 * stored in the clear. Each change tells [Remotes], which drops what it held.
 */
class ShareStore(context: Context, private val box: SecretBox = defaultBox) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Every share; a password that cannot be opened any more (keystore reset) reads as empty and lost. */
    fun all(): List<Share> {
        val arr = try {
            JSONArray(sp.getString(KEY_LIST, "[]") ?: "[]")
        } catch (e: JSONException) {
            JSONArray()
        }
        val out = ArrayList<Share>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val path = SharePath(o.optString("host"), o.optInt("port", SharePath.DEFAULT_PORT), o.optString("share"), o.optString("dir"))
            val password = unseal(o.optString("secret"))
            val lost = password == null || o.optBoolean(KEY_LOST)
            out.add(Share(id, o.optString("name"), path, o.optString("user"), password.orEmpty(), passwordLost = lost))
        }
        return out
    }

    fun get(id: String): Share? = all().firstOrNull { it.id == id }

    /** Adds [share], or replaces the one with its id in place. */
    fun put(share: Share) {
        synchronized(LOCK) {
            val list = all().toMutableList()
            val at = list.indexOfFirst { it.id == share.id }
            if (at >= 0) list[at] = share else list.add(share)
            write(list)
        }
        Remotes.changed(share.id)
    }

    fun remove(id: String) {
        synchronized(LOCK) { write(all().filterNot { it.id == id }) }
        Remotes.changed(id)
    }

    private fun write(list: List<Share>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.id).put("name", s.name)
                    .put("host", s.path.host).put("port", s.path.port).put("share", s.path.share).put("dir", s.path.dir)
                    .put("user", s.user).put("secret", seal(s.password))
                    .apply { if (s.passwordLost) put(KEY_LOST, true) }
            )
        }
        sp.edit().putString(KEY_LIST, arr.toString()).commit()
    }

    private fun seal(password: String): String =
        if (password.isEmpty()) "" else Base64.encodeToString(box.seal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    /** The password [secret] seals; null when it cannot be opened. */
    private fun unseal(secret: String): String? =
        if (secret.isEmpty()) "" else runCatching { String(box.open(Base64.decode(secret, Base64.NO_WRAP)), Charsets.UTF_8) }.getOrNull()

    companion object {
        private const val FILE = "shares"
        private const val KEY_LIST = "list"
        private const val KEY_LOST = "lost"
        private val LOCK = Any()

        /** How passwords are sealed; tests stand in a key the JVM has. */
        @Volatile
        var defaultBox: SecretBox = AesGcmBox(KeystoreKey::get)

        /** A new id, unique among shares in practice: it names the share's root path, so it never changes. */
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(ID_CHARS)

        private const val ID_CHARS = 12
    }
}
