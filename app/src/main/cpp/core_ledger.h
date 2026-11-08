#ifndef CORE_LEDGER_H
#define CORE_LEDGER_H

#include <stdint.h>

// Where CPU time goes, core by core, while the ledger is open: each thread doing timed work
// counts its own CPU time between ticks against the core it is on at the tick. Calibration
// opens it to weigh CPU time by core capacity and to learn how much of each thread's CPU time
// was its work.
// Everything is a no-op while it is closed.

#define CL_MAX_CORES 64
#define CL_MAX_TIDS 128

// Opens (resetting everything) or closes the ledger.
void cl_open(int open);

// This thread starts counting (its id is recorded); calls nest, the outermost one counts.
void cl_begin(void);

// Charges this thread's CPU time since its last tick to the core it is on now.
void cl_tick(void);

// Ticks, then stops counting when the outermost cl_begin ends.
void cl_end(void);

// Copies up to `cores` cores' nanoseconds into `ns` and zeroes them; the count copied.
int cl_take_ns(int64_t * ns, int cores);

// Copies up to `max` ids of threads that counted since the last call into `tids`, the
// nanoseconds each counted into `ns`, and forgets them; the count copied.
int cl_take_threads(int32_t * tids, int64_t * ns, int max);

#endif
