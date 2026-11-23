package com.campus.lab.schedule;

final class TimeFragments {

    private TimeFragments() {
    }

    static int fragmentedFreeSlots(long openMask, long occupiedMask, int minimumSlots, int slotCount) {
        int fragmented = 0;
        int run = 0;
        for (int slot = 0; slot < slotCount; slot++) {
            long bit = 1L << slot;
            if ((openMask & bit) != 0 && (occupiedMask & bit) == 0) {
                run++;
                continue;
            }
            if (run > 0 && run < minimumSlots) {
                fragmented += run;
            }
            run = 0;
        }
        if (run > 0 && run < minimumSlots) {
            fragmented += run;
        }
        return fragmented;
    }

    static int freeSlots(long openMask, long occupiedMask) {
        return Long.bitCount(openMask & ~occupiedMask);
    }

}
