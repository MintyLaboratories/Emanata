#include "resample_chunks.h"

int rs_chunks_run(const rs_chunk_ops * ops, void * ctx, int chunk, int slots, int y0, int y1, rs_stop stop, void * opaque)
{
    if (chunk < 1 || slots < 1 || slots > RS_SLOTS_MAX) return -1;
    int busy[RS_SLOTS_MAX] = { 0 };
    int r = 0;
    int slot = 0;
    for (int y = y0; y < y1;) {
        if (stop != NULL && stop(opaque)) {
            r = 1;
            break;
        }
        const int next = (y / chunk + 1) * chunk;
        const int end = next < y1 ? next : y1;
        if (busy[slot]) {
            busy[slot] = 0;
            const int f = ops->finish(ctx, slot, stop, opaque);
            if (f != 0) {
                r = f > 0 ? 1 : -1;
                break;
            }
        }
        if (ops->submit(ctx, slot, y, end) != 0) {
            r = -1;
            break;
        }
        busy[slot] = 1;
        slot = (slot + 1) % slots;
        y = end;
    }
    // The rest, oldest first: the next slot in turn holds the oldest chunk.
    for (int i = 0; i < slots; i++) {
        const int s = (slot + i) % slots;
        if (!busy[s]) continue;
        busy[s] = 0;
        if (r == 0 && stop != NULL && stop(opaque)) r = 1;
        if (r != 0) {
            ops->drop(ctx, s);
        } else {
            const int f = ops->finish(ctx, s, stop, opaque);
            if (f != 0) r = f > 0 ? 1 : -1;
        }
    }
    return r;
}
