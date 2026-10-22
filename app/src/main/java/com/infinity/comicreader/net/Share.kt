package com.infinity.comicreader.net

/**
 * A network share the user added: browsed as a storage root at [root] under [name],
 * over SMB or WebDAV as its [path] says. On SMB, [user] may name a domain
 * (`DOMAIN\user`), and an empty one signs in anonymously or as a guest; on WebDAV, an
 * empty user and password sign in as no one.
 * [passwordLost] says the stored password can no longer be opened (the keystore was
 * reset, or the share came from another device): it is never signed in with until
 * entered again.
 */
data class Share(
    val id: String,
    val name: String,
    val path: ShareAddress,
    val user: String,
    val password: String,
    val passwordLost: Boolean = false
) {
    val kind: ShareKind get() = if (path is DavUrl) ShareKind.WEBDAV else ShareKind.SMB

    /** The share's top as the app's paths spell it. */
    val root: String get() = Remotes.rootOf(id)

    /** The domain [user] names before a `\`, or empty. */
    val domain: String get() = if ('\\' in user) user.substringBefore('\\') else ""

    /** [user] without its domain. */
    val account: String get() = user.substringAfter('\\')

    /** Hides the password from logs and crash reports. */
    override fun toString(): String = "Share(id=$id, name=$name, path=$path, user=$user)"
}
