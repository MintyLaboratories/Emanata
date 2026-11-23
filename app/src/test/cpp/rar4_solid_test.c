// The app's modified libarchive RAR 4 reader (src/main/cpp/libarchive_rar) on a solid archive:
// every entry reads back with its CRC-32, read in order, reached by skipping the entries before
// it (which unpacks them), or after a partial read; with rar:solid-skip=consume, skipping jumps
// over the data and the solid entry after it is refused. Argument: testfile.rar3.solid.cbr.
#include <archive.h>
#include <archive_entry.h>
#include <stdio.h>
#include <string.h>
#include <zlib.h>

static const struct {
    const char *name;
    long size;
    unsigned long crc;
} want[] = {{"testfile.png", 87, 0xafb0ac62UL}, {"testfile.jpg", 220, 0xda70b16cUL}};

#define COUNT 2

static int failures = 0;

#define CHECK(cond, ...)                         \
    do {                                         \
        if (!(cond)) {                           \
            printf("FAIL line %d: ", __LINE__);  \
            printf(__VA_ARGS__);                 \
            printf("\n");                        \
            failures++;                          \
        }                                        \
    } while (0)

static struct archive *open_rar(const char *path, int consume) {
    struct archive *a = archive_read_new();
    archive_read_support_format_rar(a);
    if (consume) archive_read_set_format_option(a, "rar", "solid-skip", "consume");
    if (archive_read_open_filename(a, path, 4096) != ARCHIVE_OK) {
        printf("FAIL: cannot open %s: %s\n", path, archive_error_string(a));
        archive_read_free(a);
        return NULL;
    }
    return a;
}

// Reads up to limit bytes (-1: all) of the current entry into *crc and *size; the last read's result.
static la_ssize_t read_entry(struct archive *a, long limit, unsigned long *crc, long *size) {
    char buf[1000];
    la_ssize_t n = 0;
    *crc = crc32(0, NULL, 0);
    *size = 0;
    while (limit < 0 || *size < limit) {
        size_t want_bytes = sizeof buf;
        if (limit >= 0 && (long) want_bytes > limit - *size) want_bytes = (size_t) (limit - *size);
        n = archive_read_data(a, buf, want_bytes);
        if (n <= 0) break;
        *crc = crc32(*crc, (unsigned char *) buf, (unsigned) n);
        *size += (long) n;
    }
    return n;
}

// Moves to entry i (0-based) from a fresh handle, reading limit bytes of each entry before it.
static int walk_to(struct archive *a, int i, long limit) {
    struct archive_entry *e;
    for (int j = 0; j <= i; j++) {
        if (archive_read_next_header(a, &e) != ARCHIVE_OK) return 0;
        CHECK(strcmp(archive_entry_pathname(e), want[j].name) == 0, "entry %d is %s", j, archive_entry_pathname(e));
        if (j < i && limit != 0) {
            unsigned long crc;
            long size;
            read_entry(a, limit, &crc, &size);
        }
    }
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 2) return 2;
    const char *path = argv[1];
    unsigned long crc;
    long size;

    // In order, each read in full.
    struct archive *a = open_rar(path, 0);
    if (!a) return 1;
    for (int i = 0; i < COUNT; i++) {
        struct archive_entry *e;
        if (archive_read_next_header(a, &e) != ARCHIVE_OK) {
            CHECK(0, "in order: header %d: %s", i, archive_error_string(a));
            break;
        }
        CHECK(strcmp(archive_entry_pathname(e), want[i].name) == 0, "in order: entry %d is %s", i, archive_entry_pathname(e));
        CHECK(read_entry(a, -1, &crc, &size) == 0, "in order: entry %d: %s", i, archive_error_string(a));
        CHECK(size == want[i].size && crc == want[i].crc, "in order: entry %d: %ld bytes, crc %08lx", i, size, crc);
    }
    archive_read_free(a);

    // Each entry reached by skipping those before it, then after reading 10 bytes of each.
    for (long limit = 0; limit <= 10; limit += 10) {
        for (int i = 0; i < COUNT; i++) {
            a = open_rar(path, 0);
            if (!a) return 1;
            CHECK(walk_to(a, i, limit), "skip %ld: header %d: %s", limit, i, archive_error_string(a));
            CHECK(read_entry(a, -1, &crc, &size) == 0, "skip %ld: entry %d: %s", limit, i, archive_error_string(a));
            CHECK(size == want[i].size && crc == want[i].crc, "skip %ld: entry %d: %ld bytes, crc %08lx", limit, i, size, crc);
            archive_read_free(a);
        }
    }

    // solid-skip=consume: the headers list, the first entry reads, the solid one after a skip does not.
    a = open_rar(path, 1);
    if (!a) return 1;
    CHECK(walk_to(a, 1, 0), "consume: header 1: %s", archive_error_string(a));
    CHECK(read_entry(a, -1, &crc, &size) == ARCHIVE_FAILED, "consume: the solid entry read after a skip");
    archive_read_free(a);
    a = open_rar(path, 1);
    if (!a) return 1;
    CHECK(walk_to(a, 0, 0), "consume: header 0: %s", archive_error_string(a));
    CHECK(read_entry(a, -1, &crc, &size) == 0 && crc == want[0].crc, "consume: entry 0: %s", archive_error_string(a));
    archive_read_free(a);

    if (failures) printf("%d failure(s)\n", failures);
    return failures != 0;
}
