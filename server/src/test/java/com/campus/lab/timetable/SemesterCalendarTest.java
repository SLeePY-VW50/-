package com.campus.lab.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SemesterCalendarTest {

    @Test
    void expandsAWeekdayFromTheMondayOfWeekOne() {
        LocalDate week1Monday = LocalDate.of(2026, 10, 19);

        assertThat(SemesterCalendar.dates(week1Monday, 1, 2, 6)).containsExactly(
                LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 31));
        assertThat(SemesterCalendar.dates(week1Monday, 3, 3, 3))
                .containsExactly(LocalDate.of(2026, 11, 4));
    }

    @Test
    void rejectsAWeekOneThatDoesNotStartOnMonday() {
        assertThatThrownBy(() -> SemesterCalendar.dates(LocalDate.of(2026, 10, 20), 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
