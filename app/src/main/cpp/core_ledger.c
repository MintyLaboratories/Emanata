// sched_getcpu.
#define _GNU_SOURCE

#include "core_ledger.h"

#include <sched.h>
#include <stdatomic.h>
#include <time.h>
#include <unistd.h>

static atomic_int opened;
static atomic_int_fast64_t spent[CL_MAX_CORES];
static atomic_int tids[CL_MAX_TIDS];
static atomic_int_fast64_t tid_ns[CL_MAX_TIDS];
static atomic_int tid_count;
// Bumped whenever the recorded threads are taken: a thread recorded before records itself again.
static atomic_int generation;

// This thread's nesting depth, its CPU time at its last tick, and its slot among the
// recorded threads in generation `slot_gen`.
static _Thread_local int depth;
static _Thread_local int64_t last;
static _Thread_local int slot = -1;
static _Thread_local int slot_gen = -1;

static int64_t thread_ns(void)
{
    struct timespec t;
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t) != 0) return 0;
    return (int64_t)t.tv_sec * 1000000000LL + t.tv_nsec;
}

void cl_open(int open)
{
    atomic_store(&opened, 0);
    for (int i = 0; i < CL_MAX_CORES; i++) atomic_store(&spent[i], 0);
    atomic_fetch_add(&generation, 1);
    atomic_store(&tid_count, 0);
    for (int i = 0; i < CL_MAX_TIDS; i++) atomic_store(&tid_ns[i], 0);
    atomic_store(&opened, open != 0);
}

// Records this thread (once per generation); its slot, -1 when the slots are full.
static int record(void)
{
    const int gen = atomic_load(&generation);
    if (slot_gen == gen) return slot;
    const int tid = gettid();
    int at = -1;
    const int n = atomic_load(&tid_count);
    for (int i = 0; i < n && i < CL_MAX_TIDS; i++) {
        if (atomic_load(&tids[i]) == tid) at = i;
    }
    if (at < 0) {
        at = atomic_fetch_add(&tid_count, 1);
        if (at < CL_MAX_TIDS) atomic_store(&tids[at], tid);
        else at = -1;
    }
    slot = at;
    slot_gen = gen;
    return at;
}

void cl_begin(void)
{
    if (depth > 0) {
        depth++;
        return;
    }
    if (!atomic_load_explicit(&opened, memory_order_relaxed)) return;
    depth = 1;
    last = thread_ns();
    record();
}

void cl_tick(void)
{
    if (depth == 0) return;
    const int64_t now = thread_ns();
    const int64_t d = now - last;
    last = now;
    if (d <= 0 || !atomic_load_explicit(&opened, memory_order_relaxed)) return;
    const int cpu = sched_getcpu();
    if (cpu >= 0 && cpu < CL_MAX_CORES) atomic_fetch_add_explicit(&spent[cpu], d, memory_order_relaxed);
    const int at = record();
    if (at >= 0) atomic_fetch_add_explicit(&tid_ns[at], d, memory_order_relaxed);
}

void cl_end(void)
{
    if (depth == 0) return;
    if (depth == 1) cl_tick();
    depth--;
}

int cl_take_ns(int64_t * ns, int cores)
{
    int n = cores < CL_MAX_CORES ? cores : CL_MAX_CORES;
    if (n < 0) n = 0;
    for (int i = 0; i < n; i++) ns[i] = atomic_exchange(&spent[i], 0);
    return n;
}

int cl_take_threads(int32_t * out, int64_t * ns, int max)
{
    atomic_fetch_add(&generation, 1);
    int n = atomic_exchange(&tid_count, 0);
    if (n > CL_MAX_TIDS) n = CL_MAX_TIDS;
    int copied = 0;
    for (int i = 0; i < n; i++) {
        const int32_t tid = atomic_load(&tids[i]);
        const int64_t spent_ns = atomic_exchange(&tid_ns[i], 0);
        if (copied < max) {
            out[copied] = tid;
            ns[copied] = spent_ns;
            copied++;
        }
    }
    return copied;
}
