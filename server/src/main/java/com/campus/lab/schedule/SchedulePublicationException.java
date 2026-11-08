package com.campus.lab.schedule;

public class SchedulePublicationException extends RuntimeException {

    public SchedulePublicationException(String reason) {
        super(reason);
    }

    public SchedulePublicationException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
