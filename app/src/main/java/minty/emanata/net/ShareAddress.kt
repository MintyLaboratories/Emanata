package minty.emanata.net

/** Where a network share lives: an SMB [SharePath] or a WebDAV [DavUrl]. */
sealed interface ShareAddress {
    val host: String
    val port: Int

    /** The share's folder, `/`-separated, empty for its top. */
    val dir: String
}

/** How a share is reached. */
enum class ShareKind { SMB, WEBDAV }
