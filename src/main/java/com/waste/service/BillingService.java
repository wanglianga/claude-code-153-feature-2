package com.waste.service;

import com.waste.common.BizException;
import com.waste.common.NoGen;
import com.waste.common.TraceLogger;
import com.waste.config.WasteProperties;
import com.waste.entity.*;
import com.waste.enums.*;
import com.waste.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 计费结算：
 * 称重完成 → 业主账单（押金优先抵扣，不足部分物业垫付）+ 清运公司结算单；
 * 违规 → 押金扣款 + 施工队罚款账单；
 * 费用争议 → 账单调整并回冲押金。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final BillRepository billRepo;
    private final WeighRecordRepository weighRepo;
    private final DispatchOrderRepository dispatchRepo;
    private final CollectionAppointmentRepository apptRepo;
    private final DailyReportRepository reportRepo;
    private final RenovationRegistrationRepository regRepo;
    private final DepositDeductionRepository deductionRepo;
    private final WasteProperties props;
    private final TraceLogger traceLogger;
    private final TraceUpdater traceUpdater;

    /** MQ 事件：称重完成 → 生成业主账单 + 清运公司结算单（幂等：同一预约只生成一次）。
     *  REQUIRES_NEW：同步降级路径在 afterCommit 回调中执行，必须挂起外围已提交事务另起新事务。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onWeighRecorded(Long weighRecordId) {
        WeighRecord w = weighRepo.findById(weighRecordId).orElseThrow();
        if (!billRepo.findByAppointmentId(w.getAppointmentId()).isEmpty()) {
            log.info("预约 {} 账单已生成，跳过重复计费", w.getAppointmentId());
            return;
        }
        CollectionAppointment appt = apptRepo.findById(w.getAppointmentId()).orElseThrow();
        DailyReport report = reportRepo.findById(appt.getReportId()).orElseThrow();
        RenovationRegistration reg = regRepo.findById(appt.getRegistrationId()).orElseThrow();
        DispatchOrder dispatch = dispatchRepo.findById(w.getDispatchId()).orElseThrow();
        String period = appt.getApptDate().toString().substring(0, 7);

        // ---- 业主账单：正常袋 × 单价 + 临时加袋 × 单价 × 加袋倍率 ----
        int normalBags = report.getBagCount() - report.getExtraBagCount();
        BigDecimal baseFee = props.getBagUnitPrice().multiply(BigDecimal.valueOf(normalBags));
        BigDecimal extraFee = props.getBagUnitPrice().multiply(props.getExtraBagMultiplier())
                .multiply(BigDecimal.valueOf(report.getExtraBagCount()));
        BigDecimal total = baseFee.add(extraFee).setScale(2, RoundingMode.HALF_UP);

        Bill ownerBill = new Bill();
        ownerBill.setBillNo(NoGen.of("BILL"));
        ownerBill.setBillType(BillType.OWNER);
        ownerBill.setPartyId(reg.getOwnerId());
        ownerBill.setPartyName(reg.getOwnerName());
        ownerBill.setRegistrationId(reg.getId());
        ownerBill.setAppointmentId(appt.getId());
        ownerBill.setPeriod(period);
        ownerBill.setAmount(total);
        ownerBill.setDetail("{\"normalBags\":" + normalBags + ",\"extraBags\":" + report.getExtraBagCount()
                + ",\"bagUnitPrice\":" + props.getBagUnitPrice() + ",\"extraMultiplier\":"
                + props.getExtraBagMultiplier() + ",\"baseFee\":" + baseFee + ",\"extraFee\":" + extraFee
                + ",\"netWeightKg\":" + w.getNetWeightKg() + "}");

        // 押金抵扣，不足部分物业垫付
        BigDecimal deduct = reg.getDepositBalance().min(total);
        if (deduct.compareTo(BigDecimal.ZERO) > 0) {
            reg.setDepositBalance(reg.getDepositBalance().subtract(deduct));
            regRepo.save(reg);
            saveDeduction(reg.getId(), null, null, "FEE", deduct,
                    "清运费抵扣（预约 " + appt.getApptNo() + "）", reg.getDepositBalance());
        }
        BigDecimal gap = total.subtract(deduct);
        String payNote = "押金抵扣 " + deduct + " 元";
        if (gap.compareTo(BigDecimal.ZERO) > 0) {
            Bill advance = new Bill();
            advance.setBillNo(NoGen.of("BILL"));
            advance.setBillType(BillType.PROPERTY_ADVANCE);
            advance.setPartyName("物业");
            advance.setRegistrationId(reg.getId());
            advance.setAppointmentId(appt.getId());
            advance.setPeriod(period);
            advance.setAmount(gap);
            advance.setDetail("业主押金不足，物业垫付清运费（业主账单 " + ownerBill.getBillNo() + "）");
            billRepo.save(advance);
            payNote += "，物业垫付 " + gap + " 元";
            traceLogger.record(appt.getId(), "BILL", advance.getId(), null, "BILL_GENERATE",
                    "物业垫付单 " + advance.getBillNo() + "，金额 " + gap + " 元");
        }
        ownerBill.setPaidAmount(total);
        ownerBill.setStatus(BillStatus.PAID);
        ownerBill.setPaidAt(LocalDateTime.now());
        ownerBill.setDetail(ownerBill.getDetail().replace("}", ",\"payNote\":\"" + payNote + "\"}"));
        billRepo.save(ownerBill);
        traceLogger.record(appt.getId(), "BILL", ownerBill.getId(), null, "BILL_GENERATE",
                "业主账单 " + ownerBill.getBillNo() + "，金额 " + total + " 元（" + payNote + "）");

        // ---- 清运公司结算单：净重(吨) × 单价 ----
        BigDecimal tons = w.getNetWeightKg().divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        BigDecimal collectorFee = tons.multiply(props.getCollectorPricePerTon())
                .setScale(2, RoundingMode.HALF_UP);
        Bill collectorBill = new Bill();
        collectorBill.setBillNo(NoGen.of("BILL"));
        collectorBill.setBillType(BillType.COLLECTOR);
        collectorBill.setPartyName(dispatch.getCompanyName());
        collectorBill.setRegistrationId(reg.getId());
        collectorBill.setAppointmentId(appt.getId());
        collectorBill.setPeriod(period);
        collectorBill.setAmount(collectorFee);
        collectorBill.setDetail("{\"netWeightKg\":" + w.getNetWeightKg() + ",\"tons\":" + tons
                + ",\"pricePerTon\":" + props.getCollectorPricePerTon() + ",\"manifestNo\":\""
                + w.getManifestNo() + "\"}");
        billRepo.save(collectorBill);
        traceLogger.record(appt.getId(), "BILL", collectorBill.getId(), null, "BILL_GENERATE",
                "清运公司结算单 " + collectorBill.getBillNo() + "，" + tons + " 吨 × "
                        + props.getCollectorPricePerTon() + " 元/吨 = " + collectorFee + " 元");

        // 袋级追溯：费用结算完成（状态只前进不后退）
        traceUpdater.advance(appt.getId(), TraceStatus.SETTLED);
    }

    /** 押金流水记录 */
    public DepositDeduction saveDeduction(Long registrationId, Long violationId, Long billId,
                                          String type, BigDecimal amount, String reason, BigDecimal balanceAfter) {
        DepositDeduction d = new DepositDeduction();
        d.setRegistrationId(registrationId);
        d.setViolationId(violationId);
        d.setBillId(billId);
        d.setType(type);
        d.setAmount(amount);
        d.setReason(reason);
        d.setBalanceAfter(balanceAfter);
        return deductionRepo.save(d);
    }

    /**
     * 称重复核认定多户合并清运：为被合并户（未称重、未计费的预约）补建业主账单。
     * 费用规则与正常计费一致：正常袋 × 单价 + 临时加袋 × 单价 × 倍率，押金优先抵扣、不足部分物业垫付。
     * 幂等：该预约已有账单时跳过。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRED)
    public void settleMergedAppointment(CollectionAppointment appt, com.waste.entity.WeighReviewShare share,
                                        com.waste.entity.WeighReview review, com.waste.auth.Session property) {
        if (!billRepo.findByAppointmentId(appt.getId()).isEmpty()) {
            log.info("被合并户预约 {} 账单已存在，跳过补建", appt.getId());
            return;
        }
        DailyReport report = reportRepo.findById(appt.getReportId()).orElseThrow();
        RenovationRegistration reg = regRepo.findById(appt.getRegistrationId()).orElseThrow();
        String period = appt.getApptDate().toString().substring(0, 7);

        int normalBags = report.getBagCount() - report.getExtraBagCount();
        BigDecimal baseFee = props.getBagUnitPrice().multiply(BigDecimal.valueOf(normalBags));
        BigDecimal extraFee = props.getBagUnitPrice().multiply(props.getExtraBagMultiplier())
                .multiply(BigDecimal.valueOf(report.getExtraBagCount()));
        BigDecimal total = baseFee.add(extraFee).setScale(2, RoundingMode.HALF_UP);

        Bill ownerBill = new Bill();
        ownerBill.setBillNo(NoGen.of("BILL"));
        ownerBill.setBillType(BillType.OWNER);
        ownerBill.setPartyId(reg.getOwnerId());
        ownerBill.setPartyName(reg.getOwnerName());
        ownerBill.setRegistrationId(reg.getId());
        ownerBill.setAppointmentId(appt.getId());
        ownerBill.setPeriod(period);
        ownerBill.setAmount(total);
        ownerBill.setDetail("{\"normalBags\":" + normalBags + ",\"extraBags\":" + report.getExtraBagCount()
                + ",\"bagUnitPrice\":" + props.getBagUnitPrice() + ",\"extraMultiplier\":"
                + props.getExtraBagMultiplier() + ",\"baseFee\":" + baseFee + ",\"extraFee\":" + extraFee
                + ",\"mergedCollection\":true,\"reviewNo\":\"" + review.getReviewNo()
                + "\",\"allocatedWeightKg\":" + share.getConfirmedWeightKg() + "}");

        BigDecimal deduct = reg.getDepositBalance().min(total);
        if (deduct.compareTo(BigDecimal.ZERO) > 0) {
            reg.setDepositBalance(reg.getDepositBalance().subtract(deduct));
            regRepo.save(reg);
            saveDeduction(reg.getId(), null, null, "FEE", deduct,
                    "合并清运复核补扣清运费（预约 " + appt.getApptNo() + "，复核单 " + review.getReviewNo() + "）",
                    reg.getDepositBalance());
        }
        BigDecimal gap = total.subtract(deduct);
        String payNote = "合并清运复核补建账单，押金抵扣 " + deduct + " 元";
        if (gap.compareTo(BigDecimal.ZERO) > 0) {
            Bill advance = new Bill();
            advance.setBillNo(NoGen.of("BILL"));
            advance.setBillType(BillType.PROPERTY_ADVANCE);
            advance.setPartyName("物业");
            advance.setRegistrationId(reg.getId());
            advance.setAppointmentId(appt.getId());
            advance.setPeriod(period);
            advance.setAmount(gap);
            advance.setDetail("合并清运复核：业主押金不足，物业垫付清运费（业主账单 " + ownerBill.getBillNo() + "）");
            billRepo.save(advance);
            payNote += "，物业垫付 " + gap + " 元";
            traceLogger.record(appt.getId(), "BILL", advance.getId(), property, "BILL_GENERATE",
                    "合并清运物业垫付单 " + advance.getBillNo() + "，金额 " + gap + " 元");
        }
        ownerBill.setPaidAmount(total);
        ownerBill.setStatus(BillStatus.PAID);
        ownerBill.setPaidAt(LocalDateTime.now());
        ownerBill.setDetail(ownerBill.getDetail().replace("}", ",\"payNote\":\"" + payNote + "\"}"));
        billRepo.save(ownerBill);
        traceLogger.record(appt.getId(), "BILL", ownerBill.getId(), property, "BILL_GENERATE",
                "合并清运复核补建业主账单 " + ownerBill.getBillNo() + "，" + share.getBuildingRoom()
                        + " 分摊净重 " + share.getConfirmedWeightKg() + "kg，按袋清运费 " + total
                        + " 元（" + payNote + "），分摊清运结算 " + share.getCollectorFee() + " 元");
    }

    /**
     * 称重复核订正清运公司结算单（设备异常按回执重量、司机误填按验收袋数折算）。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRED)
    public Bill correctCollectorFee(Long appointmentId, String reviewNo, BigDecimal newFee,
                                    String reason, com.waste.auth.Session property) {
        Bill bill = billRepo.findByAppointmentId(appointmentId).stream()
                .filter(b -> b.getBillType() == BillType.COLLECTOR).findFirst()
                .orElseThrow(() -> new BizException("清运公司结算单不存在，无法订正"));
        BigDecimal old = bill.getAmount();
        bill.setAmount(newFee);
        if (bill.getStatus() == BillStatus.PAID) {
            // 已付款：订正金额后转待处理，由财务补付或追回差额
            bill.setStatus(BillStatus.UNPAID);
            bill.setPaidAmount(bill.getPaidAmount() == null ? BigDecimal.ZERO : bill.getPaidAmount());
        }
        bill.setDetail((bill.getDetail() == null ? "" : bill.getDetail())
                + " [复核" + reviewNo + " 结算订正] " + old + " → " + newFee + "，原因: " + reason);
        billRepo.save(bill);
        traceLogger.record(appointmentId, "BILL", bill.getId(), property, "BILL_REVIEW_CORRECT",
                "清运结算复核订正: " + bill.getBillNo() + " " + old + " → " + newFee + " 元，原因: " + reason);
        return bill;
    }

    /** 财务确认收款/付款 */
    @Transactional
    public Bill pay(Long billId, com.waste.auth.Session finance) {
        Bill bill = billRepo.findById(billId).orElseThrow(() -> new BizException(404, "账单不存在"));
        if (bill.getStatus() == BillStatus.PAID) {
            throw new BizException("账单已结清，不能重复操作");
        }
        bill.setStatus(BillStatus.PAID);
        bill.setPaidAmount(bill.getAmount());
        bill.setPaidAt(LocalDateTime.now());
        billRepo.save(bill);

        // 施工队缴清罚款 → 返还业主押金
        if (bill.getBillType() == BillType.CONSTRUCTION_TEAM && bill.getRegistrationId() != null) {
            RenovationRegistration reg = regRepo.findById(bill.getRegistrationId()).orElseThrow();
            reg.setDepositBalance(reg.getDepositBalance().add(bill.getAmount()));
            regRepo.save(reg);
            saveDeduction(reg.getId(), null, bill.getId(), "REFUND", bill.getAmount().negate(),
                    "施工队缴清罚款，返还业主押金（账单 " + bill.getBillNo() + "）", reg.getDepositBalance());
        }
        traceLogger.record(bill.getAppointmentId(), "BILL", bill.getId(), finance, "BILL_PAY",
                "账单 " + bill.getBillNo() + "（" + bill.getBillType() + "）已结清，金额 " + bill.getAmount() + " 元");
        return bill;
    }

    /** 费用争议调整：仅允许处理争议中的账单，差额回冲/补扣押金 */
    @Transactional
    public Bill adjust(Long billId, BigDecimal newAmount, String reason, com.waste.auth.Session finance) {
        Bill bill = billRepo.findById(billId).orElseThrow(() -> new BizException(404, "账单不存在"));
        if (bill.getStatus() != BillStatus.DISPUTED) {
            throw new BizException("仅争议中的账单可调整");
        }
        if (newAmount == null || newAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("调整后金额不合法");
        }
        newAmount = newAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal old = bill.getAmount();
        BigDecimal diff = newAmount.subtract(old);
        bill.setAmount(newAmount);
        bill.setPaidAmount(newAmount);
        bill.setStatus(BillStatus.PAID);
        bill.setDetail((bill.getDetail() == null ? "" : bill.getDetail())
                + " [调整] " + old + " → " + newAmount + "，原因: " + reason);
        billRepo.save(bill);

        // 业主账单差额与押金联动
        if (bill.getBillType() == BillType.OWNER && bill.getRegistrationId() != null
                && diff.compareTo(BigDecimal.ZERO) != 0) {
            RenovationRegistration reg = regRepo.findById(bill.getRegistrationId()).orElseThrow();
            if (diff.compareTo(BigDecimal.ZERO) < 0) {
                reg.setDepositBalance(reg.getDepositBalance().add(diff.abs()));
                regRepo.save(reg);
                saveDeduction(reg.getId(), null, bill.getId(), "REFUND", diff,
                        "费用争议调整退款（账单 " + bill.getBillNo() + "）", reg.getDepositBalance());
            } else {
                BigDecimal deduct = reg.getDepositBalance().min(diff);
                reg.setDepositBalance(reg.getDepositBalance().subtract(deduct));
                regRepo.save(reg);
                if (deduct.compareTo(BigDecimal.ZERO) > 0) {
                    saveDeduction(reg.getId(), null, bill.getId(), "FEE", deduct,
                            "费用争议调整补扣（账单 " + bill.getBillNo() + "）", reg.getDepositBalance());
                }
            }
        }
        traceLogger.record(bill.getAppointmentId(), "BILL", bill.getId(), finance, "BILL_ADJUST",
                "费用争议调整: 账单 " + bill.getBillNo() + " " + old + " → " + newAmount + " 元，原因: " + reason);
        return bill;
    }

    public List<Bill> list(BillType type, BillStatus status, String period) {
        if (type != null && period != null) {
            return billRepo.findByBillTypeAndPeriodOrderByIdDesc(type, period);
        }
        if (type != null) {
            return billRepo.findByBillTypeOrderByIdDesc(type);
        }
        if (status != null) {
            return billRepo.findByStatusOrderByIdDesc(status);
        }
        if (period != null) {
            return billRepo.findByPeriodOrderByIdDesc(period);
        }
        return billRepo.findAll();
    }

    public Bill get(Long id) {
        return billRepo.findById(id).orElseThrow(() -> new BizException(404, "账单不存在"));
    }
}
