package com.campus.lab.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private static final ReservationRuleConfig GLOBAL = new ReservationRuleConfig(30, 240, 1, 14);

    private final RuleValidator validator = new RuleValidator(
            Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), SHANGHAI));

    @Test
    void laboratoryOverridesTakePrecedenceOverGlobalConfiguration() {
        TimeRule laboratoryRule = rule(TODAY.plusDays(1), 60, 120, 2, 7);

        EffectiveTimeRule effective = validator.merge(laboratoryRule, GLOBAL);

        assertThat(effective.minDurationMinutes()).isEqualTo(60);
        assertThat(effective.maxDurationMinutes()).isEqualTo(120);
        assertThat(effective.advanceMinDays()).isEqualTo(2);
        assertThat(effective.advanceMaxDays()).isEqualTo(7);
        assertThat(effective.openTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(effective.closeTime()).isEqualTo(LocalTime.of(22, 0));
    }

    @Test
    void nullableLaboratoryOverridesFallBackToGlobalConfiguration() {
        EffectiveTimeRule effective = validator.merge(
                rule(TODAY.plusDays(1), null, null, null, null), GLOBAL);

        assertThat(effective.minDurationMinutes()).isEqualTo(30);
        assertThat(effective.maxDurationMinutes()).isEqualTo(240);
        assertThat(effective.advanceMinDays()).isEqualTo(1);
        assertThat(effective.advanceMaxDays()).isEqualTo(14);
    }

    @Test
    void openingAndClosingBoundariesAreInclusive() {
        LocalDate date = TODAY.plusDays(1);
        EffectiveTimeRule rule = validator.merge(rule(date, null, null, null, null), GLOBAL);

        assertThatCode(() -> validate(date, LocalTime.of(8, 0), LocalTime.of(9, 0), rule,
                ResourceStatus.ACTIVE, List.of())).doesNotThrowAnyException();
        assertThatCode(() -> validate(date, LocalTime.of(21, 30), LocalTime.of(22, 0), rule,
                ResourceStatus.ACTIVE, List.of())).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(date, LocalTime.of(7, 30), LocalTime.of(8, 30), rule,
                ResourceStatus.ACTIVE, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(date, LocalTime.of(21, 30), LocalTime.of(22, 30), rule,
                ResourceStatus.ACTIVE, List.of())).isInstanceOf(IllegalArgumentException.class);
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
    void durationUsesEffectiveMinimumAndMaximum() {
        LocalDate date = TODAY.plusDays(2);
        EffectiveTimeRule globalRule = validator.merge(rule(date, null, null, null, null), GLOBAL);
        EffectiveTimeRule minimumSixty = validator.merge(rule(date, 60, 120, null, null), GLOBAL);

        assertThatCode(() -> validate(date, LocalTime.of(8, 0), LocalTime.of(12, 0), globalRule,
                ResourceStatus.ACTIVE, List.of())).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(date, LocalTime.of(8, 0), LocalTime.of(12, 30), globalRule,
                ResourceStatus.ACTIVE, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(date, LocalTime.of(8, 0), LocalTime.of(8, 30), minimumSixty,
                ResourceStatus.ACTIVE, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(date, LocalTime.of(8, 0), LocalTime.of(8, 0), globalRule,
                ResourceStatus.ACTIVE, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void laboratoryMustBeActive() {
        LocalDate date = TODAY.plusDays(1);
        EffectiveTimeRule rule = validator.merge(rule(date, null, null, null, null), GLOBAL);

        assertThatThrownBy(() -> validate(date, LocalTime.of(8, 0), LocalTime.of(9, 0), rule,
                ResourceStatus.MAINTENANCE, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestedSeatsMustExistBelongToTheLabAndBeActive() {
        LocalDate date = TODAY.plusDays(1);
        EffectiveTimeRule rule = validator.merge(rule(date, null, null, null, null), GLOBAL);
        List<SeatSnapshot> seats = List.of(
                seat(11, ResourceStatus.ACTIVE),
                seat(12, ResourceStatus.DISABLED));

        assertThatCode(() -> validate(date, LocalTime.of(8, 0), LocalTime.of(9, 0), rule,
                ResourceStatus.ACTIVE, seats, 11L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(date, LocalTime.of(8, 0), LocalTime.of(9, 0), rule,
                ResourceStatus.ACTIVE, seats, 12L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(date, LocalTime.of(8, 0), LocalTime.of(9, 0), rule,
                ResourceStatus.ACTIVE, seats, 99L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mergedDurationsRejectMisalignmentOverflowAndOneSidedInvalidOverrides() {
        LocalDate date = TODAY.plusDays(1);

        assertThatThrownBy(() -> validator.merge(rule(date, 45, 90, null, null), GLOBAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30")
                .hasMessageContaining("240");
        assertThatThrownBy(() -> validator.merge(rule(date, 30, 300, null, null), GLOBAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30")
                .hasMessageContaining("240");
        assertThatThrownBy(() -> validator.merge(rule(date, 270, null, null, null), GLOBAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30")
                .hasMessageContaining("240");
    }

    @Test
    void mergedDurationsAcceptThirtyAndFourHourBoundaries() {
        EffectiveTimeRule effective = validator.merge(
                rule(TODAY.plusDays(1), 30, 240, null, null), GLOBAL);

        assertThat(effective.minDurationMinutes()).isEqualTo(30);
        assertThat(effective.maxDurationMinutes()).isEqualTo(240);
    }

    private void validateWithGlobalRule(LocalDate date) {
        EffectiveTimeRule rule = validator.merge(rule(date, null, null, null, null), GLOBAL);
        validate(date, LocalTime.of(8, 0), LocalTime.of(9, 0), rule,
                ResourceStatus.ACTIVE, List.of());
    }

    private void validate(
            LocalDate date, LocalTime start, LocalTime end, EffectiveTimeRule rule,
            ResourceStatus labStatus, List<SeatSnapshot> seats, Long... seatIds) {
        validator.validate(
                new RuleValidationRequest(1L, date, start, end, List.of(seatIds)),
                labStatus, rule, seats);
    }

    private static TimeRule rule(
            LocalDate requestDate, Integer minDuration, Integer maxDuration,
            Integer advanceMin, Integer advanceMax) {
        return new TimeRule(
                1L, 1L, requestDate.getDayOfWeek().getValue(),
                LocalTime.of(8, 0), LocalTime.of(22, 0),
                minDuration, maxDuration, advanceMin, advanceMax);
    }

    private static SeatSnapshot seat(long id, ResourceStatus status) {
        return new SeatSnapshot(id, "S" + id, "A", (int) id, "STANDARD", false, status, 0);
    }
}
