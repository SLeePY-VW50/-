package com.campus.lab.timetable;

import com.campus.lab.common.ApiResponse;
import com.campus.lab.resource.dto.LabView;
import com.campus.lab.timetable.dto.MyTimetableSessionView;
import com.campus.lab.timetable.dto.RosterAccountsRequest;
import com.campus.lab.timetable.dto.SaveSemesterRequest;
import com.campus.lab.timetable.dto.SaveTimetableEntryRequest;
import com.campus.lab.timetable.dto.SemesterView;
import com.campus.lab.timetable.dto.TimetableConflictReport;
import com.campus.lab.timetable.dto.TimetableEntryView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/timetable")
public class TimetableController {

    private final TimetableService service;

    public TimetableController(TimetableService service) {
        this.service = service;
    }

    @GetMapping("/semesters")
    @PreAuthorize("hasAnyRole('LAB_ADMIN','SYS_ADMIN')")
    public ApiResponse<List<SemesterView>> semesters() {
        return ApiResponse.success(service.listSemesters());
    }

    @PostMapping("/semesters")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public ApiResponse<SemesterView> createSemester(@Valid @RequestBody SaveSemesterRequest request) {
        return ApiResponse.success(service.createSemester(request));
    }

    @PutMapping("/semesters/{semesterId}")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public ApiResponse<SemesterView> updateSemester(
            @PathVariable long semesterId, @Valid @RequestBody SaveSemesterRequest request) {
        return ApiResponse.success(service.updateSemester(semesterId, request));
    }

    @GetMapping("/labs")
    @PreAuthorize("hasAnyRole('LAB_ADMIN','SYS_ADMIN')")
    public ApiResponse<List<LabView>> labs(Authentication authentication) {
        return ApiResponse.success(service.writableLabs(userId(authentication), sysAdmin(authentication)));
    }

    @GetMapping("/entries")
    @PreAuthorize("hasAnyRole('LAB_ADMIN','SYS_ADMIN')")
    public ApiResponse<List<TimetableEntryView>> entries(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long labId,
            Authentication authentication) {
        return ApiResponse.success(service.listEntries(
                userId(authentication), sysAdmin(authentication), semesterId, labId));
    }

    @GetMapping("/entries/{entryId}")
    @PreAuthorize("hasAnyRole('LAB_ADMIN','SYS_ADMIN')")
    public ApiResponse<TimetableEntryView> entry(
            @PathVariable long entryId, Authentication authentication) {
        return ApiResponse.success(service.getEntry(
                userId(authentication), sysAdmin(authentication), entryId));
    }

    @PostMapping("/entries")
    @PreAuthorize("hasAnyRole('LAB_ADMIN','SYS_ADMIN')")
    public ResponseEntity<?> create(
            @Valid @RequestBody SaveTimetableEntryRequest request, Authentication authentication) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.create(
                    userId(authentication), sysAdmin(authentication), request)));
        } catch (TimetableConflictException exception) {
            return conflict(exception);
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('LAB_ADMIN','SYS_ADMIN')")
    public ResponseEntity<?> importExcel(
            @RequestParam("file") MultipartFile file, Authentication authentication) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.importExcel(
                    userId(authentication), sysAdmin(authentication), file)));
        } catch (TimetableConflictException exception) {
            return conflict(exception);
        }
    }

    @PostMapping("/entries/{entryId}/roster")
    @PreAuthorize("hasAnyRole('LAB_ADMIN','SYS_ADMIN')")
    public ApiResponse<TimetableEntryView> addRoster(
            @PathVariable long entryId,
            @Valid @RequestBody RosterAccountsRequest request,
            Authentication authentication) {
        return ApiResponse.success(service.addRoster(
                userId(authentication), sysAdmin(authentication), entryId,
                request.studentAccounts()));
    }

    @DeleteMapping("/entries/{entryId}/roster/{studentUserId}")
    @PreAuthorize("hasAnyRole('LAB_ADMIN','SYS_ADMIN')")
    public ApiResponse<TimetableEntryView> removeRoster(
            @PathVariable long entryId,
            @PathVariable long studentUserId,
            Authentication authentication) {
        return ApiResponse.success(service.removeRoster(
                userId(authentication), sysAdmin(authentication), entryId, studentUserId));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('STUDENT','TEACHER','LAB_ADMIN','SYS_ADMIN')")
    public ApiResponse<List<MyTimetableSessionView>> mine(Authentication authentication) {
        return ApiResponse.success(service.mine(userId(authentication)));
    }

    private static ResponseEntity<ApiResponse<TimetableConflictReport>> conflict(
            TimetableConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiResponse<>(
                409, exception.getMessage(), new TimetableConflictReport(exception.conflicts())));
    }

    private static long userId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }

    private static boolean sysAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SYS_ADMIN"));
    }
}
