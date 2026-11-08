package com.campus.lab.credit;

import com.campus.lab.common.BusinessException;
import com.campus.lab.credit.dto.CreditAccountView;
import com.campus.lab.credit.dto.CreditRecordView;
import com.campus.lab.credit.dto.ViolationView;
import com.campus.lab.reservation.dto.PageView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreditService {

    private final CreditRepository repository;

    public CreditService(CreditRepository repository) {
        this.repository = repository;
    }

    @Transactional(rollbackFor = Exception.class)
    public CreditAdjustment deduct(
            long userId,
            long reservationId,
            String violationType,
            int requestedPoints,
            Long operatorId) {
        if (requestedPoints < 0) {
            throw new IllegalArgumentException("扣分不能为负数");
        }
        int balanceBefore = repository.lockUserCredit(userId);
        int actualPoints = Math.min(requestedPoints, balanceBefore);
        int balanceAfter = balanceBefore - actualPoints;
        long violationId = repository.insertViolation(
                userId, reservationId, violationType, actualPoints, operatorId);
        repository.updateCreditScore(userId, balanceBefore, balanceAfter);
        repository.insertCreditRecord(
                userId, violationId, "DEDUCT", -actualPoints,
                balanceBefore, balanceAfter, operatorId);
        return new CreditAdjustment(violationId, actualPoints, balanceBefore, balanceAfter);
    }

    @Transactional(rollbackFor = Exception.class)
    public CreditAdjustment restore(long violationId, Long operatorId) {
        CreditRepository.ViolationDebit violation = repository.lockViolationDebit(violationId);
        if (violation == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "违规记录不存在");
        }
        CreditRecordView existingRestore = repository.findCreditRecordByType(violationId, "RESTORE");
        if (existingRestore != null) {
            int currentBalance = repository.lockUserCredit(violation.userId());
            return new CreditAdjustment(violationId, 0, currentBalance, currentBalance);
        }
        int balanceBefore = repository.lockUserCredit(violation.userId());
        int actualPoints = Math.min(violation.deductPoints(), 100 - balanceBefore);
        int balanceAfter = balanceBefore + actualPoints;
        repository.updateCreditScore(violation.userId(), balanceBefore, balanceAfter);
        repository.insertCreditRecord(
                violation.userId(), violationId, "RESTORE", actualPoints,
                balanceBefore, balanceAfter, operatorId);
        return new CreditAdjustment(violationId, actualPoints, balanceBefore, balanceAfter);
    }

    @Transactional(readOnly = true)
    public CreditAccountView getMyCredit(long userId, int page, int size) {
        validatePage(page, size);
        Integer creditScore = repository.findCreditScore(userId);
        if (creditScore == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不存在或不可用");
        }
        long total = repository.countCreditRecords(userId);
        List<CreditRecordView> records = repository.listCreditRecords(userId, page, size);
        return new CreditAccountView(creditScore, new PageView<>(records, page, size, total));
    }

    @Transactional(readOnly = true)
    public PageView<ViolationView> getMyViolations(long userId, int page, int size) {
        validatePage(page, size);
        long total = repository.countViolations(userId);
        return new PageView<>(repository.listViolations(userId, page, size), page, size, total);
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "页码不能为负数");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "每页大小必须在1到100之间");
        }
    }

    public record CreditAdjustment(
            long violationId, int actualPoints, int balanceBefore, int balanceAfter) {
    }
}
