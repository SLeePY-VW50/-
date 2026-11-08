package com.campus.lab.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TimeSlotMaskTest {

    @Test
    void createsContiguousMaskUsingExclusiveEndSlot() {
        assertThat(TimeSlotMask.of(0, 1))
                .isEqualTo(new TimeSlotMask(0, 1, 1L));
        assertThat(TimeSlotMask.of(16, 18).value())
                .isEqualTo((1L << 16) | (1L << 17));
        assertThat(TimeSlotMask.of(0, 8).value()).isEqualTo(255L);
    }

    @Test
    void convertsAlignedLocalTimesToSlots() {
        assertThat(TimeSlotMask.between(LocalTime.of(8, 0), LocalTime.of(9, 0)))
                .isEqualTo(TimeSlotMask.of(16, 18));
    }

    @Test
    void midnightEndMeans2400AndAllowsLastHalfHour() {
        TimeSlotMask mask = TimeSlotMask.between(LocalTime.of(23, 30), LocalTime.MIDNIGHT);

        assertThat(mask.startSlot()).isEqualTo(47);
        assertThat(mask.endSlot()).isEqualTo(48);
        assertThat(mask.value()).isEqualTo(1L << 47);
    }

    @Test
    void highestByteUsesOnlyLow48BitsWithoutWideShift() {
        assertThat(TimeSlotMask.of(40, 48).value()).isEqualTo(255L << 40);
        assertThat(TimeSlotMask.of(40, 48).value()).isPositive();
    }

    @ParameterizedTest
    @MethodSource("nonAlignedTimes")
    void rejectsNonAlignedStartOrEnd(LocalTime start, LocalTime end) {
        assertThatThrownBy(() -> TimeSlotMask.between(start, end))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> nonAlignedTimes() {
        return Stream.of(
                Arguments.of(LocalTime.of(8, 15), LocalTime.of(9, 0)),
                Arguments.of(LocalTime.of(8, 0), LocalTime.of(9, 15)),
                Arguments.of(LocalTime.of(8, 0, 1), LocalTime.of(9, 0)),
                Arguments.of(LocalTime.of(8, 0), LocalTime.of(9, 0, 1)),
                Arguments.of(LocalTime.of(8, 0, 0, 1), LocalTime.of(9, 0)),
                Arguments.of(LocalTime.of(8, 0), LocalTime.of(9, 0, 0, 1)));
    }

    @Test
    void rejectsCrossMidnightOtherThanMidnightEnd() {
        assertThatThrownBy(() -> TimeSlotMask.between(
                LocalTime.of(23, 30), LocalTime.of(0, 30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroLengthAndMoreThanFourHours() {
        assertThatThrownBy(() -> TimeSlotMask.of(1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimeSlotMask.between(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimeSlotMask.of(0, 9))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimeSlotMask.between(
                LocalTime.of(8, 0), LocalTime.of(12, 30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidSlotRanges")
    void rejectsInvalidSlotRangesAndInconsistentValues(int start, int end, long value) {
        assertThatThrownBy(() -> new TimeSlotMask(start, end, value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidSlotRanges() {
        return Stream.of(
                Arguments.of(-1, 1, 1L),
                Arguments.of(0, 49, 1L),
                Arguments.of(0, 1, 0L),
                Arguments.of(0, 1, 2L),
                Arguments.of(47, 48, -1L),
                Arguments.of(0, 2, 1L));
    }

    @Test
    void containsAndOverlapsFollowSetSemantics() {
        TimeSlotMask nineToTen = TimeSlotMask.of(18, 20);
        TimeSlotMask nineToEleven = TimeSlotMask.of(18, 22);
        TimeSlotMask tenToEleven = TimeSlotMask.of(20, 22);
        TimeSlotMask noon = TimeSlotMask.of(24, 26);

        assertThat(nineToEleven.contains(nineToTen)).isTrue();
        assertThat(nineToTen.contains(nineToEleven)).isFalse();
        assertThat(nineToTen.overlaps(tenToEleven)).isFalse();
        assertThat(nineToTen.overlaps(nineToEleven)).isTrue();
        assertThat(nineToTen.overlaps(noon)).isFalse();
    }
}
