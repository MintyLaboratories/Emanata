package com.infinity.comicreader.data

import org.json.JSONException
import org.json.JSONObject

/** The start folder of each location, by the location's root path, as stored ([Prefs.locationStart]). */
object LocationStarts {
    /** True when [folder] lies strictly below [root]. */
    fun isBelow(folder: String?, root: String): Boolean =
        folder != null && folder != root && StorageRoot(root, "", StorageRoot.Kind.DEVICE).contains(folder)

    /** The starts held in [json]; empty when it is missing or broken, and any start not below its root left out. */
    fun decode(json: String?): Map<String, String> {
        if (json.isNullOrEmpty()) return emptyMap()
        val o = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return emptyMap()
        }
        val out = HashMap<String, String>()
        o.keys().forEach { root -> o.optString(root).takeIf { isBelow(it, root) }?.let { out[root] = it } }
        return out
    }

    fun encode(starts: Map<String, String>): String = JSONObject(starts as Map<*, *>).toString()
}
