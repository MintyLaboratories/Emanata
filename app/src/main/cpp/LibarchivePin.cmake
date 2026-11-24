# The libarchive source the app (Libarchive.cmake) and the host tests (src/test/cpp) build: a
# commit of branch "emanata" of the fork, which reads solid RAR 3/4 archives. To move to a newer
# commit, change LIBARCHIVE_COMMIT and LIBARCHIVE_SHA256 (the SHA-256 of its tarball below).
set(LIBARCHIVE_COMMIT 9eec6bff14cc2ffaeaf8857aea6b6a8619807de3)
set(LIBARCHIVE_SHA256 3b97d1c7092c577c84eede5efff7dca807937c8e042f671bf5af0d01ae9b5ab1)
set(LIBARCHIVE_URL "https://github.com/MadameMinty/libarchive/archive/${LIBARCHIVE_COMMIT}.tar.gz")
