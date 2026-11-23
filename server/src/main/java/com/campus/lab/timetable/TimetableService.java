package com.campus.lab.timetable;

import com.campus.lab.common.BusinessException;
import com.campus.lab.period.PeriodCatalog;
import com.campus.lab.period.PeriodSchedule;
import com.campus.lab.reservation.LabLockKey;
import com.campus.lab.reservation.LockManager;
import com.campus.lab.reservation.ReservationLock;
import com.campus.lab.reservation.TimeSlotMask;
import com.campus.lab.resource.dto.LabView;
import com.campus.lab.timetable.dto.MyTimetableSessionView;
import com.campus.lab.timetable.dto.SaveSemesterRequest;
import com.campus.lab.timetable.dto.SaveTimetableEntryRequest;
import com.campus.lab.timetable.dto.SemesterView;
import com.campus.lab.timetable.dto.TimetableEntryView;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TimetableService {

    private final TimetableRepository repository;
    private final TimetableTransactionService transactionService;
    private final PeriodCatalog periodCatalog;
    private final LockManager lockManager;
    private final Clock clock;

    public TimetableService(
            TimetableRepository repository,
            TimetableTransactionService transactionService,
            PeriodCatalog periodCatalog,
            LockManager lockManager,
            Clock clock) {
        this.repository = repository;
        this.transactionService = transactionService;
        this.periodCatalog = periodCatalog;
        this.lockManager = lockManager;
        this.clock = clock;
    }

    public List<SemesterView> listSemesters() {
        return repository.listSemesters();
    }

    public SemesterView createSemester(SaveSemesterRequest request) {
        String code = required(request.code(), "学期代码", 32);
        String name = required(request.name(), "学期名称", 128);
        requireMonday(request.week1Monday());
        if (repository.semesterCodeTaken(code, null)) {
            throw new BusinessException(HttpStatus.CONFLICT, "学期代码已存在");
        }
        long semesterId = repository.insertSemester(code, name, request.week1Monday(), request.weekCount());
        return toView(repository.findSemester(semesterId));
    }

    public SemesterView updateSemester(long semesterId, SaveSemesterRequest request) {
        TimetableRepository.SemesterRow current = requireSemester(semesterId);
        String code = required(request.code(), "学期代码", 32);
        String name = required(request.name(), "学期名称", 128);
        requireMonday(request.week1Monday());
        boolean calendarChanged = !current.week1Monday().equals(request.week1Monday())
                || current.weekCount() != request.weekCount();
        if (calendarChanged && repository.countEntries(semesterId) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "已有课表，不能修改学期周历");
        }
        if (repository.semesterCodeTaken(code, semesterId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "学期代码已存在");
        }
        repository.updateSemester(semesterId, code, name, request.week1Monday(), request.weekCount());
        return toView(repository.findSemester(semesterId));
    }

    public List<LabView> writableLabs(long userId, boolean sysAdmin) {
        return repository.listWritableLabs(sysAdmin ? null : userId);
    }

    public List<TimetableEntryView> listEntries(long userId, boolean sysAdmin, Long semesterId, Long labId) {
        if (labId != null) {
            requireWriter(userId, sysAdmin, labId);
        }
        return repository.list(semesterId, labId, sysAdmin ? null : userId, null);
    }

    public TimetableEntryView getEntry(long userId, boolean sysAdmin, long entryId) {
        TimetableEntryView entry = requireEntry(entryId);
        requireWriter(userId, sysAdmin, entry.labId());
        return entry;
    }

    public TimetableEntryView create(long userId, boolean sysAdmin, SaveTimetableEntryRequest request) {
        TimetableDraft draft = prepare(userId, sysAdmin, 0, request);
        List<Long> ids = commit(userId, List.of(draft));
        return repository.findEntry(ids.getFirst());
    }

    public List<TimetableEntryView> importExcel(long userId, boolean sysAdmin, MultipartFile file) {
        List<TimetableDraft> drafts = new ArrayList<>();
        for (TimetableExcel.ImportRow row : TimetableExcel.parse(file)) {
            TimetableRepository.SemesterRow semester = repository.findSemesterByCode(row.semesterCode());
            if (semester == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "第" + row.rowNumber() + "行：学期不存在");
            }
            Long labId = repository.labIdByCode(row.labCode());
            if (labId == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "第" + row.rowNumber() + "行：实验室不存在");
            }
            drafts.add(prepare(userId, sysAdmin, row.rowNumber(), new SaveTimetableEntryRequest(
                    semester.semesterId(), labId, row.courseName(), row.teacherAccount(), row.className(),
                    row.dayOfWeek(), row.startPeriod(), row.endPeriod(), row.weekStart(), row.weekEnd(),
                    row.studentAccounts())));
        }
        List<Long> ids = commit(userId, drafts);
        return ids.stream().map(repository::findEntry).toList();
    }

    public TimetableEntryView addRoster(
            long userId, boolean sysAdmin, long entryId, List<String> studentAccounts) {
        TimetableEntryView entry = requireEntry(entryId);
        requireWriter(userId, sysAdmin, entry.labId());
        List<String> accounts = accounts(studentAccounts);
        if (accounts.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请填写学生账号");
        }
        for (String account : accounts) {
            Long studentId = repository.activeUserId(account, "STUDENT");
            if (studentId == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "学生账号不存在或不是在读学生：" + account);
            }
            repository.insertRoster(entryId, studentId);
        }
        return repository.findEntry(entryId);
    }

    public TimetableEntryView removeRoster(long userId, boolean sysAdmin, long entryId, long studentUserId) {
        TimetableEntryView entry = requireEntry(entryId);
        requireWriter(userId, sysAdmin, entry.labId());
        if (repository.deleteRoster(entryId, studentUserId) != 1) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "名单中没有该学生");
        }
        return repository.findEntry(entryId);
    }

    public List<MyTimetableSessionView> mine(long userId) {
        LocalDate today = LocalDate.now(clock);
        return repository.mySessions(userId, today, today.plusDays(7));
    }

    private List<Long> commit(long operatorId, List<TimetableDraft> drafts) {
        List<LabLockKey> keys = drafts.stream()
                .flatMap(draft -> draft.sessions().stream()
                        .map(session -> new LabLockKey(draft.labId(), session.date())))
                .toList();
        List<ReservationLock> locks = lockManager.acquireAll(keys);
        try {
            return transactionService.commit(operatorId, drafts);
        } finally {
            for (int index = locks.size() - 1; index >= 0; index--) {
                try {
                    lockManager.release(locks.get(index));
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private TimetableDraft prepare(long userId, boolean sysAdmin, int rowNumber, SaveTimetableEntryRequest request) {
        String prefix = rowNumber == 0 ? "" : "第" + rowNumber + "行：";
        TimetableRepository.SemesterRow semester = requireSemester(request.semesterId());
        if (!"ACTIVE".equals(semester.status())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, prefix + "学期已归档");
        }
        requireWriter(userId, sysAdmin, request.labId());
        String courseName = required(request.courseName(), prefix + "课程名", 128);
        String className = required(request.className(), prefix + "班级", 128);
        String teacherAccount = required(request.teacherAccount(), prefix + "教师账号", 32);
        Long teacherId = repository.activeUserId(teacherAccount, "TEACHER");
        if (teacherId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, prefix + "教师账号不存在或不是在职教师");
        }
        int dayOfWeek = request.dayOfWeek();
        int startPeriod = request.startPeriod();
        int endPeriod = request.endPeriod();
        int weekStart = request.weekStart();
        int weekEnd = request.weekEnd();
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, prefix + "星期必须在1到7之间");
        }
        if (weekStart < 1 || weekEnd < weekStart || weekEnd > semester.weekCount()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, prefix + "周次超出学期范围");
        }
        PeriodSchedule schedule;
        try {
            schedule = periodCatalog.forLab(request.labId());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.NOT_FOUND, prefix + "实验室不存在");
        }
        if (startPeriod < 1 || endPeriod < startPeriod || endPeriod > schedule.slotCount()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, prefix + "节次超出该实验室节次表");
        }
        TimeSlotMask mask = TimeSlotMask.of(startPeriod - 1, endPeriod);
        LocalTime start = schedule.startTime(mask.startSlot());
        LocalTime end = schedule.endTime(mask.endSlot());
        List<Long> studentIds = new ArrayList<>();
        for (String account : accounts(request.studentAccounts())) {
            Long studentId = repository.activeUserId(account, "STUDENT");
            if (studentId == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, prefix + "学生账号不存在或不是在读学生：" + account);
            }
            studentIds.add(studentId);
        }
        List<LocalDate> dates = SemesterCalendar.dates(
                semester.week1Monday(), weekStart, weekEnd, dayOfWeek);
        List<TimetableDraft.Session> sessions = new ArrayList<>();
        for (int index = 0; index < dates.size(); index++) {
            sessions.add(new TimetableDraft.Session(
                    weekStart + index, dates.get(index), mask, start, end));
        }
        return new TimetableDraft(
                rowNumber, semester.semesterId(), request.labId(), courseName, teacherId, className,
                dayOfWeek, startPeriod, endPeriod, weekStart, weekEnd, studentIds, sessions);
    }

    private void requireWriter(long userId, boolean sysAdmin, long labId) {
        if (sysAdmin) {
            return;
        }
        if (!repository.administers(userId, labId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只能维护自己管理的实验室课表");
        }
    }

    private TimetableRepository.SemesterRow requireSemester(long semesterId) {
        TimetableRepository.SemesterRow semester = repository.findSemester(semesterId);
        if (semester == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "学期不存在");
        }
        return semester;
    }

    private TimetableEntryView requireEntry(long entryId) {
        TimetableEntryView entry = repository.findEntry(entryId);
        if (entry == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "课表不存在");
        }
        return entry;
    }

    private static void requireMonday(LocalDate week1Monday) {
        if (week1Monday == null || week1Monday.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "第一周起始日必须是周一");
        }
    }

    private static SemesterView toView(TimetableRepository.SemesterRow row) {
        return new SemesterView(
                row.semesterId(), row.code(), row.name(), row.week1Monday(), row.weekCount(), row.status());
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, label + "不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, label + "过长");
        }
        return trimmed;
    }

    private static List<String> accounts(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            unique.add(value.trim());
        }
        return List.copyOf(unique);
    }
}
