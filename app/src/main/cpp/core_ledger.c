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
static atomic_int tid_count;

// This thread's nesting depth, and its CPU time at its last tick.
static _Thread_local int depth;
static _Thread_local int64_t last;

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
    atomic_store(&tid_count, 0);
    atomic_store(&opened, open != 0);
}

static void record(int tid)
{
    const int n = atomic_load(&tid_count);
    for (int i = 0; i < n && i < CL_MAX_TIDS; i++) {
        if (atomic_load(&tids[i]) == tid) return;
    }
    const int at = atomic_fetch_add(&tid_count, 1);
    if (at < CL_MAX_TIDS) atomic_store(&tids[at], tid);
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
    record(gettid());
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

int cl_take_tids(int32_t * out, int max)
{
    int n = atomic_exchange(&tid_count, 0);
    if (n > CL_MAX_TIDS) n = CL_MAX_TIDS;
    if (n > max) n = max;
    for (int i = 0; i < n; i++) out[i] = atomic_load(&tids[i]);
    return n;
}
