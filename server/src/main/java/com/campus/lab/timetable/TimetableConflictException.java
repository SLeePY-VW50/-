package com.campus.lab.timetable;

import com.campus.lab.timetable.dto.TimetableConflictView;
import java.util.List;

/** Rolls the occupancy transaction back while carrying the conflict list. */
public class TimetableConflictException extends RuntimeException {

    private final List<TimetableConflictView> conflicts;

    public TimetableConflictException(List<TimetableConflictView> conflicts) {
        super("课表与已有预约冲突，未写入");
        this.conflicts = List.copyOf(conflicts);
    }

    public List<TimetableConflictView> conflicts() {
        return conflicts;
    }
}
