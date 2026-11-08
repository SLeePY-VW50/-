package com.campus.lab.schedule;

import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.reservation.SeatSnapshot;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

final class ScheduleResourceState {

    private final Map<LabDateKey, LabState> labs;
    private final ScheduleResourceState baseline;
    private final IdentityHashMap<LabState, LabState> changedLabs;
    private final Metrics baselineMetrics;
    private Metrics metricsDelta = Metrics.ZERO;
    private final Map<UserDateKey, Long> userMasks = new HashMap<>();

    ScheduleResourceState(ScheduleProblem problem) {
        labs = new HashMap<>();
        for (ScheduleLabResource resource : problem.resources()) {
            labs.put(new LabDateKey(resource.labId(), resource.date()), new LabState(resource));
        }
        baseline = null;
        changedLabs = null;
        baselineMetrics = computeBaselineMetrics();
    }

    private ScheduleResourceState(ScheduleResourceState baseline) {
        labs = null;
        this.baseline = baseline;
        changedLabs = new IdentityHashMap<>();
        baselineMetrics = null;
        userMasks.putAll(baseline.userMasks);
    }

    ScheduleResourceState copy() {
        return new ScheduleResourceState(this);
    }

    private LabState lab(LabDateKey key) {
        if (baseline == null) {
            return labs.get(key);
        }
        LabState baselineLab = baseline.lab(key);
        if (baselineLab == null) {
            return null;
        }
        LabState changed = changedLabs.get(baselineLab);
        return changed == null ? baselineLab : changed;
    }

    private LabState mutableLab(LabDateKey key) {
        if (baseline == null) {
            return labs.get(key);
        }
        LabState baselineLab = baseline.lab(key);
        return changedLabs.computeIfAbsent(baselineLab, LabState::new);
    }

    private Metrics computeBaselineMetrics() {
        Metrics total = Metrics.ZERO;
        for (LabDateKey key : labs.keySet()) {
            total = total.plus(metrics(key));
        }
        return total;
    }

    boolean canOccupy(ScheduleDemand demand, ScheduleCandidate candidate) {
        long requested = candidate.timeMask().value();
        UserDateKey userKey = new UserDateKey(demand.userId(), demand.date());
        if ((userMasks.getOrDefault(userKey, 0L) & requested) != 0) {
            return false;
        }
        LabState lab = lab(new LabDateKey(candidate.labId(), candidate.date()));
        if (lab == null || (lab.roomMask & requested) != 0) {
            return false;
        }
        if (demand.mode() == ReservationMode.ROOM) {
            if ((lab.allSeatMasks & requested) != 0) {
                return false;
            }
            for (int slot = candidate.startSlot(); slot < candidate.endSlot(); slot++) {
                if (plannedSeatCount(lab, slot) > 0) {
                    return false;
                }
            }
            return true;
        }
        int minimumSpec = ScheduleSpecRank.of(demand.minSpecLevel());
        for (int slot = candidate.startSlot(); slot < candidate.endSlot(); slot++) {
            for (int required = 0; required < 4; required++) {
                int requestedSeats = minimumSpec >= required ? demand.seatCount() : 0;
                if (plannedSeatCountAtLeast(lab, required, slot) + requestedSeats
                        > lab.initiallyFreeSeats[required][slot]) {
                    return false;
                }
            }
        }
        return true;
    }

    void occupy(ScheduleDemand demand, ScheduleCandidate candidate) {
        long requested = candidate.timeMask().value();
        userMasks.merge(new UserDateKey(demand.userId(), demand.date()), requested, (left, right) -> left | right);
        LabDateKey key = new LabDateKey(candidate.labId(), candidate.date());
        Metrics before = metrics(key);
        LabState lab = mutableLab(key);
        if (demand.mode() == ReservationMode.ROOM) {
            lab.roomMask |= requested;
            updateMetrics(key, before);
            return;
        }
        int minimumSpec = ScheduleSpecRank.of(demand.minSpecLevel());
        for (int slot = candidate.startSlot(); slot < candidate.endSlot(); slot++) {
            lab.plannedSeatsBySpec[minimumSpec][slot] += demand.seatCount();
        }
        updateMetrics(key, before);
    }

    ScheduleLabResource resource(ScheduleCandidate candidate) {
        return lab(new LabDateKey(candidate.labId(), candidate.date())).resource;
    }

    boolean hasUserConflict(ScheduleDemand demand, ScheduleCandidate candidate) {
        long requested = candidate.timeMask().value();
        return (userMasks.getOrDefault(new UserDateKey(demand.userId(), demand.date()), 0L)
                & requested) != 0;
    }

    void occupyActual(
            ScheduleDemand demand, ScheduleCandidate candidate,
            java.util.List<com.campus.lab.reservation.SeatAssignment> selectedSeats) {
        long requested = candidate.timeMask().value();
        userMasks.merge(new UserDateKey(demand.userId(), demand.date()), requested, (left, right) -> left | right);
        LabDateKey key = new LabDateKey(candidate.labId(), candidate.date());
        Metrics before = metrics(key);
        LabState lab = mutableLab(key);
        if (demand.mode() == ReservationMode.ROOM) {
            lab.roomMask |= requested;
            updateMetrics(key, before);
            return;
        }
        lab.copySeatMasksIfShared();
        for (com.campus.lab.reservation.SeatAssignment selected : selectedSeats) {
            for (int index = 0; index < lab.resource.seats().size(); index++) {
                if (lab.resource.seats().get(index).seatId() == selected.seatId()) {
                    for (int slot = candidate.startSlot(); slot < candidate.endSlot(); slot++) {
                        long bit = 1L << slot;
                        if (lab.resource.seats().get(index).status() == ResourceStatus.ACTIVE
                                && (lab.seatMasks[index] & bit) == 0) {
                            lab.activeOccupiedCounts[slot]++;
                        }
                    }
                    lab.seatMasks[index] |= requested;
                    lab.allSeatMasks |= requested;
                    break;
                }
            }
        }
        updateMetrics(key, before);
    }

    com.campus.lab.reservation.ResourceSnapshot snapshot(ScheduleCandidate candidate) {
        LabState lab = lab(new LabDateKey(candidate.labId(), candidate.date()));
        java.util.List<SeatSnapshot> seats = new java.util.ArrayList<>();
        for (int index = 0; index < lab.resource.seats().size(); index++) {
            SeatSnapshot seat = lab.resource.seats().get(index);
            seats.add(new SeatSnapshot(
                    seat.seatId(), seat.seatCode(), seat.zoneCode(), seat.seatIndex(),
                    seat.specLevel(), seat.nearWindow(), seat.status(), lab.seatMasks[index]));
        }
        return new com.campus.lab.reservation.ResourceSnapshot(lab.roomMask, seats);
    }

    long roomBlockingMask(ScheduleCandidate candidate) {
        return roomBlockingMask(new LabDateKey(candidate.labId(), candidate.date()));
    }

    double utilization() {
        return metrics().utilization();
    }

    double estimatedFragmentation() {
        return metrics().fragmentation();
    }

    private Metrics metrics() {
        Metrics total = baseline == null ? baselineMetrics : baseline.metrics();
        return total.plus(metricsDelta);
    }

    private Metrics metrics(LabDateKey key) {
        LabState lab = lab(key);
        if (lab == null || lab.resource.status() != ResourceStatus.ACTIVE) {
            return Metrics.ZERO;
        }
        long occupied = 0;
        long available = 0;
        for (int slot = 0; slot < 48; slot++) {
            long bit = 1L << slot;
            if ((lab.resource.openMask() & bit) == 0) {
                continue;
            }
            available += lab.activeSeatCount;
            if ((lab.roomMask & bit) != 0) {
                occupied += lab.activeSeatCount;
                continue;
            }
            occupied += lab.activeOccupiedCounts[slot];
            occupied += Math.min(
                    lab.activeSeatCount - lab.activeOccupiedCounts[slot], plannedSeatCount(lab, slot));
        }
        long roomOccupied = roomBlockingMask(key);
        int minimumSlots = minimumSlots(lab.resource);
        long free = TimeFragments.freeSlots(lab.resource.openMask(), roomOccupied);
        long fragmented = TimeFragments.fragmentedFreeSlots(
                lab.resource.openMask(), roomOccupied, minimumSlots);
        return new Metrics(occupied, available, fragmented, free);
    }

    private void updateMetrics(LabDateKey key, Metrics before) {
        metricsDelta = metricsDelta.plus(metrics(key).minus(before));
    }

    double actualFragmentation() {
        int free = 0;
        int fragmented = 0;
        for (LabState lab : labs.values()) {
            if (lab.resource.status() != ResourceStatus.ACTIVE) {
                continue;
            }
            int minimumSlots = minimumSlots(lab.resource);
            for (int index = 0; index < lab.resource.seats().size(); index++) {
                if (lab.resource.seats().get(index).status() != ResourceStatus.ACTIVE) {
                    continue;
                }
                long occupied = lab.roomMask | lab.seatMasks[index];
                free += TimeFragments.freeSlots(lab.resource.openMask(), occupied);
                fragmented += TimeFragments.fragmentedFreeSlots(
                        lab.resource.openMask(), occupied, minimumSlots);
            }
        }
        return free == 0 ? 0 : (double) fragmented / free;
    }

    private static int minimumSlots(ScheduleLabResource resource) {
        return resource.effectiveRule() == null
                ? 1 : Math.max(1, resource.effectiveRule().minDurationMinutes() / 30);
    }

    private static int plannedSeatCount(LabState lab, int slot) {
        return plannedSeatCountAtLeast(lab, 0, slot);
    }

    private static int plannedSeatCountAtLeast(LabState lab, int minimumSpec, int slot) {
        int count = 0;
        for (int spec = minimumSpec; spec < 4; spec++) {
            count += lab.plannedSeatsBySpec[spec][slot];
        }
        return count;
    }

    private long roomBlockingMask(LabDateKey key) {
        LabState lab = lab(key);
        long blocked = lab.roomMask | lab.allSeatMasks;
        for (int slot = 0; slot < 48; slot++) {
            if (plannedSeatCount(lab, slot) > 0) {
                blocked |= 1L << slot;
            }
        }
        return blocked;
    }

    private static final class LabState {
        private final ScheduleLabResource resource;
        private long roomMask;
        private long allSeatMasks;
        private long[] seatMasks;
        private boolean sharedSeatMasks;
        private final int activeSeatCount;
        private final int[] activeOccupiedCounts;
        private final int[][] plannedSeatsBySpec;
        private final int[][] initiallyFreeSeats;

        private LabState(ScheduleLabResource resource) {
            this.resource = resource;
            roomMask = resource.roomMask();
            seatMasks = new long[resource.seats().size()];
            activeOccupiedCounts = new int[48];
            plannedSeatsBySpec = new int[4][48];
            initiallyFreeSeats = new int[4][48];
            long union = 0;
            int activeCount = 0;
            for (int index = 0; index < resource.seats().size(); index++) {
                SeatSnapshot seat = resource.seats().get(index);
                seatMasks[index] = seat.mask();
                union |= seat.mask();
                if (seat.status() != ResourceStatus.ACTIVE) {
                    continue;
                }
                activeCount++;
                int spec = ScheduleSpecRank.of(seat.specLevel());
                for (int slot = 0; slot < 48; slot++) {
                    if ((seat.mask() & (1L << slot)) != 0) {
                        activeOccupiedCounts[slot]++;
                    } else {
                        for (int required = spec; required >= 0; required--) {
                            initiallyFreeSeats[required][slot]++;
                        }
                    }
                }
            }
            activeSeatCount = activeCount;
            allSeatMasks = union;
        }

        private LabState(LabState source) {
            resource = source.resource;
            roomMask = source.roomMask;
            allSeatMasks = source.allSeatMasks;
            seatMasks = source.seatMasks;
            sharedSeatMasks = true;
            activeSeatCount = source.activeSeatCount;
            activeOccupiedCounts = source.activeOccupiedCounts.clone();
            plannedSeatsBySpec = new int[4][];
            for (int spec = 0; spec < 4; spec++) {
                plannedSeatsBySpec[spec] = source.plannedSeatsBySpec[spec].clone();
            }
            initiallyFreeSeats = source.initiallyFreeSeats;
        }

        private void copySeatMasksIfShared() {
            if (sharedSeatMasks) {
                seatMasks = seatMasks.clone();
                sharedSeatMasks = false;
            }
        }
    }

    private record Metrics(long occupied, long available, long fragmented, long free) {
        private static final Metrics ZERO = new Metrics(0, 0, 0, 0);

        private Metrics plus(Metrics other) {
            return new Metrics(
                    occupied + other.occupied, available + other.available,
                    fragmented + other.fragmented, free + other.free);
        }

        private Metrics minus(Metrics other) {
            return new Metrics(
                    occupied - other.occupied, available - other.available,
                    fragmented - other.fragmented, free - other.free);
        }

        private double utilization() {
            return available == 0 ? 0 : (double) occupied / available;
        }

        private double fragmentation() {
            return free == 0 ? 0 : (double) fragmented / free;
        }
    }

    private record UserDateKey(long userId, java.time.LocalDate date) {
    }
}
