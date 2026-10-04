package com.waste.service;

import com.waste.auth.Session;
import com.waste.common.BizException;
import com.waste.common.NoGen;
import com.waste.common.TraceLogger;
import com.waste.entity.DisposalReceipt;
import com.waste.entity.WeighRecord;
import com.waste.enums.TraceStatus;
import com.waste.repository.DisposalReceiptRepository;
import com.waste.repository.WeighRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DisposalService {

    private final DisposalReceiptRepository receiptRepo;
    private final WeighRecordRepository weighRepo;
    private final WeighReviewService weighReviewService;
    private final TraceLogger traceLogger;
    private final TraceUpdater traceUpdater;

    /** 处置场回执：同一批垃圾完成末端处置的凭证 */
    @Transactional
    public DisposalReceipt create(Session collector, Long weighRecordId, String disposalSite,
                                  String receiptNo, BigDecimal receivedWeightKg, LocalDateTime receivedAt) {
        WeighRecord w = weighRepo.findById(weighRecordId)
                .orElseThrow(() -> new BizException(404, "称重记录不存在"));
        receiptRepo.findByWeighRecordId(weighRecordId).ifPresent(r -> {
            throw new BizException("该称重记录已有处置回执（" + r.getReceiptNo() + "）");
        });
        DisposalReceipt r = new DisposalReceipt();
        r.setWeighRecordId(weighRecordId);
        r.setAppointmentId(w.getAppointmentId());
        r.setDisposalSite(disposalSite);
        r.setReceiptNo(receiptNo != null && !receiptNo.isBlank() ? receiptNo : NoGen.of("RC"));
        r.setReceivedWeightKg(receivedWeightKg);
        r.setReceivedAt(receivedAt != null ? receivedAt : LocalDateTime.now());
        receiptRepo.save(r);
        traceUpdater.advance(w.getAppointmentId(), TraceStatus.DISPOSED);

        traceLogger.record(w.getAppointmentId(), "DISPOSAL", r.getId(), collector, "DISPOSAL_RECEIPT",
                "处置场回执: 处置场=" + disposalSite + " 回执号=" + r.getReceiptNo()
                        + " 接收重量=" + receivedWeightKg + "kg");

        // 回执是称重复核关键证据：若该称重记录有待复核单，自动重新比对
        weighReviewService.refreshIfPending(w.getId());
        return r;
    }

    public List<DisposalReceipt> list() {
        return receiptRepo.findAll();
    }
}
