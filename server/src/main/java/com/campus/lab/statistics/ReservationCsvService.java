package com.campus.lab.statistics;

import com.campus.lab.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationCsvService {

    private static final int MAX_ROWS = 10000;
    private static final String[] HEADERS = {
            "reservationId", "userId", "account", "labId", "labName", "requestDate",
            "mode", "status", "startTime", "endTime", "attendeeCount", "seatCount",
            "purpose", "checkinTime", "checkoutTime", "createdAt"
    };

    private final StatisticsRepository repository;

    public ReservationCsvService(StatisticsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public byte[] export(long userId, boolean administrator, Long labId,
            LocalDate start, LocalDate end) {
        StatisticsService.validateRange(start, end);
        Long ownerId = administrator ? null : userId;
        List<StatisticsRepository.CsvRow> rows =
                repository.listCsvRows(ownerId, labId, start, end, MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "导出超过10000条，请缩小日期范围");
        }
        StringBuilder csv = new StringBuilder();
        appendRow(csv, (Object[]) HEADERS);
        for (StatisticsRepository.CsvRow row : rows) {
            appendRow(csv,
                    row.reservationId(), row.userId(), row.account(), row.labId(), row.labName(),
                    row.date(), row.mode(), row.status(), row.startTime(), row.endTime(),
                    row.attendeeCount(), row.seatCount(), row.purpose(), row.checkinTime(),
                    row.checkoutTime(), row.createdAt());
        }
        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[content.length + 3];
        result[0] = (byte) 0xEF;
        result[1] = (byte) 0xBB;
        result[2] = (byte) 0xBF;
        System.arraycopy(content, 0, result, 3, content.length);
        return result;
    }

    private static void appendRow(StringBuilder csv, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                csv.append(',');
            }
            csv.append(cell(values[index]));
        }
        csv.append("\r\n");
    }

    private static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        int start = 0;
        while (start < text.length()
                && (Character.isWhitespace(text.charAt(start))
                || Character.isISOControl(text.charAt(start)))) {
            start++;
        }
        text = text.substring(start).replace('\r', ' ').replace('\n', ' ');
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
