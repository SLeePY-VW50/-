package com.campus.lab.reservation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class GreedySeatAllocator {

    public SeatAllocationResult allocate(List<SeatSnapshot> seats, ConflictQuery query) {
        Objects.requireNonNull(seats, "seats");
        Objects.requireNonNull(query, "query");
        if (query.mode() != ReservationMode.SEAT) {
            throw new IllegalArgumentException("工位分配仅支持SEAT模式");
        }
        int minimumSpecRank = query.minSpecLevel() == null ? 0 : specRank(query.minSpecLevel());
        if (query.minSpecLevel() != null && minimumSpecRank == 0) {
            throw new IllegalArgumentException("未知最低配置等级");
        }
        long requestedMask = query.timeMask().value();
        List<SeatSnapshot> candidates = seats.stream()
                .filter(seat -> seat.status() == ResourceStatus.ACTIVE)
                .filter(seat -> specRank(seat.specLevel()) >= minimumSpecRank)
                .filter(seat -> (seat.mask() & requestedMask) == 0)
                .sorted(Comparator.comparingInt(SeatSnapshot::seatIndex)
                        .thenComparingLong(SeatSnapshot::seatId))
                .toList();
        if (candidates.size() < query.seatCount()) {
            return SeatAllocationResult.failure("INSUFFICIENT_AVAILABLE_SEATS");
        }

        List<SeatSnapshot> selected = findPreferenceMatchingSegment(candidates, query);
        if (selected.isEmpty()) {
            selected = fallbackCandidates(candidates, query);
        }
        return SeatAllocationResult.success(selected.stream()
                .map(seat -> assignment(seat, query))
                .toList());
    }

    private List<SeatSnapshot> findPreferenceMatchingSegment(
            List<SeatSnapshot> candidates, ConflictQuery query) {
        int count = query.seatCount();
        List<SeatSnapshot> segment = new ArrayList<>(count);
        for (SeatSnapshot current : candidates) {
            if (!matchesSoftPreferences(current, query)) {
                segment.clear();
                continue;
            }
            if (!segment.isEmpty()) {
                SeatSnapshot previous = segment.getLast();
                if (!previous.zoneCode().equals(current.zoneCode())
                        || current.seatIndex() != previous.seatIndex() + 1) {
                    segment.clear();
                }
            }
            segment.add(current);
            if (segment.size() == count) {
                return List.copyOf(segment);
            }
        }
        return List.of();
    }

    private List<SeatSnapshot> fallbackCandidates(List<SeatSnapshot> candidates, ConflictQuery query) {
        Comparator<SeatSnapshot> comparator = Comparator
                .comparingInt((SeatSnapshot seat) -> specRank(seat.specLevel())).reversed()
                .thenComparingInt(seat -> query.preferNearWindow() && seat.nearWindow() ? 0 : 1)
                .thenComparingInt(SeatSnapshot::seatIndex)
                .thenComparingLong(SeatSnapshot::seatId);
        return candidates.stream().sorted(comparator).limit(query.seatCount()).toList();
    }

    private boolean matchesSoftPreferences(SeatSnapshot seat, ConflictQuery query) {
        return (query.preferredZone() == null || query.preferredZone().equals(seat.zoneCode()))
                && (!query.preferNearWindow() || seat.nearWindow());
    }

    private SeatAssignment assignment(SeatSnapshot seat, ConflictQuery query) {
        return new SeatAssignment(
                seat.seatId(), seat.seatCode(), seat.seatIndex(),
                query.preferredZone() != null && !query.preferredZone().equals(seat.zoneCode()),
                query.preferNearWindow() && !seat.nearWindow());
    }

    private static int specRank(String specLevel) {
        if (specLevel == null) {
            return 0;
        }
        return switch (specLevel) {
            case "STANDARD" -> 1;
            case "HIGH" -> 2;
            case "GPU" -> 3;
            default -> 0;
        };
    }
}
