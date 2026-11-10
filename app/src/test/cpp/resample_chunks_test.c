// Host tests of a GPU band's chunk order (resample_chunks.c), with fake ops that act as the
// GPU: every row copied out exactly once, chunk edges, a slot reused only after its chunk
// finished, chunks finished in the order they started and overlapping, and what stopping
// or failing at each point leaves. Exits nonzero on the first failed run of checks.

#include "resample_chunks.h"

#include <stdio.h>
#include <string.h>

static int failures;

#define CHECK(c)                                                       \
    do {                                                               \
        if (!(c)) {                                                    \
            fprintf(stderr, "%s:%d: failed: %s\n", __FILE__, __LINE__, #c); \
            failures++;                                                \
        }                                                              \
    } while (0)

enum { ROWS = 400, OPS = 1000 };

// One band's record, with the fake GPU's state.
typedef struct {
    int chunk;
    int slots;
    // Per slot: the rows of its chunk in flight (y1 0: none) and that chunk's start index.
    int y0[RS_SLOTS_MAX];
    int y1[RS_SLOTS_MAX];
    int started_as[RS_SLOTS_MAX];
    int copied[ROWS];
    int starts;
    int finishes;
    int drops;
    int in_flight;
    int most_in_flight;
    // Start index of the last chunk finished, to see they finish in order.
    int last_finished;
    // When each chunk finished, chunks started after it by then (overlap).
    int started_before_finish[OPS];
    // Ops in order: 's' start, 'f' finish, 'd' drop, 'q' a stop question.
    char log[OPS];
    int ops;
    // Fail the nth start or finish (1-based; 0 never); answer stop from the nth question on.
    int fail_start;
    int fail_finish;
    int stop_from;
    int questions;
    int bad;
} band;

static void note(band * b, char op)
{
    if (b->ops < OPS - 1) b->log[b->ops++] = op;
}

static int start(void * ctx, int slot, int y0, int y1)
{
    band * b = ctx;
    note(b, 's');
    if (slot < 0 || slot >= b->slots || b->y1[slot] != 0) b->bad++;
    if (y1 <= y0 || y1 - y0 > b->chunk || (y0 / b->chunk != (y1 - 1) / b->chunk)) b->bad++;
    if (++b->starts == b->fail_start) return -1;
    b->y0[slot] = y0;
    b->y1[slot] = y1;
    b->started_as[slot] = b->starts;
    if (++b->in_flight > b->most_in_flight) b->most_in_flight = b->in_flight;
    return 0;
}

static int finish(void * ctx, int slot)
{
    band * b = ctx;
    note(b, 'f');
    if (slot < 0 || slot >= b->slots || b->y1[slot] == 0) {
        b->bad++;
        return -1;
    }
    const int index = b->started_as[slot];
    if (index != b->last_finished + 1) b->bad++;
    b->last_finished = index;
    if (index < OPS) b->started_before_finish[index] = b->starts - index;
    const int failed = ++b->finishes == b->fail_finish;
    if (!failed) {
        for (int y = b->y0[slot]; y < b->y1[slot]; y++) b->copied[y]++;
    }
    b->y1[slot] = 0;
    b->in_flight--;
    return failed ? -1 : 0;
}

static void drop(void * ctx, int slot)
{
    band * b = ctx;
    note(b, 'd');
    if (slot < 0 || slot >= b->slots || b->y1[slot] == 0) {
        b->bad++;
        return;
    }
    b->y1[slot] = 0;
    b->in_flight--;
    b->drops++;
}

static int stop(void * opaque)
{
    band * b = opaque;
    note(b, 'q');
    b->questions++;
    return b->stop_from != 0 && b->questions >= b->stop_from;
}

static const rs_chunk_ops fake_ops = { start, finish, drop };

static void init(band * b, int chunk, int slots)
{
    memset(b, 0, sizeof(*b));
    b->chunk = chunk;
    b->slots = slots;
}

static int run(band * b, int y0, int y1, int asked)
{
    return rs_chunks_run(&fake_ops, b, b->chunk, b->slots, y0, y1, asked ? stop : NULL, b);
}

static int chunks_of(int chunk, int y0, int y1)
{
    int n = 0;
    for (int y = y0; y < y1; n++) {
        const int next = (y / chunk + 1) * chunk;
        y = next < y1 ? next : y1;
    }
    return n;
}

// Every band of every geometry: all rows once, in order, overlapping, nothing left.
static void test_complete(void)
{
    for (int slots = 1; slots <= RS_SLOTS_MAX; slots++) {
        for (int chunk = 1; chunk <= 50; chunk += 7) {
            for (int y0 = 0; y0 < 120; y0 += 13) {
                for (int y1 = y0; y1 <= 260; y1 += 37) {
                    for (int asked = 0; asked <= 1; asked++) {
                        band b;
                        init(&b, chunk, slots);
                        CHECK(run(&b, y0, y1, asked) == 0);
                        CHECK(b.bad == 0);
                        CHECK(b.in_flight == 0);
                        CHECK(b.drops == 0);
                        const int n = chunks_of(chunk, y0, y1);
                        CHECK(b.starts == n && b.finishes == n);
                        CHECK(b.most_in_flight == (n < slots ? n : slots));
                        for (int y = 0; y < ROWS; y++) CHECK(b.copied[y] == (y >= y0 && y < y1));
                        // Each chunk but the last few finishes once slots - 1 later ones started.
                        for (int k = 1; k <= n; k++) {
                            const int later = n - k < slots - 1 ? n - k : slots - 1;
                            CHECK(b.started_before_finish[k] == later);
                        }
                        // Asked before each start and each finish of the drain.
                        if (asked) CHECK(b.questions == n + (n < slots ? n : slots));
                    }
                }
            }
        }
    }
}

// Two slots, three chunks: the first slot is finished before it starts the third.
static void test_reuse_order(void)
{
    band b;
    init(&b, 10, 2);
    CHECK(run(&b, 0, 30, 0) == 0);
    b.log[b.ops] = 0;
    CHECK(strcmp(b.log, "ssfsff") == 0);
}

// Stopped at each question: no start or finish after it, the rest dropped.
static void test_stop(void)
{
    for (int slots = 1; slots <= 3; slots++) {
        const int n = chunks_of(8, 3, 77);
        for (int from = 1; from <= n + (n < slots ? n : slots); from++) {
            band b;
            init(&b, 8, slots);
            b.stop_from = from;
            CHECK(run(&b, 3, 77, 1) == 1);
            CHECK(b.bad == 0);
            CHECK(b.in_flight == 0);
            CHECK(b.questions == from);
            CHECK(b.starts == b.finishes + b.drops);
            b.log[b.ops] = 0;
            const char * stopped = strrchr(b.log, 'q');
            CHECK(stopped != NULL && strchr(stopped, 's') == NULL && strchr(stopped, 'f') == NULL);
            int copied = 0;
            for (int y = 0; y < ROWS; y++) copied += b.copied[y];
            CHECK(copied <= 77 - 3);
        }
    }
}

// The last question of a band comes in the drain: a stop there drops what is in flight.
static void test_stop_in_drain(void)
{
    band b;
    init(&b, 10, 2);
    b.stop_from = 4;
    CHECK(run(&b, 0, 30, 1) == 1);
    CHECK(b.starts == 3 && b.finishes == 1 && b.drops == 2);
    CHECK(b.in_flight == 0);
    b.log[b.ops] = 0;
    CHECK(strcmp(b.log, "qsqsqfsqdd") == 0);
}

// A failed start or finish: -1, no op after it but drops, nothing left in flight.
static void test_failures(void)
{
    for (int slots = 1; slots <= 3; slots++) {
        const int n = chunks_of(6, 0, 50);
        for (int k = 1; k <= n; k++) {
            for (int which = 0; which < 2; which++) {
                band b;
                init(&b, 6, slots);
                if (which == 0) b.fail_start = k; else b.fail_finish = k;
                CHECK(run(&b, 0, 50, 0) == -1);
                CHECK(b.bad == 0);
                CHECK(b.in_flight == 0);
                b.log[b.ops] = 0;
                // The failed op is the last start or finish.
                const char * last = b.log + strlen(b.log);
                while (last > b.log && last[-1] == 'd') last--;
                CHECK(last > b.log && (last[-1] == (which == 0 ? 's' : 'f')));
            }
        }
    }
}

// Bad arguments start nothing.
static void test_bad_arguments(void)
{
    band b;
    init(&b, 0, 2);
    CHECK(rs_chunks_run(&fake_ops, &b, 0, 2, 0, 10, NULL, NULL) == -1);
    CHECK(rs_chunks_run(&fake_ops, &b, 4, 0, 0, 10, NULL, NULL) == -1);
    CHECK(rs_chunks_run(&fake_ops, &b, 4, RS_SLOTS_MAX + 1, 0, 10, NULL, NULL) == -1);
    CHECK(b.ops == 0);
}

int main(void)
{
    test_complete();
    test_reuse_order();
    test_stop();
    test_stop_in_drain();
    test_failures();
    test_bad_arguments();
    if (failures != 0) {
        fprintf(stderr, "%d checks failed\n", failures);
        return 1;
    }
    printf("resample_chunks: all checks passed\n");
    return 0;
}
