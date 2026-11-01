// A libjxl parallel runner (JxlParallelRunner) on a fixed set of threads pinned to chosen
// cores: the calling thread works as thread 0, `workers` more wait between runs.
#pragma once

#include <sched.h>
#include <stdint.h>

#include <jxl/parallel_runner.h>

typedef struct jr_pool jr_pool;

// A pool of `workers` threads (0 runs everything on the caller) on the cores set in
// `cores` (bit n = core n; 0 = any); null when out of memory or threads.
jr_pool * jr_create(int workers, uint64_t cores);

// Stops and joins the threads; the pool must not be running.
void jr_destroy(jr_pool * pool);

// Asks `stop(opaque)` on the calling thread before each run and between the values it
// hands itself; once it returns nonzero, the threads take no more values, and that run and
// every later one fail, so the decode stops (jr_canceled).
void jr_stop_when(jr_pool * pool, int (*stop)(void * opaque), void * opaque);

// True once `stop` stopped a run.
int jr_canceled(jr_pool * pool);

// The JxlParallelRunner, with the pool as its runner_opaque.
JxlParallelRetCode jr_run(void * pool, void * opaque, JxlParallelRunInit init, JxlParallelRunFunction func, uint32_t start, uint32_t end);

// Moves the calling thread onto `cores` (0 = leave it), keeping its old set in `kept`;
// false when it stays where it was (no cores asked, the system refuses them, or a pin of
// this thread is in effect: pins never nest, so an inner one cannot end the outer one).
int jr_pin(uint64_t cores, cpu_set_t * kept);

// Lets the calling thread run on every core again, ending a jr_pin. The set jr_pin kept
// leaves out cores the system had paused then: put back, it would keep the thread, and
// threads it starts later, off those cores for good.
void jr_unpin(void);

// A pin asked from Java: jr_pin to `cores`, returning the old set of the first 64 cores, 0
// when the thread stays where it was; every core's bit (all 64 set) ends a pin (jr_unpin), 0.
uint64_t jr_pin_mask(uint64_t cores);
