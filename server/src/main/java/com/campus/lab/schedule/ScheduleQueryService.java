package com.campus.lab.schedule;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.dto.PageView;
import com.campus.lab.schedule.dto.ScheduleBatchDetailView;
import com.campus.lab.schedule.dto.ScheduleBatchView;
import com.campus.lab.schedule.dto.ScheduleResultView;
import com.campus.lab.schedule.dto.ScheduleSnapshotMetadataView;
import com.campus.lab.schedule.dto.ScheduleTaskView;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleQueryService {

    private final ScheduleRepository scheduleRepository;
    private final ScheduleQueryRepository queryRepository;

    public ScheduleQueryService(
            ScheduleRepository scheduleRepository, ScheduleQueryRepository queryRepository) {
        this.scheduleRepository = scheduleRepository;
        this.queryRepository = queryRepository;
    }

    @Transactional(readOnly = true)
    public PageView<ScheduleBatchView> listBatches(int page, int size) {
        if (page < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "页码不能为负数");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "每页大小必须在1到100之间");
        }
        return new PageView<>(
                queryRepository.listBatches(page, size), page, size, queryRepository.countBatches());
    }

    @Transactional(readOnly = true)
    public ScheduleBatchDetailView getBatch(String batchNo) {
        ScheduleBatchView batch = queryRepository.findBatch(batchNo);
        if (batch == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "排期批次不存在");
        }
        List<ScheduleTaskView> tasks = scheduleRepository.listTasks(batchNo).stream()
                .map(this::taskView).toList();
        return new ScheduleBatchDetailView(
                batch.batchNo(), batch.datasetId(), batch.requestDate(), batch.primaryTaskId(),
                batch.primaryStatus(), batch.createdAt(), batch.publishedAt(), tasks);
    }

    @Transactional(readOnly = true)
    public ScheduleTaskView getTask(long taskId) {
        ScheduleRepository.ScheduleTask task = scheduleRepository.findTask(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "排期任务不存在");
        }
        return taskView(task);
    }

    private ScheduleTaskView taskView(ScheduleRepository.ScheduleTask task) {
        List<ScheduleResultView> results = queryRepository.listResults(task.taskId());
        LocalDate requestDate = LocalDate.parse(
                queryRepository.readTree(task.demandSnapshot()).path("date").asText());
        ScheduleSnapshotMetadataView metadata = new ScheduleSnapshotMetadataView(
                task.datasetId(), requestDate,
                scheduleRepository.snapshotDemandIds(task.demandSnapshot()).size(),
                queryRepository.readTree(task.resourceSnapshot()).size());
        LocalDateTime publishedAt = results.stream()
                .map(ScheduleResultView::publishedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return new ScheduleTaskView(
                task.taskId(), task.scheduleBatchNo(), task.algorithmType(), task.primary(),
                task.datasetId(), task.status(), task.progress(), task.startedAt(), task.finishedAt(),
                task.durationMs(), task.errorMessage(), task.randomSeed(), task.repeatIndex(),
                queryRepository.readTree(task.parameters()),
                queryRepository.readTree(task.runtimeEnvironment()), metadata,
                queryRepository.findExperiment(task.taskId()), results, publishedAt);
    }
}
