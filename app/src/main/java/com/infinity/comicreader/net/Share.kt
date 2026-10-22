package com.infinity.comicreader.net

/**
 * A network share the user added: browsed as a storage root at [root] under [name].
 * [user] may name a domain (`DOMAIN\user`); an empty one signs in anonymously or as a guest.
 * [passwordLost] says the stored password can no longer be opened (the keystore was
 * reset, or the share came from another device): it is never signed in with until
 * entered again.
 */
data class Share(
    val id: String,
    val name: String,
    val path: SharePath,
    val user: String,
    val password: String,
    val passwordLost: Boolean = false
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
