package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.lab.schedule.ScheduleRepository;
import com.campus.lab.schedule.ScheduleService;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class ScheduleSqlEfficiencyIntegrationTest extends AbstractScheduleIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleService scheduleService;

    @MockitoSpyBean
    private JdbcTemplate jdbcTemplateSpy;

    @AfterEach
    void resetSpy() {
        Mockito.reset(jdbcTemplateSpy);
    }

    @Test
    void snapshotLoadsTwoLaboratoriesWithSixRepositoryQueries() {
        long firstLabId = createLaboratory(2);
        long secondLabId = createLaboratory(2);
        long firstUserId = userIdByAccount(createTestAccount("STUDENT"));
        long secondUserId = userIdByAccount(createTestAccount("STUDENT"));
        createPending(firstUserId, firstLabId, DATE);
        createPending(secondUserId, secondLabId, DATE);
        Mockito.clearInvocations(jdbcTemplateSpy);

        ScheduleRepository.ScheduleSnapshot snapshot = scheduleRepository.loadSnapshot(DATE);

        assertThat(snapshot.problem().resources()).hasSize(2);
        assertThat(repositoryQueryCount("ScheduleRepository")).isEqualTo(6);
    }

    @Test
    void publisherLoadsUserOverlapCandidatesOncePerBatch() {
        long labId = createLaboratory(2);
        long firstUserId = userIdByAccount(createTestAccount("STUDENT"));
        long secondUserId = userIdByAccount(createTestAccount("STUDENT"));
        createPending(firstUserId, labId, DATE);
        createPending(secondUserId, labId, DATE);
        Mockito.clearInvocations(jdbcTemplateSpy);

        scheduleService.runBatch(DATE);

        assertThat(repositoryQueryCount("SchedulePublicationRepository")).isEqualTo(1);
    }

    private long repositoryQueryCount(String repositoryName) {
        return Mockito.mockingDetails(jdbcTemplateSpy).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().startsWith("query"))
                .filter(invocation -> invocation.getLocation().toString().contains(repositoryName))
                .filter(invocation -> invocation.getArguments().length > 0
                        && invocation.getArgument(0) instanceof String)
                .filter(invocation -> repositoryName.equals("ScheduleRepository")
                        || invocation.getArgument(0).toString().contains(
                                "status IN ('PENDING_SCHEDULE', 'APPROVED', 'IN_USE')"))
                .count();
    }
}
