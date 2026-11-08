package com.campus.lab.credit;

import com.campus.lab.common.BusinessException;
import com.campus.lab.credit.dto.AppealView;
import com.campus.lab.reservation.dto.PageView;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppealService {

    private final AppealRepository repository;
    private final CreditService creditService;

    public AppealService(AppealRepository repository, CreditService creditService) {
        this.repository = repository;
        this.creditService = creditService;
    }

    @Transactional(rollbackFor = Exception.class)
    public AppealView create(long userId, long violationId, String reason) {
        AppealRepository.ViolationDetails violation = repository.lockViolation(violationId);
        if (violation == null || violation.userId() != userId) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "违规记录不存在");
        }
        if (!"ACTIVE".equals(violation.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "已撤销违规不能申诉");
        }
        if (repository.countByViolation(violationId) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "每个违规最多提交一条申诉");
        }
        long appealId = repository.insertAppeal(violationId, userId, reason.trim());
        return repository.findAppeal(appealId);
    }

    @Transactional(readOnly = true)
    public PageView<AppealView> list(AppealStatus status, int page, int size) {
        validatePage(page, size);
        long total = repository.countAppeals(status);
        return new PageView<>(repository.listAppeals(status, page, size), page, size, total);
    }

    @Transactional(rollbackFor = Exception.class)
    public AppealView review(
            long appealId, long reviewerId, ReviewDecision decision, String opinion) {
        String normalizedOpinion = opinion.trim();
        AppealView appeal = repository.lockAppeal(appealId);
        if (appeal == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "申诉不存在");
        }
        if (!"PENDING".equals(appeal.status())) {
            if (appeal.status().equals(decision.name())
                    && Objects.equals(appeal.reviewOpinion(), normalizedOpinion)) {
                return appeal;
            }
            throw new BusinessException(HttpStatus.CONFLICT, "申诉已核定，不能变更核定结果");
        }
        AppealRepository.ViolationDetails violation = repository.lockViolation(appeal.violationId());
        if (violation == null) {
            throw new IllegalStateException("申诉对应的违规不存在");
        }
        if (!"ACTIVE".equals(violation.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "违规当前状态不允许核定");
        }
        int updated = repository.updateReview(appealId, reviewerId, decision, normalizedOpinion);
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "申诉状态已变化，请重试");
        }
        if (decision == ReviewDecision.APPROVED) {
            if (repository.revokeViolation(appeal.violationId()) != 1) {
                throw new BusinessException(HttpStatus.CONFLICT, "违规状态已变化，请重试");
            }
            creditService.restore(appeal.violationId(), reviewerId);
        }
        return repository.findAppeal(appealId);
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "页码不能为负数");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "每页大小必须在1到100之间");
        }
    }
}
