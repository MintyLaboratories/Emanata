// sched_setaffinity and cpu_set_t.
#define _GNU_SOURCE

#include "jxl_runner.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>

struct jr_pool {
    pthread_mutex_t lock;
    pthread_cond_t go;
    pthread_cond_t idle;
    // The run in progress: its function, opaque, next value to hand out and end.
    JxlParallelRunFunction func;
    void * opaque;
    atomic_uint_fast32_t next;
    uint32_t end;
    // Bumped by every run; workers wait for it to change.
    unsigned generation;
    // Workers still in the current run.
    int busy;
    int stop;
    atomic_int canceled;
    uint64_t cores;
    int workers;
    int started;
    pthread_t threads[];
};

typedef struct {
    jr_pool * pool;
    size_t id;
} worker_arg;

int jr_pin(uint64_t cores, cpu_set_t * kept)
{
    if (cores == 0 || sched_getaffinity(0, sizeof(*kept), kept) != 0) return 0;
    cpu_set_t want;
    CPU_ZERO(&want);
    for (int n = 0; n < 64 && n < CPU_SETSIZE; n++) {
        if ((cores >> n) & 1) CPU_SET(n, &want);
    }
    return sched_setaffinity(0, sizeof(want), &want) == 0;
}

// Hands out the run's values until none are left, as thread `id`.
static void drain(jr_pool * p, JxlParallelRunFunction func, void * opaque, uint32_t end, size_t id)
{
    for (;;) {
        const uint32_t v = (uint32_t)atomic_fetch_add_explicit(&p->next, 1, memory_order_relaxed);
        if (v >= end) return;
        func(opaque, v, id);
    }
}

static void * work(void * argument)
{
    worker_arg * a = argument;
    jr_pool * p = a->pool;
    const size_t id = a->id;
    free(a);
    char name[16];
    snprintf(name, sizeof(name), "jxl-decode-%zu", id);
    pthread_setname_np(pthread_self(), name);
    cpu_set_t kept;
    jr_pin(p->cores, &kept);
    unsigned seen = 0;
    pthread_mutex_lock(&p->lock);
    for (;;) {
        while (p->generation == seen && !p->stop) pthread_cond_wait(&p->go, &p->lock);
        if (p->stop) break;
        seen = p->generation;
        JxlParallelRunFunction func = p->func;
        void * opaque = p->opaque;
        const uint32_t end = p->end;
        pthread_mutex_unlock(&p->lock);
        drain(p, func, opaque, end, id);
        pthread_mutex_lock(&p->lock);
        if (--p->busy == 0) pthread_cond_signal(&p->idle);
    }
    pthread_mutex_unlock(&p->lock);
    return NULL;
}

jr_pool * jr_create(int workers, uint64_t cores)
{
    if (workers < 0) workers = 0;
    jr_pool * p = calloc(1, sizeof(*p) + (size_t)workers * sizeof(pthread_t));
    if (p == NULL) return NULL;
    pthread_mutex_init(&p->lock, NULL);
    pthread_cond_init(&p->go, NULL);
    pthread_cond_init(&p->idle, NULL);
    p->cores = cores;
    p->workers = workers;
    for (int i = 0; i < workers; i++) {
        worker_arg * a = malloc(sizeof(*a));
        if (a == NULL) break;
        a->pool = p;
        a->id = (size_t)i + 1;
        if (pthread_create(&p->threads[i], NULL, work, a) != 0) {
            free(a);
            break;
        }
        p->started++;
    }
    if (p->started < workers) {
        jr_destroy(p);
        return NULL;
    }
    return p;
}

void jr_destroy(jr_pool * p)
{
    if (p == NULL) return;
    pthread_mutex_lock(&p->lock);
    p->stop = 1;
    pthread_cond_broadcast(&p->go);
    pthread_mutex_unlock(&p->lock);
    for (int i = 0; i < p->started; i++) pthread_join(p->threads[i], NULL);
    pthread_cond_destroy(&p->idle);
    pthread_cond_destroy(&p->go);
    pthread_mutex_destroy(&p->lock);
    free(p);
}

void jr_cancel(jr_pool * p)
{
    atomic_store(&p->canceled, 1);
}

JxlParallelRetCode jr_run(void * pool, void * opaque, JxlParallelRunInit init, JxlParallelRunFunction func, uint32_t start, uint32_t end)
{
    jr_pool * p = pool;
    if (atomic_load(&p->canceled)) return JXL_PARALLEL_RET_RUNNER_ERROR;
    if (start >= end) return JXL_PARALLEL_RET_SUCCESS;
    // One value, or no workers: the caller alone, without waking anyone.
    if (end - start == 1 || p->started == 0) {
        const JxlParallelRetCode r = init(opaque, 1);
        if (r != JXL_PARALLEL_RET_SUCCESS) return r;
        for (uint32_t v = start; v < end; v++) func(opaque, v, 0);
        return JXL_PARALLEL_RET_SUCCESS;
    }
    const JxlParallelRetCode r = init(opaque, (size_t)p->started + 1);
    if (r != JXL_PARALLEL_RET_SUCCESS) return r;
    pthread_mutex_lock(&p->lock);
    p->func = func;
    p->opaque = opaque;
    atomic_store_explicit(&p->next, start, memory_order_relaxed);
    p->end = end;
    p->busy = p->started;
    p->generation++;
    pthread_cond_broadcast(&p->go);
    pthread_mutex_unlock(&p->lock);
    drain(p, func, opaque, end, 0);
    pthread_mutex_lock(&p->lock);
    while (p->busy > 0) pthread_cond_wait(&p->idle, &p->lock);
    pthread_mutex_unlock(&p->lock);
    return JXL_PARALLEL_RET_SUCCESS;
}
