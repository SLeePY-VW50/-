package com.campus.lab.experiment;

import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.reservation.SeatSnapshot;
import com.campus.lab.resource.EffectiveTimeRule;
import com.campus.lab.schedule.ScheduleDemand;
import com.campus.lab.schedule.ScheduleLabResource;
import com.campus.lab.schedule.ScheduleProblem;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class SyntheticProblemFactory {

    static final LocalDate DATE = LocalDate.of(2026, 10, 26);
    private static final int LAB_COUNT = 8;
    private static final int SEATS_PER_LAB = 20;
    private static final long OPEN_MASK = ((1L << 28) - 1) << 16;

    ScheduleProblem generate(int dataScale, long seed) {
        Random random = new Random(seed);
        List<ScheduleLabResource> resources = new ArrayList<>();
        for (int labIndex = 0; labIndex < LAB_COUNT; labIndex++) {
            long labId = 9001L + labIndex;
            List<SeatSnapshot> seats = new ArrayList<>();
            for (int seatIndex = 1; seatIndex <= SEATS_PER_LAB; seatIndex++) {
                String specification = switch ((seatIndex - 1) % 3) {
                    case 0 -> "STANDARD";
                    case 1 -> "HIGH";
                    default -> "GPU";
                };
                seats.add(new SeatSnapshot(
                        labId * 100 + seatIndex, "S" + seatIndex,
                        seatIndex <= SEATS_PER_LAB / 2 ? "NORTH" : "SOUTH", seatIndex,
                        specification, seatIndex % 3 == 0, ResourceStatus.ACTIVE, 0));
            }
            EffectiveTimeRule rule = new EffectiveTimeRule(
                    labId, labId, DATE.getDayOfWeek().getValue(),
                    LocalTime.of(8, 0), LocalTime.of(22, 0), 30, 240, 0, 365);
            resources.add(new ScheduleLabResource(
                    labId, DATE, ResourceStatus.ACTIVE, SEATS_PER_LAB,
                    OPEN_MASK, 0, seats, rule));
        }

        List<ScheduleDemand> demands = new ArrayList<>();
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 1, 8, 0);
        for (int index = 0; index < dataScale; index++) {
            ReservationMode mode = index % 10 < 3 ? ReservationMode.ROOM : ReservationMode.SEAT;
            int duration = 30 * (1 + random.nextInt(4));
            int startSlot = 16 + random.nextInt(20);
            int windowEnd = Math.min(44, startSlot + duration / 30 + 1 + random.nextInt(4));
            int attendees = mode == ReservationMode.ROOM
                    ? 4 + random.nextInt(17) : 1 + random.nextInt(3);
            String minimumSpec = null;
            if (mode == ReservationMode.SEAT && random.nextBoolean()) {
                minimumSpec = switch (random.nextInt(3)) {
                    case 0 -> "STANDARD";
                    case 1 -> "HIGH";
                    default -> "GPU";
                };
            }
            String preferredZone = random.nextInt(4) == 0
                    ? (random.nextBoolean() ? "NORTH" : "SOUTH") : null;
            Set<Long> allowedLabs = random.nextInt(5) < 3
                    ? Set.of() : Set.of(9001L + random.nextInt(LAB_COUNT));
            demands.add(new ScheduleDemand(
                    index + 1L, 1 + random.nextInt(Math.max(10, dataScale / 2)),
                    createdAt.plusSeconds(index), DATE, startSlot * 30, windowEnd * 30,
                    duration, mode, attendees, mode == ReservationMode.SEAT ? attendees : 0,
                    60 + random.nextInt(41), minimumSpec, preferredZone, random.nextBoolean(),
                    allowedLabs));
        }
        return new ScheduleProblem(demands, resources);
    }
}
