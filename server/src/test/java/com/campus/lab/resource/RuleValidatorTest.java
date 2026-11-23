package com.campus.lab.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.lab.period.DongfangPeriodTable;
import com.campus.lab.period.PeriodSchedule;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.reservation.SeatSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleValidatorTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final ReservationRuleConfig GLOBAL = new ReservationRuleConfig(1, 5, 1, 14);
    private static final PeriodSchedule SCHEDULE = DongfangPeriodTable.schedule();

    private final RuleValidator validator = new RuleValidator(
            Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), SHANGHAI));

    @Test
    void laboratoryOverridesTakePrecedenceOverGlobalConfiguration() {
        TimeRule laboratoryRule = rule(TODAY.plusDays(1), 2, 4, 2, 7);

        EffectiveTimeRule effective = validator.merge(laboratoryRule, GLOBAL);

        assertThat(effective.minPeriods()).isEqualTo(2);
        assertThat(effective.maxPeriods()).isEqualTo(4);
        assertThat(effective.advanceMinDays()).isEqualTo(2);
        assertThat(effective.advanceMaxDays()).isEqualTo(7);
        assertThat(effective.openStartPeriod()).isEqualTo(1);
        assertThat(effective.openEndPeriod()).isEqualTo(10);
    }

    @Test
    void nullableLaboratoryOverridesFallBackToGlobalConfiguration() {
        EffectiveTimeRule effective = validator.merge(
                rule(TODAY.plusDays(1), null, null, null, null), GLOBAL);

        assertThat(effective.minPeriods()).isEqualTo(1);
        assertThat(effective.maxPeriods()).isEqualTo(5);
        assertThat(effective.advanceMinDays()).isEqualTo(1);
        assertThat(effective.advanceMaxDays()).isEqualTo(14);
    }

    @Test
    void openingBoundariesFollowPeriodStartsAndEnds() {
        LocalDate date = TODAY.plusDays(1);
        EffectiveTimeRule rule = validator.merge(rule(date, null, null, null, null), GLOBAL);

        assertThatCode(() -> validate(date, LocalTime.of(8, 30), LocalTime.of(9, 15), rule))
                .doesNotThrowAnyException();
        assertThatCode(() -> validate(date, LocalTime.of(16, 45), LocalTime.of(17, 30), rule))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(date, LocalTime.of(18, 30), LocalTime.of(19, 15), rule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("开放节次");
    }

    @Test
    void crossBreakSpanCountsAsConsecutivePeriods() {
        LocalDate date = TODAY.plusDays(1);
        EffectiveTimeRule rule = validator.merge(rule(date, null, null, null, null), GLOBAL);

        assertThatCode(() -> validate(date, LocalTime.of(9, 15), LocalTime.of(10, 50), rule))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(date, LocalTime.of(9, 0), LocalTime.of(10, 30), rule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("节次");
    }

    @Test
    void advanceDaysUseAnInclusiveConfiguredRange() {
        assertThatThrownBy(() -> validateWithGlobalRule(TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> validateWithGlobalRule(TODAY.plusDays(1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> validateWithGlobalRule(TODAY.plusDays(14)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validateWithGlobalRule(TODAY.plusDays(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void durationUsesEffectiveMinimumAndMaximumPeriods() {
        LocalDate date = TODAY.plusDays(2);
        EffectiveTimeRule globalRule = validator.merge(rule(date, null, null, null, null), GLOBAL);
        EffectiveTimeRule twoToThree = validator.merge(rule(date, 2, 3, null, null), GLOBAL);

        assertThatCode(() -> validate(date, LocalTime.of(8, 30), LocalTime.of(12, 30), globalRule))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(date, LocalTime.of(8, 30), LocalTime.of(16, 45), globalRule))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(date, LocalTime.of(8, 30), LocalTime.of(9, 15), twoToThree))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void laboratoryMustBeActive() {
        LocalDate date = TODAY.plusDays(1);
        EffectiveTimeRule rule = validator.merge(rule(date, null, null, null, null), GLOBAL);

        assertThatThrownBy(() -> validator.validate(
                new RuleValidationRequest(1L, date, LocalTime.of(8, 30), LocalTime.of(9, 15), List.of()),
                ResourceStatus.MAINTENANCE, rule, List.of(), SCHEDULE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestedSeatsMustExistBelongToTheLabAndBeActive() {
        LocalDate date = TODAY.plusDays(1);
        EffectiveTimeRule rule = validator.merge(rule(date, null, null, null, null), GLOBAL);
        List<SeatSnapshot> seats = List.of(
                seat(11, ResourceStatus.ACTIVE),
                seat(12, ResourceStatus.DISABLED));

        assertThatCode(() -> validate(date, LocalTime.of(8, 30), LocalTime.of(9, 15), rule, seats, 11L))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(date, LocalTime.of(8, 30), LocalTime.of(9, 15), rule, seats, 12L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(date, LocalTime.of(8, 30), LocalTime.of(9, 15), rule, seats, 99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mergedPeriodCountsRejectInvertedRanges() {
        LocalDate date = TODAY.plusDays(1);

        assertThatThrownBy(() -> validator.merge(rule(date, 4, 2, null, null), GLOBAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.merge(rule(date, 0, 5, null, null), GLOBAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void validateWithGlobalRule(LocalDate date) {
        EffectiveTimeRule rule = validator.merge(rule(date, null, null, null, null), GLOBAL);
        validate(date, LocalTime.of(8, 30), LocalTime.of(9, 15), rule);
    }

    private void validate(LocalDate date, LocalTime start, LocalTime end, EffectiveTimeRule rule) {
        validate(date, start, end, rule, List.of());
    }

    private void validate(
            LocalDate date, LocalTime start, LocalTime end, EffectiveTimeRule rule,
            List<SeatSnapshot> seats, Long... seatIds) {
        validator.validate(
                new RuleValidationRequest(1L, date, start, end, List.of(seatIds)),
                ResourceStatus.ACTIVE, rule, seats, SCHEDULE);
    }

    private static TimeRule rule(
            LocalDate requestDate, Integer minPeriods, Integer maxPeriods,
            Integer advanceMin, Integer advanceMax) {
        return new TimeRule(
                1L, 1L, requestDate.getDayOfWeek().getValue(),
                LocalTime.of(8, 30), LocalTime.of(17, 30), 1, 10,
                minPeriods, maxPeriods, advanceMin, advanceMax);
    }

    private static SeatSnapshot seat(long id, ResourceStatus status) {
        return new SeatSnapshot(id, "S" + id, "A", (int) id, "STANDARD", false, status, 0);
    }
}
