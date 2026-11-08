package com.campus.lab.schedule;

import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchedulePublicationFailureService {

    private final SchedulePublicationRepository repository;
    private final Clock clock;

    public SchedulePublicationFailureService(SchedulePublicationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailed(long taskId, String reason) {
        repository.markPublishFailed(taskId, reason, LocalDateTime.now(clock));
    }
}
