package com.campus.lab.timetable;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.AllocationMode;
import com.campus.lab.reservation.BitmapConflictDetector;
import com.campus.lab.reservation.ConflictDecision;
import com.campus.lab.reservation.ConflictQuery;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationRepository;
import com.campus.lab.reservation.ReservationStatus;
import com.campus.lab.reservation.ResourceSnapshot;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.reservation.SeatSnapshot;
import com.campus.lab.reservation.dto.ReservationPreferences;
import com.campus.lab.reservation.NormalizedReservation;
import com.campus.lab.timetable.dto.TimetableConflictView;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TimetableTransactionService {

    private final TimetableRepository timetableRepository;
    private final ReservationRepository reservationRepository;
    private final BitmapConflictDetector conflictDetector = new BitmapConflictDetector();

    public TimetableTransactionService(
            TimetableRepository timetableRepository, ReservationRepository reservationRepository) {
        this.timetableRepository = timetableRepository;
        this.reservationRepository = reservationRepository;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<Long> commit(long operatorId, List<TimetableDraft> drafts) {
        Map<LabDate, List<Occ>> byDay = new LinkedHashMap<>();
        for (TimetableDraft draft : drafts) {
            for (TimetableDraft.Session session : draft.sessions()) {
                byDay.computeIfAbsent(new LabDate(draft.labId(), session.date()), key -> new ArrayList<>())
                        .add(new Occ(draft, session));
            }
        }
        List<LabDate> days = byDay.keySet().stream()
                .sorted(Comparator.comparingLong(LabDate::labId).thenComparing(LabDate::date))
                .toList();
        Map<LabDate, DayState> states = new LinkedHashMap<>();
        List<TimetableConflictView> conflicts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<TeacherClaim> teacherClaims = new ArrayList<>();
        for (LabDate day : days) {
            reservationRepository.ensureLabBitmap(day.labId(), day.date());
            ReservationRepository.LabBitmap room = reservationRepository.lockLabBitmap(day.labId(), day.date());
            ReservationRepository.LabDetails laboratory = reservationRepository.lockLabForUpdate(day.labId());
            if (laboratory.status() != ResourceStatus.ACTIVE) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "实验室不可用");
            }
            reservationRepository.ensureSeatBitmaps(day.labId(), day.date());
            List<ReservationRepository.SeatBitmap> seatBitmaps =
                    reservationRepository.loadSeatBitmaps(day.labId(), day.date());
            List<SeatSnapshot> snapshots = new ArrayList<>();
            List<SeatSnapshot> active = new ArrayList<>();
            for (ReservationRepository.SeatBitmap seat : seatBitmaps) {
                snapshots.add(seat.snapshot());
                if (seat.snapshot().status() == ResourceStatus.ACTIVE) {
                    active.add(seat.snapshot());
                }
            }
            if (active.isEmpty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "实验室没有可分配的ACTIVE工位");
            }
            states.put(day, new DayState(room.version(), active));
            List<Occ> accepted = new ArrayList<>();
            for (Occ occ : byDay.get(day)) {
                boolean blocked = false;
                ConflictDecision decision;
                try {
                    decision = conflictDetector.detect(
                            new ResourceSnapshot(room.mask(), snapshots),
                            new ConflictQuery(occ.session().mask(), ReservationMode.ROOM, 0, null, null, false));
                } catch (IllegalArgumentException exception) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, exception.getMessage());
                }
                if (!decision.available()) {
                    blocked = true;
                    List<TimetableConflictView> holders = timetableRepository.findHolders(
                            day.labId(), day.date(), occ.session().mask().value(),
                            occ.draft().rowNumber(), occ.session().weekNo(), occ.draft().dayOfWeek(),
                            occ.draft().startPeriod(), occ.draft().endPeriod());
                    if (holders.isEmpty()) {
                        add(conflicts, seen, unnamed(occ));
                    } else {
                        holders.forEach(holder -> add(conflicts, seen, holder));
                    }
                }
                for (Occ prior : accepted) {
                    if (prior.session().mask().overlaps(occ.session().mask())) {
                        blocked = true;
                        add(conflicts, seen, fileOverlap(occ, prior));
                    }
                }
                if (teacherBlocked(occ, teacherClaims, conflicts, seen)) {
                    blocked = true;
                }
                if (!blocked) {
                    accepted.add(occ);
                    teacherClaims.add(new TeacherClaim(
                            occ.draft().teacherUserId(), day.date(), occ.session().mask().value(),
                            occ.draft().rowNumber(), occ.draft().courseName()));
                }
            }
        }
        if (!conflicts.isEmpty()) {
            throw new TimetableConflictException(conflicts);
        }
        Map<LabDate, Long> versions = new LinkedHashMap<>();
        states.forEach((day, state) -> versions.put(day, state.version()));
        List<Long> entryIds = new ArrayList<>();
        for (TimetableDraft draft : drafts) {
            long entryId = timetableRepository.insertEntry(draft, operatorId);
            for (long studentId : draft.studentIds()) {
                timetableRepository.insertRoster(entryId, studentId);
            }
            for (TimetableDraft.Session session : draft.sessions()) {
                LabDate day = new LabDate(draft.labId(), session.date());
                long reservationId = writeRoom(
                        operatorId, draft, session, states.get(day).activeSeats(), versions.get(day));
                versions.put(day, versions.get(day) + 1);
                timetableRepository.insertSession(entryId, session, reservationId);
            }
            entryIds.add(entryId);
        }
        return entryIds;
    }

    private boolean teacherBlocked(
            Occ occ, List<TeacherClaim> claims, List<TimetableConflictView> conflicts, Set<String> seen) {
        boolean blocked = false;
        for (TeacherClaim claim : claims) {
            if (claim.teacherId() == occ.draft().teacherUserId()
                    && claim.date().equals(occ.session().date())
                    && (claim.mask() & occ.session().mask().value()) != 0) {
                blocked = true;
                add(conflicts, seen, teacherFileOverlap(occ, claim));
            }
        }
        for (TimetableConflictView existing : timetableRepository.findTeacherSessions(
                occ.draft().teacherUserId(), occ.session().date(), occ.session().mask().value(),
                occ.draft().rowNumber(), occ.session().weekNo(), occ.draft().dayOfWeek(),
                occ.draft().startPeriod(), occ.draft().endPeriod())) {
            blocked = true;
            add(conflicts, seen, existing);
        }
        return blocked;
    }

    private long writeRoom(
            long operatorId, TimetableDraft draft, TimetableDraft.Session session,
            List<SeatSnapshot> activeSeats, long version) {
        int duration = (int) ChronoUnit.MINUTES.between(session.start(), session.end());
        int attendees = Math.max(draft.studentIds().size(), 1);
        String purpose = draft.courseName() + " " + draft.className();
        if (purpose.length() > 500) {
            purpose = purpose.substring(0, 500);
        }
        NormalizedReservation request = new NormalizedReservation(
                AllocationMode.FIXED, draft.labId(), session.date(), session.start(), session.end(),
                duration, session.mask().periodCount(), ReservationMode.ROOM, attendees,
                activeSeats.size(), purpose, ReservationPreferences.empty(), session.mask());
        LocalDateTime start = session.date().atTime(session.start());
        LocalDateTime end = session.date().atTime(session.end());
        long reservationId = reservationRepository.insertReservation(
                draft.teacherUserId(), null, request, draft.labId(), start, end,
                activeSeats.size(), ReservationStatus.APPROVED);
        timetableRepository.markTimetableReservation(reservationId);
        for (SeatSnapshot seat : activeSeats) {
            reservationRepository.insertSeatAssignment(reservationId, seat.seatId(), session.mask().value());
        }
        reservationRepository.updateRoomMask(draft.labId(), session.date(), version, session.mask().value());
        reservationRepository.insertInitialLog(reservationId, operatorId, ReservationStatus.APPROVED);
        return reservationId;
    }

    private static void add(
            List<TimetableConflictView> conflicts, Set<String> seen, TimetableConflictView view) {
        String key = view.rowNumber() + "|" + view.date() + "|" + view.reservationId()
                + "|" + (view.reservationId() == 0 ? view.purpose() : "");
        if (seen.add(key)) {
            conflicts.add(view);
        }
    }

    private static TimetableConflictView unnamed(Occ occ) {
        return new TimetableConflictView(
                occ.draft().rowNumber(), occ.session().date(), occ.session().weekNo(),
                occ.draft().dayOfWeek(), occ.draft().startPeriod(), occ.draft().endPeriod(),
                0, 0, "", "未知占用", "该节次已有占用，但没有对应的有效预约",
                "", "", null, null);
    }

    private static TimetableConflictView fileOverlap(Occ occ, Occ prior) {
        String where = prior.draft().rowNumber() == 0
                ? "本次提交的另一条课表" : "第" + prior.draft().rowNumber() + "行";
        return new TimetableConflictView(
                occ.draft().rowNumber(), occ.session().date(), occ.session().weekNo(),
                occ.draft().dayOfWeek(), occ.draft().startPeriod(), occ.draft().endPeriod(),
                0, 0, "", "导入文件",
                "与" + where + "「" + prior.draft().courseName() + "」占用同一实验室节次",
                "TIMETABLE", "TIMETABLE", null, null);
    }

    private static TimetableConflictView teacherFileOverlap(Occ occ, TeacherClaim claim) {
        String where = claim.rowNumber() == 0 ? "本次提交的另一条课表" : "第" + claim.rowNumber() + "行";
        return new TimetableConflictView(
                occ.draft().rowNumber(), occ.session().date(), occ.session().weekNo(),
                occ.draft().dayOfWeek(), occ.draft().startPeriod(), occ.draft().endPeriod(),
                0, occ.draft().teacherUserId(), "", "任课教师",
                "与" + where + "「" + claim.courseName() + "」的任课教师时段重叠",
                "TIMETABLE", "TIMETABLE", null, null);
    }

    private record LabDate(long labId, LocalDate date) {
    }

    private record Occ(TimetableDraft draft, TimetableDraft.Session session) {
    }

    private record DayState(long version, List<SeatSnapshot> activeSeats) {
    }

    private record TeacherClaim(long teacherId, LocalDate date, long mask, int rowNumber, String courseName) {
    }
}
