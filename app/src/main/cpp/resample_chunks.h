// The order of a GPU band's chunks, apart from GL (resample_chunks.c), so host tests can
// check it: which slot each chunk takes, and when each is started, copied out or dropped.

#ifndef RESAMPLE_CHUNKS_H
#define RESAMPLE_CHUNKS_H

#include "resample.h"

// Most slots a band may take turns on.
#define RS_SLOTS_MAX 4

// What a band does with one chunk; ctx is the caller's.
typedef struct {
    // Starts output rows y0 until y1 in slot, which has no chunk in flight; 0 on success,
    // with nothing left in flight otherwise.
    int (*submit)(void * ctx, int slot, int y0, int y1);
    // Waits for slot's chunk in flight, then copies its rows out; 0 on success. Either way
    // the slot has no chunk in flight after.
    int (*finish)(void * ctx, int slot);
    // Forgets slot's chunk in flight without waiting for it.
    void (*drop)(void * ctx, int slot);
} rs_chunk_ops;

// Output rows y0 until y1 in chunks that end on multiples of `chunk` (or at y1), taking
// slots 0..slots-1 in turn: a slot's chunk in flight is finished before the slot starts
// its next one, and the rest are finished in the order they started. stop (NULL: never)
// is asked before each start and each of those last finishes. Once stopped or failed, chunks in flight are
// dropped. 0 on success, -1 when an op failed, 1 once stopped; no slot has a chunk in
// flight on return.
int rs_chunks_run(const rs_chunk_ops * ops, void * ctx, int chunk, int slots, int y0, int y1, rs_stop stop, void * opaque);

#endif
