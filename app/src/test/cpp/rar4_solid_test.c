// The app's modified libarchive RAR 4 reader (src/main/cpp/libarchive_rar) on the CC0 RAR test
// files (src/test/resources/rar): the same two images as RAR 4, solid RAR 4, RAR 5 and solid RAR 5.
// - Each file lists both entries in its own order, and every entry unpacks to the same bytes.
// - Solid RAR 4: each entry reads back in order, reached by skipping the entries before it
//   (which unpacks them), or after a partial read; with rar:solid-skip=consume, skipping jumps
//   over the data and the solid entry after it is refused.
// - Solid RAR 4 behind a stored entry (built from the solid file): the first compressed entry,
//   flagged solid, starts the stream; flagged solid as an archive's first entry, it is refused.
// Argument: the test files' folder.
#include <archive.h>
#include <archive_entry.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

#define PNG 0
#define MAX_BYTES 4096

static const struct {
    const char *name;
    long size;
    unsigned long crc;
} want[] = {{"testfile.png", 87, 0xafb0ac62UL}, {"testfile.jpg", 220, 0xda70b16cUL}};

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

typedef struct {
    unsigned char *bytes;
    size_t size;
} Buffer;

static Buffer load(const char *dir, const char *name) {
    char path[4096];
    Buffer b = {NULL, 0};
    snprintf(path, sizeof path, "%s/%s", dir, name);
    FILE *f = fopen(path, "rb");
    if (!f) return b;
    b.bytes = malloc(MAX_BYTES);
    if (b.bytes) b.size = fread(b.bytes, 1, MAX_BYTES, f);
    fclose(f);
    return b;
}

static struct archive *open_rar(Buffer in, int consume) {
    struct archive *a = archive_read_new();
    archive_read_support_format_rar(a);
    archive_read_support_format_rar5(a);
    if (consume) archive_read_set_format_option(a, "rar", "solid-skip", "consume");
    if (archive_read_open_memory(a, in.bytes, in.size) != ARCHIVE_OK) {
        printf("FAIL: cannot open: %s\n", archive_error_string(a));
        failures++;
        archive_read_free(a);
        return NULL;
    }
    return a;
}

static int index_of(const char *name) {
    for (int i = 0; i < 2; i++)
        if (strcmp(name, want[i].name) == 0) return i;
    return -1;
}

// Reads up to limit bytes (-1: all) of the current entry into out (if any), *crc and *size; the last read's result.
static la_ssize_t read_entry(struct archive *a, long limit, unsigned char *out, unsigned long *crc, long *size) {
    char buf[100];
    la_ssize_t n = 0;
    *crc = crc32(0, NULL, 0);
    *size = 0;
    while (limit < 0 || *size < limit) {
        size_t want_bytes = sizeof buf;
        if (limit >= 0 && (long) want_bytes > limit - *size) want_bytes = (size_t) (limit - *size);
        n = archive_read_data(a, buf, want_bytes);
        if (n <= 0) break;
        if (out && *size + n <= MAX_BYTES) memcpy(out + *size, buf, (size_t) n);
        *crc = crc32(*crc, (unsigned char *) buf, (unsigned) n);
        *size += (long) n;
    }
    return n;
}

// Every entry of in, read in order: the names in the order given, the bytes into bytes by want[] index.
static void read_all(const char *label, Buffer in, const char *first, const char *second,
                     unsigned char bytes[2][MAX_BYTES]) {
    struct archive *a = open_rar(in, 0);
    if (!a) return;
    const char *order[] = {first, second};
    struct archive_entry *e;
    for (int i = 0; i < 2; i++) {
        unsigned long crc;
        long size;
        if (archive_read_next_header(a, &e) != ARCHIVE_OK) {
            CHECK(0, "%s: header %d: %s", label, i, archive_error_string(a));
            break;
        }
        const char *name = archive_entry_pathname(e);
        CHECK(strcmp(name, order[i]) == 0, "%s: entry %d is %s", label, i, name);
        int w = index_of(name);
        if (w < 0) continue;
        CHECK(read_entry(a, -1, bytes[w], &crc, &size) == 0, "%s: %s: %s", label, name, archive_error_string(a));
        CHECK(size == want[w].size && crc == want[w].crc, "%s: %s: %ld bytes, crc %08lx", label, name, size, crc);
    }
    CHECK(archive_read_next_header(a, &e) == ARCHIVE_EOF, "%s: more than 2 entries", label);
    archive_read_free(a);
}

// Moves to entry i (0-based) from a fresh handle, reading limit bytes of each entry before it.
static int walk_to(struct archive *a, const char *const *names, int i, long limit) {
    struct archive_entry *e;
    for (int j = 0; j <= i; j++) {
        if (archive_read_next_header(a, &e) != ARCHIVE_OK) return 0;
        CHECK(strcmp(archive_entry_pathname(e), names[j]) == 0, "entry %d is %s", j, archive_entry_pathname(e));
        if (j < i && limit != 0) {
            unsigned long crc;
            long size;
            read_entry(a, limit, NULL, &crc, &size);
        }
    }
    return 1;
}

// Each of the count entries named names, reached by skipping those before it, then after reading 10 bytes of each.
static void skip_to_each(const char *label, Buffer in, const char *const *names, int count) {
    for (long limit = 0; limit <= 10; limit += 10) {
        for (int i = 0; i < count; i++) {
            unsigned long crc;
            long size;
            struct archive *a = open_rar(in, 0);
            if (!a) return;
            CHECK(walk_to(a, names, i, limit), "%s, skip %ld: header %d: %s", label, limit, i, archive_error_string(a));
            CHECK(read_entry(a, -1, NULL, &crc, &size) == 0, "%s, skip %ld: entry %d: %s", label, limit, i,
                  archive_error_string(a));
            int w = index_of(names[i]);
            if (w >= 0)
                CHECK(size == want[w].size && crc == want[w].crc, "%s, skip %ld: entry %d: %ld bytes, crc %08lx", label,
                      limit, i, size, crc);
            archive_read_free(a);
        }
    }
}

static unsigned le16(const unsigned char *p) { return p[0] | (unsigned) p[1] << 8; }

static void put_le16(unsigned char *p, unsigned v) {
    p[0] = (unsigned char) v;
    p[1] = (unsigned char) (v >> 8);
}

static void put_le32(unsigned char *p, unsigned long v) {
    for (int i = 0; i < 4; i++) p[i] = (unsigned char) (v >> (8 * i));
}

// A RAR 4 block's header CRC: the low 16 bits of the CRC-32 of its header after the CRC field.
static void seal(unsigned char *header) {
    put_le16(header, (unsigned) crc32(0, header + 2, le16(header + 5) - 2) & 0xFFFF);
}

#define MAIN_END 20 // signature (7) and main header (13)

// The solid RAR 4 with its first entry flagged solid (0x0010), behind a stored entry holding
// text when stored_first.
static Buffer first_flagged_solid(Buffer solid, int stored_first, const unsigned char *text, unsigned text_size) {
    Buffer b = {malloc(MAX_BYTES), 0};
    const unsigned char *first = solid.bytes + MAIN_END;
    unsigned header_size = le16(first + 5);
    memcpy(b.bytes, solid.bytes, MAIN_END);
    b.size = MAIN_END;
    if (stored_first) {
        // The first file header with another name of the same length, stored (0x30).
        unsigned char *h = b.bytes + b.size;
        memcpy(h, first, header_size);
        put_le32(h + 7, text_size);
        put_le32(h + 11, text_size);
        put_le32(h + 16, crc32(0, text, text_size));
        h[25] = 0x30;
        memcpy(h + 32, "notes-01.txt", 12);
        seal(h);
        memcpy(h + header_size, text, text_size);
        b.size += header_size + text_size;
    }
    unsigned char *h = b.bytes + b.size;
    memcpy(h, first, solid.size - MAIN_END);
    put_le16(h + 3, le16(h + 3) | 0x0010);
    seal(h);
    b.size += solid.size - MAIN_END;
    return b;
}

int main(int argc, char **argv) {
    if (argc < 2) return 2;
    const char *dir = argv[1];
    Buffer rar4 = load(dir, "testfile.rar3.cbr"), solid4 = load(dir, "testfile.rar3.solid.cbr");
    Buffer rar5 = load(dir, "testfile.rar5.cbr"), solid5 = load(dir, "testfile.rar5.solid.cbr");
    if (!rar4.size || !solid4.size || !rar5.size || !solid5.size) {
        printf("FAIL: cannot read the test files in %s\n", dir);
        return 1;
    }
    unsigned long crc;
    long size;
    struct archive *a;

    // The four files: each lists both entries in its order; every entry unpacks to the same bytes.
    static unsigned char bytes[4][2][MAX_BYTES];
    read_all("RAR 4", rar4, "testfile.jpg", "testfile.png", bytes[0]);
    read_all("solid RAR 4", solid4, "testfile.png", "testfile.jpg", bytes[1]);
    read_all("RAR 5", rar5, "testfile.jpg", "testfile.png", bytes[2]);
    read_all("solid RAR 5", solid5, "testfile.jpg", "testfile.png", bytes[3]);
    for (int f = 1; f < 4; f++)
        for (int w = 0; w < 2; w++)
            CHECK(memcmp(bytes[0][w], bytes[f][w], (size_t) want[w].size) == 0, "file %d: %s differs from RAR 4's", f,
                  want[w].name);

    // Solid RAR 4: each entry reached by skipping or partly reading those before it.
    const char *const solid_names[] = {"testfile.png", "testfile.jpg"};
    skip_to_each("solid RAR 4", solid4, solid_names, 2);

    // solid-skip=consume: the headers list, the first entry reads, the solid one after a skip does not.
    a = open_rar(solid4, 1);
    if (!a) return 1;
    CHECK(walk_to(a, solid_names, 1, 0), "consume: header 1: %s", archive_error_string(a));
    CHECK(read_entry(a, -1, NULL, &crc, &size) == ARCHIVE_FAILED, "consume: the solid entry read after a skip");
    archive_read_free(a);
    a = open_rar(solid4, 1);
    if (!a) return 1;
    CHECK(walk_to(a, solid_names, 0, 0), "consume: header 0: %s", archive_error_string(a));
    CHECK(read_entry(a, -1, NULL, &crc, &size) == 0 && crc == want[PNG].crc, "consume: entry 0: %s",
          archive_error_string(a));
    archive_read_free(a);

    // A stored entry first: the compressed entry after it, flagged solid, starts the stream.
    static const unsigned char text[] = "Stored before the solid stream.\n";
    Buffer stored_first = first_flagged_solid(solid4, 1, text, sizeof text - 1);
    const char *const stored_names[] = {"notes-01.txt", "testfile.png", "testfile.jpg"};
    a = open_rar(stored_first, 0);
    if (!a) return 1;
    for (int i = 0; i < 3; i++) {
        struct archive_entry *e;
        unsigned char out[MAX_BYTES];
        if (archive_read_next_header(a, &e) != ARCHIVE_OK) {
            CHECK(0, "stored first: header %d: %s", i, archive_error_string(a));
            break;
        }
        CHECK(strcmp(archive_entry_pathname(e), stored_names[i]) == 0, "stored first: entry %d is %s", i,
              archive_entry_pathname(e));
        CHECK(read_entry(a, -1, out, &crc, &size) == 0, "stored first: entry %d: %s", i, archive_error_string(a));
        if (i == 0)
            CHECK(size == (long) sizeof text - 1 && memcmp(out, text, sizeof text - 1) == 0, "stored first: the text");
        else
            CHECK(size == want[i - 1].size && memcmp(out, bytes[0][i - 1], (size_t) size) == 0,
                  "stored first: entry %d: %ld bytes", i, size);
    }
    archive_read_free(a);
    skip_to_each("stored first", stored_first, stored_names, 3);

    // Flagged solid as the archive's first entry, it continues a stream this archive lacks.
    Buffer solid_first = first_flagged_solid(solid4, 0, NULL, 0);
    a = open_rar(solid_first, 0);
    if (!a) return 1;
    CHECK(walk_to(a, solid_names, 0, 0), "solid first: header 0: %s", archive_error_string(a));
    CHECK(read_entry(a, -1, NULL, &crc, &size) == ARCHIVE_FAILED, "solid first: the first entry read");
    archive_read_free(a);

    free(rar4.bytes);
    free(solid4.bytes);
    free(rar5.bytes);
    free(solid5.bytes);
    free(stored_first.bytes);
    free(solid_first.bytes);
    if (failures) printf("%d failure(s)\n", failures);
    return failures != 0;
}
