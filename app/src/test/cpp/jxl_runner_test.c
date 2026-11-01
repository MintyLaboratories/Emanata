// Host tests of the JPEG XL parallel runner (jxl_runner.c): every value handed out exactly
// once, init's thread count, the wait for workers, cancellation, the caller-only path and
// pinning, which never nests. Exits nonzero on the first failed run of checks.

// sched_getcpu, CPU_* and pthread_setaffinity_np.
#define _GNU_SOURCE

#include "jxl_runner.h"

#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static int failures;

#define CHECK(c)                                                       \
    do {                                                               \
        if (!(c)) {                                                    \
            fprintf(stderr, "%s:%d: failed: %s\n", __FILE__, __LINE__, #c); \
            failures++;                                                \
        }                                                              \
    } while (0)

enum { MOST = 20000 };

// One run's record: how often each value ran, by which threads, and what init was told.
typedef struct {
    atomic_int counts[MOST];
    atomic_int done;
    atomic_int bad_id;
    atomic_int off_core;
    int init_calls;
    size_t threads;
    JxlParallelRetCode init_result;
    // Microseconds each value sleeps on a worker thread (not the caller).
    int worker_sleep_us;
    // When nonzero, workers check they run on this core.
    int want_core;
} Job;

static Job job;

static void reset(void)
{
    memset(&job, 0, sizeof(job));
    job.threads = 0;
}

static void sleep_us(int us)
{
    struct timespec t = { 0, (long)us * 1000L };
    nanosleep(&t, NULL);
}

static JxlParallelRetCode init(void * opaque, size_t threads)
{
    Job * j = opaque;
    j->init_calls++;
    j->threads = threads;
    return j->init_result;
}

static void func(void * opaque, uint32_t value, size_t id)
{
    Job * j = opaque;
    if (id >= j->threads) atomic_store(&j->bad_id, 1);
    if (id != 0 && j->want_core != 0 && sched_getcpu() != j->want_core - 1) atomic_store(&j->off_core, 1);
    if (id != 0 && j->worker_sleep_us > 0) sleep_us(j->worker_sleep_us);
    atomic_fetch_add(&j->counts[value], 1);
    atomic_fetch_add(&j->done, 1);
}

// Every value in start..end ran once, none outside, on ids init allowed.
static void check_once(uint32_t start, uint32_t end)
{
    int wrong = 0;
    for (uint32_t v = 0; v < MOST; v++) {
        const int want = v >= start && v < end ? 1 : 0;
        if (atomic_load(&job.counts[v]) != want) wrong++;
    }
    CHECK(wrong == 0);
    CHECK(atomic_load(&job.bad_id) == 0);
}

static void hands_out_each_value_once(void)
{
    const int sizes[] = { 0, 1, 3, 7 };
    for (size_t s = 0; s < sizeof(sizes) / sizeof(sizes[0]); s++) {
        const int workers = sizes[s];
        jr_pool * pool = jr_create(workers, 0);
        CHECK(pool != NULL);
        if (pool == NULL) continue;
        for (uint32_t r = 0; r < 2000; r++) {
            reset();
            const uint32_t start = r % 5;
            const uint32_t end = start + (r * 7) % 40;
            CHECK(jr_run(pool, &job, init, func, start, end) == JXL_PARALLEL_RET_SUCCESS);
            check_once(start, end);
            if (end == start) {
                CHECK(job.init_calls == 0);
            } else {
                CHECK(job.init_calls == 1);
                CHECK(job.threads == (end - start == 1 || workers == 0 ? 1u : (size_t)workers + 1));
            }
        }
        jr_destroy(pool);
    }
}

static void waits_for_every_worker(void)
{
    jr_pool * pool = jr_create(3, 0);
    CHECK(pool != NULL);
    if (pool == NULL) return;
    for (int r = 0; r < 50; r++) {
        reset();
        job.worker_sleep_us = 200;
        CHECK(jr_run(pool, &job, init, func, 0, 64) == JXL_PARALLEL_RET_SUCCESS);
        // Read once jr_run returned: no value may still be running.
        CHECK(atomic_load(&job.done) == 64);
        check_once(0, 64);
    }
    jr_destroy(pool);
}

static void init_failure_fails_the_run(void)
{
    jr_pool * pool = jr_create(3, 0);
    CHECK(pool != NULL);
    if (pool == NULL) return;
    reset();
    job.init_result = -5;
    CHECK(jr_run(pool, &job, init, func, 0, 100) == -5);
    CHECK(atomic_load(&job.done) == 0);
    CHECK(!jr_canceled(pool));
    jr_destroy(pool);
}

// A stop that says so after `after` asks, all on the thread that made it.
typedef struct {
    int asks;
    int after;
    pthread_t caller;
    int elsewhere;
    // When set, says stop only once every value of the run is done.
    int when_done;
    uint32_t total;
} Stop;

static int stop_now(void * opaque)
{
    Stop * s = opaque;
    if (!pthread_equal(pthread_self(), s->caller)) s->elsewhere = 1;
    s->asks++;
    if (s->when_done) return atomic_load(&job.done) == (int)s->total;
    return s->asks > s->after;
}

static void stops_midway_and_stays_stopped(void)
{
    const int sizes[] = { 0, 3 };
    for (size_t i = 0; i < 2; i++) {
        jr_pool * pool = jr_create(sizes[i], 0);
        CHECK(pool != NULL);
        if (pool == NULL) continue;
        Stop stop = { 0, 50, pthread_self(), 0, 0, 0 };
        jr_stop_when(pool, stop_now, &stop);
        reset();
        job.worker_sleep_us = 20;
        CHECK(jr_run(pool, &job, init, func, 0, MOST) == JXL_PARALLEL_RET_RUNNER_ERROR);
        CHECK(jr_canceled(pool));
        CHECK(stop.elsewhere == 0);
        const int done = atomic_load(&job.done);
        CHECK(done > 0 && done < MOST);
        int twice = 0;
        for (uint32_t v = 0; v < MOST; v++) twice += atomic_load(&job.counts[v]) > 1;
        CHECK(twice == 0);
        // Later runs fail at once, without init or values.
        reset();
        CHECK(jr_run(pool, &job, init, func, 0, 10) == JXL_PARALLEL_RET_RUNNER_ERROR);
        CHECK(job.init_calls == 0);
        CHECK(atomic_load(&job.done) == 0);
        jr_destroy(pool);
    }
}

static void stop_after_the_last_value_keeps_every_value(void)
{
    jr_pool * pool = jr_create(3, 0);
    CHECK(pool != NULL);
    if (pool == NULL) return;
    Stop stop = { 0, 0, pthread_self(), 0, 1, 200 };
    jr_stop_when(pool, stop_now, &stop);
    reset();
    const JxlParallelRetCode r = jr_run(pool, &job, init, func, 0, 200);
    // Each value ran once; the run still counts as stopped when the caller asked after the last.
    check_once(0, 200);
    CHECK(r == (jr_canceled(pool) ? JXL_PARALLEL_RET_RUNNER_ERROR : JXL_PARALLEL_RET_SUCCESS));
    CHECK(stop.elsewhere == 0);
    jr_destroy(pool);
}

static void caller_only_pool_runs_on_the_caller(void)
{
    jr_pool * pool = jr_create(0, 0);
    CHECK(pool != NULL);
    if (pool == NULL) return;
    reset();
    CHECK(jr_run(pool, &job, init, func, 10, 110) == JXL_PARALLEL_RET_SUCCESS);
    CHECK(job.threads == 1);
    check_once(10, 110);
    jr_destroy(pool);
}

static void pins_and_restores(void)
{
    cpu_set_t before;
    CHECK(sched_getaffinity(0, sizeof(before), &before) == 0);
    cpu_set_t kept;
    CHECK(jr_pin(0, &kept) == 0);
    int first = -1;
    for (int n = 0; n < 64 && first < 0; n++) {
        if (CPU_ISSET(n, &before)) first = n;
    }
    CHECK(first >= 0);
    if (first < 0) return;
    CHECK(jr_pin(1ull << first, &kept) == 1);
    CHECK(sched_getcpu() == first);
    CHECK(CPU_EQUAL(&kept, &before));
    // Workers of a pool pinned to that core run there.
    jr_pool * pool = jr_create(2, 1ull << first);
    CHECK(pool != NULL);
    if (pool != NULL) {
        reset();
        job.want_core = first + 1;
        job.worker_sleep_us = 50;
        CHECK(jr_run(pool, &job, init, func, 0, 300) == JXL_PARALLEL_RET_SUCCESS);
        CHECK(atomic_load(&job.off_core) == 0);
        jr_destroy(pool);
    }
    jr_unpin();
    cpu_set_t after;
    CHECK(sched_getaffinity(0, sizeof(after), &after) == 0);
    CHECK(CPU_EQUAL(&after, &before));
}

// The cores this thread may run on, as a mask of the first 64.
static uint64_t allowed(void)
{
    cpu_set_t now;
    if (sched_getaffinity(0, sizeof(now), &now) != 0) return 0;
    uint64_t mask = 0;
    for (int n = 0; n < 64 && n < CPU_SETSIZE; n++) {
        if (CPU_ISSET(n, &now)) mask |= 1ull << n;
    }
    return mask;
}

static void pins_never_nest_and_end_on_every_core(void)
{
    const uint64_t every = allowed();
    const long online = sysconf(_SC_NPROCESSORS_ONLN);
    CHECK(online > 0 && __builtin_popcountll(every) == (online < 64 ? online : 64));
    const int first = __builtin_ctzll(every);
    const uint64_t other = every & ~(1ull << first);
    // An inner pin is refused, so the outer one holds until it ends.
    CHECK(jr_pin_mask(1ull << first) == every);
    CHECK(allowed() == 1ull << first);
    cpu_set_t kept;
    if (other != 0) {
        CHECK(jr_pin(other, &kept) == 0);
        CHECK(jr_pin_mask(other) == 0);
    }
    CHECK(allowed() == 1ull << first);
    // Ending it with every core's bit allows every online core again, not a set kept before.
    CHECK(jr_pin_mask(UINT64_MAX) == 0);
    CHECK(allowed() == every);
    // Once ended, a new pin takes.
    CHECK(jr_pin(1ull << first, &kept) == 1);
    CHECK(allowed() == 1ull << first);
    jr_unpin();
    CHECK(allowed() == every);
    // Pool workers pin themselves; the caller is left as it was.
    jr_pool * pool = jr_create(2, 1ull << first);
    CHECK(pool != NULL);
    if (pool != NULL) jr_destroy(pool);
    CHECK(allowed() == every);
}

int main(void)
{
    hands_out_each_value_once();
    waits_for_every_worker();
    init_failure_fails_the_run();
    stops_midway_and_stays_stopped();
    stop_after_the_last_value_keeps_every_value();
    caller_only_pool_runs_on_the_caller();
    pins_and_restores();
    pins_never_nest_and_end_on_every_core();
    if (failures > 0) {
        fprintf(stderr, "%d checks failed\n", failures);
        return 1;
    }
    printf("jxl_runner: all checks passed\n");
    return 0;
}
