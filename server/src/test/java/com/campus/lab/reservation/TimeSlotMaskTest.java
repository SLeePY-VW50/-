package com.campus.lab.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.lab.period.DongfangPeriodTable;
import com.campus.lab.period.PeriodSchedule;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class TimeSlotMaskTest {

    private final PeriodSchedule schedule = DongfangPeriodTable.schedule();

    @Test
    void createsContiguousMaskUsingExclusiveEndSlot() {
        assertThat(TimeSlotMask.of(0, 1)).isEqualTo(new TimeSlotMask(0, 1, 1L));
        assertThat(TimeSlotMask.of(1, 3).value()).isEqualTo((1L << 1) | (1L << 2));
        assertThat(TimeSlotMask.of(0, 8).value()).isEqualTo(255L);
    }

    @Test
    void periodBoundariesCoverWholePeriodsIncludingTheBreakBetweenThem() {
        TimeSlotMask crossed = schedule.between(LocalTime.of(9, 15), LocalTime.of(10, 50));

        assertThat(crossed).isEqualTo(TimeSlotMask.of(1, 3));
        assertThat(crossed.periodCount()).isEqualTo(2);
        assertThat(schedule.endTime(crossed.endSlot())).isEqualTo(LocalTime.of(10, 50));
    }

    @Test
    void fullDefaultDayUsesThirteenBits() {
        TimeSlotMask day = schedule.between(LocalTime.of(8, 30), LocalTime.of(20, 50));

        assertThat(day.startSlot()).isZero();
        assertThat(day.endSlot()).isEqualTo(13);
        assertThat(day.value()).isEqualTo(8191L);
    }

    @Test
    void rejectsTimesThatAreNotPeriodBoundaries() {
        assertThatThrownBy(() -> schedule.between(LocalTime.of(9, 0), LocalTime.of(10, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("节次");
        assertThatThrownBy(() -> schedule.between(LocalTime.of(8, 30), LocalTime.of(9, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule.between(LocalTime.of(8, 30), LocalTime.of(8, 30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyAndOutOfRangeSlotMasks() {
        assertThatThrownBy(() -> TimeSlotMask.of(1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimeSlotMask.of(0, 64)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeSlotMask(0, 1, 2L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void containsAndOverlapsFollowSetSemantics() {
        TimeSlotMask second = TimeSlotMask.of(1, 2);
        TimeSlotMask secondAndThird = TimeSlotMask.of(1, 3);
        TimeSlotMask third = TimeSlotMask.of(2, 3);
        TimeSlotMask evening = TimeSlotMask.of(10, 11);

        assertThat(secondAndThird.contains(second)).isTrue();
        assertThat(second.contains(secondAndThird)).isFalse();
        assertThat(second.overlaps(third)).isFalse();
        assertThat(second.overlaps(secondAndThird)).isTrue();
        assertThat(second.overlaps(evening)).isFalse();
    }

    @Test
    void defaultOpenMaskClosesEveningPeriods() {
        long open = schedule.bookableMask(LocalTime.of(8, 30), LocalTime.of(17, 30), 1, 10);

        assertThat(open).isEqualTo((1L << 10) - 1);
        assertThat(open & (1L << 10)).isZero();
        assertThat(open & (1L << 12)).isZero();
    }

    @Test
    void earlyCheckoutReleasesPeriodsThatHaveNotStarted() {
        assertThat(schedule.firstReleasableSlot(LocalTime.of(9, 20))).isEqualTo(2);
        assertThat(schedule.firstReleasableSlot(LocalTime.of(10, 2))).isEqualTo(2);
        assertThat(schedule.firstReleasableSlot(LocalTime.of(9, 15))).isEqualTo(2);
    }
}
