package com.campus.lab.timetable;

import com.campus.lab.common.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

final class TimetableExcel {

    static final int MAX_ROWS = 200;

    private static final Map<String, String> HEADERS = Map.ofEntries(
            Map.entry("学期代码", "semester"),
            Map.entry("实验室代码", "lab"),
            Map.entry("课程名", "course"),
            Map.entry("教师账号", "teacher"),
            Map.entry("班级", "className"),
            Map.entry("星期", "day"),
            Map.entry("开始节", "start"),
            Map.entry("结束节", "end"),
            Map.entry("开始周", "weekStart"),
            Map.entry("结束周", "weekEnd"),
            Map.entry("学生账号", "students"));

    private TimetableExcel() {
    }

    static List<ImportRow> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请上传课表文件");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请上传xlsx文件");
        }
        try (InputStream input = file.getInputStream(); Workbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "导入文件没有数据");
            }
            DataFormatter formatter = new DataFormatter();
            Row header = sheet.getRow(0);
            Map<String, Integer> columns = headerIndex(header, formatter);
            List<ImportRow> rows = new ArrayList<>();
            int last = sheet.getLastRowNum();
            for (int index = 1; index <= last; index++) {
                Row row = sheet.getRow(index);
                if (row == null || blank(row, columns, formatter)) {
                    continue;
                }
                int rowNumber = index + 1;
                rows.add(new ImportRow(
                        rowNumber,
                        text(row, columns, formatter, "semester"),
                        text(row, columns, formatter, "lab"),
                        text(row, columns, formatter, "course"),
                        text(row, columns, formatter, "teacher"),
                        text(row, columns, formatter, "className"),
                        dayOfWeek(text(row, columns, formatter, "day"), rowNumber),
                        integer(text(row, columns, formatter, "start"), "开始节", rowNumber),
                        integer(text(row, columns, formatter, "end"), "结束节", rowNumber),
                        integer(text(row, columns, formatter, "weekStart"), "开始周", rowNumber),
                        integer(text(row, columns, formatter, "weekEnd"), "结束周", rowNumber),
                        students(text(row, columns, formatter, "students"))));
                if (rows.size() > MAX_ROWS) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "一次最多导入" + MAX_ROWS + "条课表");
                }
            }
            if (rows.isEmpty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "导入文件没有数据行");
            }
            return List.copyOf(rows);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "无法读取Excel");
        }
    }

    private static Map<String, Integer> headerIndex(Row header, DataFormatter formatter) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        if (header != null) {
            for (int index = 0; index < header.getLastCellNum(); index++) {
                String label = formatter.formatCellValue(header.getCell(index)).replace(" ", "").trim();
                String key = HEADERS.get(label);
                if (key != null) {
                    columns.put(key, index);
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : HEADERS.entrySet()) {
            if (!columns.containsKey(entry.getValue())) {
                missing.add(entry.getKey());
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "导入表缺少列：" + String.join("、", missing));
        }
        return columns;
    }

    private static boolean blank(Row row, Map<String, Integer> columns, DataFormatter formatter) {
        for (int index : columns.values()) {
            if (!formatter.formatCellValue(row.getCell(index)).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String text(Row row, Map<String, Integer> columns, DataFormatter formatter, String key) {
        return formatter.formatCellValue(row.getCell(columns.get(key))).trim();
    }

    private static int integer(String raw, String label, int rowNumber) {
        String text = raw.endsWith(".0") ? raw.substring(0, raw.length() - 2) : raw;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "第" + rowNumber + "行：" + label + "不是整数");
        }
    }

    static int dayOfWeek(String raw, int rowNumber) {
        return switch (raw) {
            case "1", "一", "周一", "星期一", "周1" -> 1;
            case "2", "二", "周二", "星期二", "周2" -> 2;
            case "3", "三", "周三", "星期三", "周3" -> 3;
            case "4", "四", "周四", "星期四", "周4" -> 4;
            case "5", "五", "周五", "星期五", "周5" -> 5;
            case "6", "六", "周六", "星期六", "周6" -> 6;
            case "7", "日", "天", "周日", "周天", "星期日", "星期天", "周7" -> 7;
            default -> throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "第" + rowNumber + "行：星期无法识别：" + raw);
        };
    }

    private static List<String> students(String raw) {
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> accounts = new ArrayList<>();
        for (String part : raw.split("[,，;；、\\s]+")) {
            if (!part.isBlank()) {
                accounts.add(part.trim());
            }
        }
        return List.copyOf(accounts);
    }

    record ImportRow(
            int rowNumber,
            String semesterCode,
            String labCode,
            String courseName,
            String teacherAccount,
            String className,
            int dayOfWeek,
            int startPeriod,
            int endPeriod,
            int weekStart,
            int weekEnd,
            List<String> studentAccounts) {
    }
}
