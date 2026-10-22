package com.infinity.comicreader.net

/**
 * A network share the user added: browsed as a storage root at [root] under [name].
 * [user] may name a domain (`DOMAIN\user`); an empty one signs in as a guest.
 */
data class Share(
    val id: String,
    val name: String,
    val path: SharePath,
    val user: String,
    val password: String
) {
    /** The share's top as the app's paths spell it. */
    val root: String get() = Remotes.rootOf(id)

    /** The domain [user] names before a `\`, or empty. */
    val domain: String get() = if ('\\' in user) user.substringBefore('\\') else ""

    /** [user] without its domain. */
    val account: String get() = user.substringAfter('\\')

    /** Hides the password from logs and crash reports. */
    override fun toString(): String = "Share(id=$id, name=$name, path=$path, user=$user)"
}
