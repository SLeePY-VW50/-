package com.campus.lab.statistics;

import com.campus.lab.common.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;
    private final ReservationCsvService csvService;

    public StatisticsController(StatisticsService statisticsService, ReservationCsvService csvService) {
        this.statisticsService = statisticsService;
        this.csvService = csvService;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('STUDENT','TEACHER','LAB_ADMIN','SYS_ADMIN')")
    public ApiResponse<StatisticsViews.Dashboard> dashboard(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication) {
        return ApiResponse.success(statisticsService.dashboard(
                userId(authentication), isAdministrator(authentication), date));
    }

    @GetMapping("/heatmap")
    @PreAuthorize("hasAnyRole('TEACHER','LAB_ADMIN','SYS_ADMIN')")
    public ApiResponse<List<StatisticsViews.HeatmapDay>> heatmap(
            @RequestParam long labId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return ApiResponse.success(statisticsService.heatmap(labId, start, end));
    }

    @GetMapping("/reservations.csv")
    @PreAuthorize("hasAnyRole('STUDENT','TEACHER','LAB_ADMIN','SYS_ADMIN')")
    public ResponseEntity<byte[]> reservationsCsv(
            @RequestParam(required = false) Long labId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            Authentication authentication) {
        byte[] content = csvService.export(
                userId(authentication), isAdministrator(authentication), labId, start, end);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reservations.csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(content);
    }

    private static long userId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }

    private static boolean isAdministrator(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_LAB_ADMIN")
                        || authority.getAuthority().equals("ROLE_SYS_ADMIN"));
    }
}
