package com.waste.service;

import com.waste.auth.Session;
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
import java.util.*;

/**
 * 称重差异复核：
 * 清运车回传净重明显高于现场验收袋数时，自动/人工立案，系统比对
 * 装车照片清点、车辆 GPS 停留点、车辆路线与同车同日其他预约、处置场回执重量、按袋/材质估算重量，
 * 给出"合并清运 / 称重设备异常 / 司机误填 / 无异常 / 证据不足"建议；
 * 物业确认结论并可人工调整（强制留理由），确认后：
 *  - 合并清运：按材质估算占比 + 装车顺序把净重与结算费拆分到各户，被合并户补建业主账单（押金抵扣）；
 *  - 设备异常：按处置场回执重量重算清运结算；
 *  - 司机误填：按验收袋数估算重量重算清运结算并冻结车辆后续派单；
 *  - 自动办结称重异常投诉，全过程留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeighReviewService {

    private final WeighReviewRepository reviewRepo;
    private final WeighReviewShareRepository shareRepo;
    private final WeighRecordRepository weighRepo;
    private final DispatchOrderRepository dispatchRepo;
    private final CollectionAppointmentRepository apptRepo;
    private final DailyReportRepository reportRepo;
    private final RenovationRegistrationRepository regRepo;
    private final WastePointRepository wastePointRepo;
    private final DisposalReceiptRepository receiptRepo;
    private final VehicleStopRepository stopRepo;
    private final VehicleRepository vehicleRepo;
    private final BillRepository billRepo;
    private final ComplaintRepository complaintRepo;
    private final BillingService billingService;
    private final WasteProperties props;
    private final TraceLogger traceLogger;
    private final TraceUpdater traceUpdater;

    // ============================== 立案 ==============================

    /** 称重异常时自动立案（幂等：同一称重记录只有一张复核单） */
    @Transactional
    public WeighReview openForAnomaly(WeighRecord w, Session actor) {
        return openInternal(w, true, actor);
    }

    /** 物业对任意称重记录主动发起复核（即使系统未判异常） */
    @Transactional
    public WeighReview openManually(Session property, Long weighRecordId) {
        WeighRecord w = weighRepo.findById(weighRecordId)
                .orElseThrow(() -> new BizException(404, "称重记录不存在"));
        return openInternal(w, false, property);
    }

    private WeighReview openInternal(WeighRecord w, boolean auto, Session actor) {
        Optional<WeighReview> exist = reviewRepo.findByWeighRecordId(w.getId());
        if (exist.isPresent()) {
            return exist.get();
        }
        WeighReview r = new WeighReview();
        r.setReviewNo(NoGen.of("WR"));
        r.setWeighRecordId(w.getId());
        r.setDispatchId(w.getDispatchId());
        r.setAppointmentId(w.getAppointmentId());
        r.setPlateNo(w.getPlateNo());
        r.setDriverName(w.getDriverName());
        reviewRepo.save(r);

        traceLogger.record(w.getAppointmentId(), "WEIGH_REVIEW", r.getId(), actor, "REVIEW_OPEN",
        (auto ? "称重偏差超阈值，系统自动立案复核：" : "物业人工发起称重复核：")
                + "联单 " + w.getManifestNo() + "，净重 " + w.getNetWeightKg() + "kg，按袋预估 "
                + w.getEstimatedWeightKg() + "kg，偏差 " + w.getDeviationPct() + "%");
        return analyze(r.getId());
    }

    // ============================== 证据补传 ==============================

    /** 新证据（处置回执/GPS 等）落库后，若该称重记录存在待确认复核单则自动重新比对 */
    @Transactional
    public void refreshIfPending(Long weighRecordId) {
        reviewRepo.findByWeighRecordId(weighRecordId).ifPresent(r -> {
            if (r.getStatus() == ReviewStatus.PENDING) {
                analyze(r.getId());
            }
        });
    }

    /** GPS 停留点补传后，刷新该车牌时间窗内所有待确认复核单 */
    @Transactional
    public void refreshPendingByPlate(String plateNo, LocalDateTime eventTime) {
        for (WeighReview r : reviewRepo.findByPlateNoOrderByIdDesc(plateNo)) {
            if (r.getStatus() != ReviewStatus.PENDING) {
                continue;
            }
            WeighRecord w = weighRepo.findById(r.getWeighRecordId()).orElse(null);
            if (w == null) {
                continue;
            }
            int windowH = props.getReviewMergeWindowHours();
            if (!eventTime.isBefore(w.getEntryTime().minusHours(windowH))
                    && !eventTime.isAfter(w.getExitTime().plusHours(windowH))) {
                analyze(r.getId());
            }
        }
    }

    /** 补传装车照片清点证据（可见袋数、照片中识别到的楼栋/房号标签），随后自动重新比对 */
    @Transactional
    public WeighReview submitPhotoEvidence(Session property, Long reviewId,
                                           Integer photoVisibleBags, String photoLabels) {
        WeighReview r = requirePending(reviewId);
        if (photoVisibleBags != null) {
            if (photoVisibleBags < 0) {
                throw new BizException("照片清点袋数不合法");
            }
            r.setPhotoVisibleBags(photoVisibleBags);
        }
        if (photoLabels != null) {
            r.setPhotoLabels(photoLabels.isBlank() ? null : photoLabels.trim());
        }
        reviewRepo.save(r);
        traceLogger.record(r.getAppointmentId(), "WEIGH_REVIEW", r.getId(), property, "REVIEW_EVIDENCE_PHOTO",
                "补传装车照片证据：可见袋数=" + photoVisibleBags
                        + (photoLabels != null ? "，楼栋/房号标签=" + photoLabels : ""));
        return analyze(reviewId);
    }

    // ============================== 系统比对分析 ==============================

    /**
     * 拉取全部证据重新分析并刷新系统建议（PENDING 状态可反复执行）。
     */
    @Transactional
    public WeighReview analyze(Long reviewId) {
        WeighReview r = reviewRepo.findById(reviewId)
                .orElseThrow(() -> new BizException(404, "复核单不存在"));
        WeighRecord w = weighRepo.findById(r.getWeighRecordId()).orElseThrow();
        CollectionAppointment primaryAppt = apptRepo.findById(w.getAppointmentId()).orElseThrow();
        DailyReport primaryReport = reportRepo.findById(primaryAppt.getReportId()).orElseThrow();
        RenovationRegistration primaryReg = regRepo.findById(primaryAppt.getRegistrationId()).orElseThrow();

        BigDecimal net = w.getNetWeightKg();
        BigDecimal primaryEst = props.getAvgBagWeightKg().multiply(BigDecimal.valueOf(primaryReport.getBagCount()));
        int windowH = props.getReviewMergeWindowHours();
        LocalDateTime winFrom = w.getEntryTime().minusHours(windowH);
        LocalDateTime winTo = w.getExitTime().plusHours(windowH);

        List<String> reasons = new ArrayList<>();

        // ---- 证据 1：同车时间窗内的其他派单/预约 ----
        Map<Long, DispatchOrder> otherDispatchByAppt = new LinkedHashMap<>();
        for (DispatchOrder d : dispatchRepo.findAll()) {
            if (!Objects.equals(d.getPlateNo(), w.getPlateNo()) || Objects.equals(d.getId(), w.getDispatchId())) {
                continue;
            }
            LocalDateTime t = d.getActualArrival() != null ? d.getActualArrival() : d.getPlannedArrival();
            // 该派单已单独过磅（那批货已独立称重），不属于本车合并装载
            if (t != null && !t.isBefore(winFrom) && !t.isAfter(winTo)
                    && d.getAppointmentId() != null && !d.getAppointmentId().equals(w.getAppointmentId())
                    && weighRepo.findByDispatchId(d.getId()).isEmpty()) {
                otherDispatchByAppt.put(d.getAppointmentId(), d);
            }
        }
        if (!otherDispatchByAppt.isEmpty()) {
            reasons.add("时间窗内同车（" + w.getPlateNo() + "）存在 " + otherDispatchByAppt.size()
                    + " 个其他楼栋派单");
        }

        // ---- 证据 2：GPS 停留点（在其他垃圾点装车停留）----
        List<VehicleStop> stops = stopRepo.findByPlateNoAndEventTimeBetweenOrderByEventTimeAsc(
                w.getPlateNo(), winFrom, winTo);
        List<VehicleStop> loadingStops = stops.stream()
                .filter(s -> s.getStayMinutes() != null && s.getStayMinutes() >= props.getReviewMergeStayMinutes()
                        && s.getWastePointId() != null
                        && !s.getWastePointId().equals(primaryAppt.getWastePointId()))
                .toList();
        if (!loadingStops.isEmpty()) {
            reasons.add("GPS 显示在其他楼栋垃圾点装车停留 " + loadingStops.size() + " 处："
                    + loadingStops.stream().map(s -> s.getLocation() + "(" + s.getStayMinutes() + "分钟)")
                    .reduce((a, b) -> a + "、" + b).orElse(""));
        }

        // 停留点对应点位当日的其他预约（即使没有正式派单，司机也可能私装）；
        // 已独立称重的预约（那批货已单独过磅）排除
        for (VehicleStop s : loadingStops) {
            for (CollectionAppointment a : apptRepo.findAll()) {
                if (Objects.equals(a.getWastePointId(), s.getWastePointId())
                        && !Objects.equals(a.getId(), w.getAppointmentId())
                        && !otherDispatchByAppt.containsKey(a.getId())
                        && !a.getApptDate().isBefore(winFrom.toLocalDate())
                        && !a.getApptDate().isAfter(winTo.toLocalDate())
                        && a.getStatus() != ApptStatus.REJECTED
                        && a.getStatus() != ApptStatus.REJECTED_CAPACITY
                        && a.getStatus() != ApptStatus.CANCELLED
                        && weighRepo.findByAppointmentId(a.getId()).isEmpty()) {
                    otherDispatchByAppt.put(a.getId(), null);
                }
            }
        }

        // ---- 组装合并参与户（主户 + 候选户）并按材质估算 ----
        DispatchOrder primaryDispatch = dispatchRepo.findById(w.getDispatchId()).orElse(null);
        LocalDateTime primaryCollectTime = primaryDispatch != null
                ? (primaryDispatch.getActualArrival() != null ? primaryDispatch.getActualArrival()
                : primaryDispatch.getPlannedArrival())
                : w.getEntryTime();
        List<Participant> participants = new ArrayList<>();
        participants.add(loadParticipant(primaryAppt, primaryReport, primaryReg,
                primaryDispatch, primaryCollectTime, 0));
        for (Map.Entry<Long, DispatchOrder> e : otherDispatchByAppt.entrySet()) {
            CollectionAppointment a = apptRepo.findById(e.getKey()).orElse(null);
            if (a == null) {
                continue;
            }
            DailyReport rep = reportRepo.findById(a.getReportId()).orElse(null);
            RenovationRegistration reg = regRepo.findById(a.getRegistrationId()).orElse(null);
            if (rep == null || reg == null) {
                continue;
            }
            LocalDateTime collectTime = e.getValue() != null
                    ? (e.getValue().getActualArrival() != null ? e.getValue().getActualArrival()
                    : e.getValue().getPlannedArrival())
                    : stops.stream().filter(s -> Objects.equals(s.getWastePointId(), a.getWastePointId()))
                    .map(VehicleStop::getEventTime).findFirst().orElse(w.getEntryTime());
            participants.add(loadParticipant(a, rep, reg, e.getValue(), collectTime, 0));
        }
        // 装车顺序：按实际收集时间升序，主户以称重进场时间计
        participants.sort((a, b) -> a.collectTime.compareTo(b.collectTime));
        for (int i = 0; i < participants.size(); i++) {
            participants.get(i).sequence = i + 1;
        }

        BigDecimal mergedEst = participants.stream().map(p -> p.estWeight).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mergedDev = pctDev(net, mergedEst);
        r.setMergedEstimatedKg(mergedEst);
        if (participants.size() > 1) {
            r.setSameDayAppointments(participants.subList(1, participants.size()).stream()
                    .map(p -> p.appt.getApptNo() + "(" + p.reg.getBuilding() + p.reg.getRoomNumber() + ","
                            + p.report.getBagCount() + "袋,估" + p.estWeight.stripTrailingZeros().toPlainString() + "kg)")
                    .reduce((a, b) -> a + "；" + b).orElse(""));
            reasons.add("按各户验收袋数与材质估算合计 " + mergedEst.stripTrailingZeros().toPlainString()
                    + "kg，与车载净重 " + net.stripTrailingZeros().toPlainString() + "kg 偏差 "
                    + mergedDev.stripTrailingZeros().toPlainString() + "%");
        } else {
            r.setSameDayAppointments(null);
        }

        // 路线分析摘要
        r.setRouteAnalysis("时间窗 " + winFrom + " ~ " + winTo + "，GPS 打点 " + stops.size()
                + " 个，其中其他楼栋装车停留 " + loadingStops.size() + " 处，同车其他派单 "
                + (participants.size() - 1) + " 户");

        // ---- 证据 3：装车照片标签中的其他楼栋 ----
        boolean photoOtherBuilding = false;
        if (r.getPhotoLabels() != null) {
            String labels = r.getPhotoLabels();
            photoOtherBuilding = participants.subList(1, participants.size()).stream()
                    .anyMatch(p -> labels.contains(p.reg.getBuilding()))
                    || !labels.contains(primaryReg.getBuilding());
            if (photoOtherBuilding) {
                reasons.add("装车照片出现非本户（" + primaryReg.getBuilding() + primaryReg.getRoomNumber()
                        + "）楼栋/房号标签：" + labels);
            }
        }
        if (r.getPhotoVisibleBags() != null) {
            reasons.add("装车照片清点可见 " + r.getPhotoVisibleBags() + " 袋，现场验收 "
                    + primaryReport.getBagCount() + " 袋");
        }

        // ---- 证据 4：处置场回执 ----
        Optional<DisposalReceipt> receipt = receiptRepo.findByWeighRecordId(w.getId());
        BigDecimal receiptDev = null;
        if (receipt.isPresent()) {
            receiptDev = pctDev(w.getNetWeightKg(), receipt.get().getReceivedWeightKg());
            r.setReceiptDeviationPct(receiptDev);
            BigDecimal receiptVsEst = pctDev(receipt.get().getReceivedWeightKg(), primaryEst);
            reasons.add("处置场回执接收 " + receipt.get().getReceivedWeightKg().stripTrailingZeros().toPlainString()
                    + "kg，与车载净重偏差 " + receiptDev.stripTrailingZeros().toPlainString()
                    + "%，与按袋预估偏差 " + receiptVsEst.stripTrailingZeros().toPlainString() + "%");
        } else {
            r.setReceiptDeviationPct(null);
        }

        // ---- 综合判定 ----
        boolean mergeEvidence = participants.size() > 1
                && (!otherDispatchByAppt.isEmpty() || !loadingStops.isEmpty() || photoOtherBuilding);
        BigDecimal deviceThreshold = props.getReviewDeviceDeviationPct();
        ReviewConclusion suggestion;
        if (mergeEvidence && mergedDev.compareTo(props.getReviewMergeTolerancePct()) <= 0) {
            suggestion = ReviewConclusion.MERGED_COLLECTION;
        } else if (receipt.isPresent()
                && receiptDev.compareTo(deviceThreshold) > 0
                && pctDev(receipt.get().getReceivedWeightKg(), primaryEst)
                .compareTo(props.getReviewReceiptMatchPct()) <= 0) {
            // 地磅重量与按袋估算吻合、与车载秤偏差大 → 车载称重设备异常
            suggestion = ReviewConclusion.DEVICE_ANOMALY;
        } else if (mergeEvidence) {
            // 有合并线索但估算对不上：仍建议合并清运，由物业结合照片人工核定
            suggestion = ReviewConclusion.MERGED_COLLECTION;
            reasons.add("注：合并估算与净重偏差超出 " + props.getReviewMergeTolerancePct()
                    + "% 容差，请物业结合装车照片人工核定拆账");
        } else if (receipt.isPresent() && receiptDev.compareTo(deviceThreshold) <= 0
                && !photoOtherBuilding) {
            // 处置场地磅印证车载重量真实，且无混装证据 → 重量差异源于材质密度，复核无异常
            suggestion = ReviewConclusion.NO_ANOMALY;
            reasons.add("处置场地磅重量与车载净重一致，且无其他楼栋装货证据，重量差异源于材质密度");
        } else if (receipt.isEmpty() && r.getPhotoVisibleBags() == null && stops.isEmpty()
                && otherDispatchByAppt.isEmpty()) {
            suggestion = ReviewConclusion.INCONCLUSIVE;
            reasons.add("缺少处置回执、装车清点、GPS 与同车预约证据，暂无法判定原因");
        } else {
            // 无混装证据：照片清点仅见本户验收袋数（或地磅同样异常），重量无法由袋数/材质解释 → 司机误填
            suggestion = ReviewConclusion.DRIVER_MISENTRY;
            reasons.add("无其他楼栋装货证据，现场验收 " + primaryReport.getBagCount()
                    + " 袋折算约 " + primaryEst.stripTrailingZeros().toPlainString()
                    + "kg，回传重量无法由验收袋数与材质解释，判定为司机称重数据误填");
        }
        r.setSystemSuggestion(suggestion);
        r.setSuggestionReason(String.join("；", reasons));
        reviewRepo.save(r);

        // 刷新系统拆分预案（PENDING 可反复重算）
        if (r.getStatus() == ReviewStatus.PENDING) {
            rebuildShares(r, participants, net);
        }
        traceLogger.record(r.getAppointmentId(), "WEIGH_REVIEW", r.getId(), null, "REVIEW_ANALYZE",
                "系统比对建议=" + suggestion + "：" + r.getSuggestionReason());
        return r;
    }

    // ============================== 物业确认 ==============================

    public record ShareAdjust(Long appointmentId, BigDecimal confirmedWeightKg, String reason) {
    }

    /**
     * 物业确认复核结论。
     * conclusion 为空时采纳系统建议；与建议不一致或逐户调整拆分时必须填写理由。
     */
    @Transactional
    public WeighReview confirm(Session property, Long reviewId, ReviewConclusion conclusion, String reason,
                               BigDecimal manualWeightKg, Boolean suspendVehicle, List<ShareAdjust> shareAdjusts) {
        WeighReview r = requirePending(reviewId);
        WeighRecord w = weighRepo.findById(r.getWeighRecordId()).orElseThrow();
        CollectionAppointment primaryAppt = apptRepo.findById(r.getAppointmentId()).orElseThrow();
        DailyReport primaryReport = reportRepo.findById(primaryAppt.getReportId()).orElseThrow();

        // 确认前用最新证据重新分析一次
        r = analyze(reviewId);
        ReviewConclusion finalC = conclusion != null ? conclusion : r.getSystemSuggestion();
        boolean override = finalC != r.getSystemSuggestion();
        if ((override || shareAdjusts != null && !shareAdjusts.isEmpty())
                && (reason == null || reason.isBlank())) {
            throw new BizException("推翻系统建议或人工调整拆账时必须填写调整理由");
        }
        if (finalC == null) {
            throw new BizException("系统证据不足以给出建议，请物业明确复核结论");
        }
        if (finalC == ReviewConclusion.INCONCLUSIVE && (reason == null || reason.isBlank())) {
            throw new BizException("认定证据不足必须填写情况说明（维持原计费的依据与后续跟进）");
        }

        BigDecimal primaryEst = props.getAvgBagWeightKg().multiply(BigDecimal.valueOf(primaryReport.getBagCount()));
        BigDecimal confirmedWeight = switch (finalC) {
            case MERGED_COLLECTION -> w.getNetWeightKg();
            case DEVICE_ANOMALY -> receiptRepo.findByWeighRecordId(w.getId())
                    .map(DisposalReceipt::getReceivedWeightKg)
                    .orElseThrow(() -> new BizException("认定称重设备异常必须先补传处置场回执"));
            case DRIVER_MISENTRY -> primaryEst;
            case NO_ANOMALY, INCONCLUSIVE -> w.getNetWeightKg();
        };
        if (manualWeightKg != null) {
            if (manualWeightKg.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException("人工认定重量必须大于 0");
            }
            confirmedWeight = manualWeightKg.setScale(2, RoundingMode.HALF_UP);
        }

        boolean adjusted = override || (manualWeightKg != null);
        r.setFinalConclusion(finalC);
        r.setConfirmedWeightKg(confirmedWeight);
        r.setConfirmerId(property.userId());
        r.setConfirmerName(property.name());
        r.setConfirmedAt(LocalDateTime.now());
        r.setStatus(ReviewStatus.CONFIRMED);

        boolean suspend = switch (finalC) {
            case MERGED_COLLECTION, DRIVER_MISENTRY -> true;
            default -> false;
        };
        if (suspendVehicle != null) {
            suspend = suspendVehicle;
            if (suspendVehicle && !List.of(ReviewConclusion.MERGED_COLLECTION, ReviewConclusion.DRIVER_MISENTRY)
                    .contains(finalC)) {
                adjusted = true; // 非默认冻结情形需要理由
                if (reason == null || reason.isBlank()) {
                    throw new BizException("冻结车辆必须填写理由");
                }
            }
        }

        switch (finalC) {
            case MERGED_COLLECTION -> applyMergedCollection(property, r, w, primaryAppt, shareAdjusts, reason);
            case DEVICE_ANOMALY -> applyDeviceAnomaly(property, r, w, confirmedWeight, reason);
            case DRIVER_MISENTRY -> applyDriverMisentry(property, r, w, confirmedWeight, reason);
            case NO_ANOMALY -> traceLogger.record(r.getAppointmentId(), "WEIGH_REVIEW", r.getId(), property,
                    "REVIEW_CONFIRM", "复核无异常，维持原计量净重 " + confirmedWeight + "kg");
            case INCONCLUSIVE -> traceLogger.record(r.getAppointmentId(), "WEIGH_REVIEW", r.getId(), property,
                    "REVIEW_CONFIRM", "证据不足，维持原计费，说明：" + reason);
        }

        if (suspend) {
            suspendVehicle(w, r, property, finalC, reason != null ? reason : r.getSuggestionReason());
        }

        r.setManualAdjusted(adjusted || (shareAdjusts != null && !shareAdjusts.isEmpty()));
        r.setAdjustReason(reason);
        reviewRepo.save(r);

        // 自动办结对应的称重异常投诉
        resolveAnomalyComplaint(r, property, finalC, confirmedWeight, reason);

        traceLogger.record(r.getAppointmentId(), "WEIGH_REVIEW", r.getId(), property, "REVIEW_CONFIRM",
                "物业[" + property.name() + "]确认复核结论=" + finalC + "，有效计费重量 "
                        + confirmedWeight + "kg" + (reason != null ? "，理由：" + reason : "")
                        + (suspend ? "，车辆已暂停派单" : ""));
        return r;
    }

    // ============================== 结论落地：合并清运 ==============================

    private void applyMergedCollection(Session property, WeighReview r, WeighRecord w,
                                       CollectionAppointment primaryAppt, List<ShareAdjust> shareAdjusts,
                                       String reason) {
        List<WeighReviewShare> shares = shareRepo.findByReviewIdOrderByLoadSequenceAsc(r.getId());
        if (shares.size() < 2) {
            throw new BizException("无多户拆分预案，不能按合并清运确认（请先补传 GPS 停留点/同车预约/照片标签证据）");
        }

        // 物业逐户人工调整（强制理由），校验拆分合计与车载净重一致
        if (shareAdjusts != null) {
            for (ShareAdjust adj : shareAdjusts) {
                if (adj.confirmedWeightKg() == null || adj.confirmedWeightKg().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BizException("拆账重量必须大于 0");
                }
                if (adj.reason() == null || adj.reason().isBlank()) {
                    throw new BizException("人工调整每户拆账必须填写理由");
                }
                WeighReviewShare s = shares.stream()
                        .filter(x -> Objects.equals(x.getAppointmentId(), adj.appointmentId())).findFirst()
                        .orElseThrow(() -> new BizException("拆账预约 " + adj.appointmentId() + " 不在复核范围内"));
                s.setConfirmedWeightKg(adj.confirmedWeightKg().setScale(2, RoundingMode.HALF_UP));
                s.setManualAdjusted(true);
                s.setAdjustReason(adj.reason());
            }
            BigDecimal sum = shares.stream().map(WeighReviewShare::getConfirmedWeightKg)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal diff = sum.subtract(w.getNetWeightKg()).abs();
            if (diff.compareTo(new BigDecimal("0.05")) > 0) {
                throw new BizException("各户拆账合计 " + sum + "kg 与车载净重 " + w.getNetWeightKg()
                        + "kg 不一致（差 " + diff + "kg），请重新分配");
            }
        }

        // 结算费拆分：总清运结算费 = 净重吨 × 吨单价（总额不变，按户重量占比拆分），尾差归最后装车户
        BigDecimal totalFee = w.getNetWeightKg().divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP)
                .multiply(props.getCollectorPricePerTon()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < shares.size(); i++) {
            WeighReviewShare s = shares.get(i);
            BigDecimal fee;
            if (i == shares.size() - 1) {
                fee = totalFee.subtract(allocated);
            } else {
                fee = s.getConfirmedWeightKg().divide(w.getNetWeightKg(), 6, RoundingMode.HALF_UP)
                        .multiply(totalFee).setScale(2, RoundingMode.HALF_UP);
                allocated = allocated.add(fee);
            }
            s.setCollectorFee(fee);
            shareRepo.save(s);
        }

        StringBuilder splitDesc = new StringBuilder();
        for (WeighReviewShare s : shares) {
            splitAppointment(property, r, w, s, primaryAppt.getId().equals(s.getAppointmentId()));
            splitDesc.append(s.getBuildingRoom()).append("=")
                    .append(s.getConfirmedWeightKg().stripTrailingZeros().toPlainString()).append("kg/")
                    .append(s.getCollectorFee().stripTrailingZeros().toPlainString()).append("元；");
        }

        // 在清运公司结算单上标注多户拆分（总额与原结算一致，费用归属到各户）
        Bill collectorBill = billRepo.findByAppointmentId(primaryAppt.getId()).stream()
                .filter(b -> b.getBillType() == BillType.COLLECTOR).findFirst().orElse(null);
        if (collectorBill != null) {
            collectorBill.setDetail((collectorBill.getDetail() == null ? "" : collectorBill.getDetail())
                    + " [复核" + r.getReviewNo() + " 合并清运拆账] " + splitDesc);
            billRepo.save(collectorBill);
        }
        traceLogger.record(primaryAppt.getId(), "WEIGH_REVIEW", r.getId(), property, "REVIEW_SPLIT",
                "合并清运费用按" + shares.size() + "户拆分：" + splitDesc
                        + (reason != null ? "人工调整理由：" + reason : ""));
    }

    /** 单户落地：主户维持原业主账单；被合并户补建业主账单（押金抵扣）、释放垃圾点库存、推进追溯与派单状态 */
    private void splitAppointment(Session property, WeighReview r, WeighRecord w,
                                  WeighReviewShare s, boolean primary) {
        CollectionAppointment appt = apptRepo.findById(s.getAppointmentId()).orElseThrow();
        if (primary) {
            // 主户业主账单按袋计费已在称重后生成，金额应与拆分 ownerFee 一致；仅做留痕
            traceLogger.record(appt.getId(), "WEIGH_REVIEW", r.getId(), property, "REVIEW_SHARE",
                    "主户" + s.getBuildingRoom() + "分摊净重 " + s.getConfirmedWeightKg()
                            + "kg，业主按袋清运费 " + s.getOwnerFee() + " 元维持原账单");
            return;
        }

        // 被合并户：此前没有称重/计费，补建业主账单并联动押金
        billingService.settleMergedAppointment(appt, s, r, property);

        // 派单若存在（司机挂了单但没称重），补齐为完成
        dispatchRepo.findByAppointmentId(appt.getId()).ifPresent(d -> {
            if (d.getStatus() != DispatchStatus.COMPLETED) {
                d.setStatus(DispatchStatus.COMPLETED);
                dispatchRepo.save(d);
            }
        });
        appt.setStatus(ApptStatus.COMPLETED);
        apptRepo.save(appt);

        // 释放该户在垃圾点占用的库存
        DailyReport rep = reportRepo.findById(appt.getReportId()).orElseThrow();
        WastePoint point = wastePointRepo.findById(appt.getWastePointId()).orElseThrow();
        point.setCurrentBags(Math.max(0, point.getCurrentBags() - rep.getBagCount()));
        wastePointRepo.save(point);

        traceUpdater.advance(appt.getId(), TraceStatus.LOADED);
        traceUpdater.advance(appt.getId(), TraceStatus.WEIGHED);
        traceUpdater.advance(appt.getId(), TraceStatus.DISPOSED);
        traceUpdater.advance(appt.getId(), TraceStatus.SETTLED);
    }

    // ============================== 结论落地：设备异常 / 误填 ==============================

    private void applyDeviceAnomaly(Session property, WeighReview r, WeighRecord w,
                                    BigDecimal confirmedWeight, String reason) {
        BigDecimal newFee = confirmedWeight.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP)
                .multiply(props.getCollectorPricePerTon()).setScale(2, RoundingMode.HALF_UP);
        billingService.correctCollectorFee(r.getAppointmentId(), r.getReviewNo(), newFee,
                "称重设备异常：以处置场回执重量 " + confirmedWeight + "kg 为准重算清运结算"
                        + (reason != null ? "；物业说明：" + reason : ""), property);
        w.setAnomalyReason((w.getAnomalyReason() == null ? "" : w.getAnomalyReason())
                + " [复核" + r.getReviewNo() + "] 认定地磅/车载秤设备异常，结算以回执 " + confirmedWeight + "kg 为准");
        weighRepo.save(w);
    }

    private void applyDriverMisentry(Session property, WeighReview r, WeighRecord w,
                                     BigDecimal confirmedWeight, String reason) {
        BigDecimal newFee = confirmedWeight.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP)
                .multiply(props.getCollectorPricePerTon()).setScale(2, RoundingMode.HALF_UP);
        billingService.correctCollectorFee(r.getAppointmentId(), r.getReviewNo(), newFee,
                "司机称重误填：以现场验收袋数折算 " + confirmedWeight + "kg 为准重算清运结算"
                        + (reason != null ? "；物业说明：" + reason : ""), property);
        w.setAnomalyReason((w.getAnomalyReason() == null ? "" : w.getAnomalyReason())
                + " [复核" + r.getReviewNo() + "] 认定司机误填，结算以验收袋数折算 " + confirmedWeight + "kg 为准");
        weighRepo.save(w);
    }

    // ============================== 车辆派单权限 ==============================

    private void suspendVehicle(WeighRecord w, WeighReview r, Session property,
                                ReviewConclusion conclusion, String reason) {
        // 复核按车牌冻结：同车牌可能有多辆车档案，按车牌匹配全部冻结
        List<Vehicle> vehicles = vehicleRepo.findAll().stream()
                .filter(v -> v.getPlateNo().equals(w.getPlateNo())).toList();
        for (Vehicle v : vehicles) {
            if (Boolean.TRUE.equals(v.getDispatchSuspended())) {
                continue;
            }
            v.setDispatchSuspended(true);
            v.setSuspendReason("称重复核 " + r.getReviewNo() + " 认定" + conclusionText(conclusion)
                    + "，暂停后续派单；" + reason);
            v.setSuspendReviewId(r.getId());
            v.setSuspendedAt(LocalDateTime.now());
            vehicleRepo.save(v);
        }
        traceLogger.record(r.getAppointmentId(), "WEIGH_REVIEW", r.getId(), property, "VEHICLE_SUSPEND",
                "车辆 " + w.getPlateNo() + "（司机 " + w.getDriverName() + "）因" + conclusionText(conclusion)
                        + "被暂停后续派单权限");
    }

    /** 物业核查后解冻车辆 */
    @Transactional
    public Vehicle unsuspend(Session property, Long vehicleId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException("解冻车辆必须填写核查说明");
        }
        Vehicle v = vehicleRepo.findById(vehicleId).orElseThrow(() -> new BizException(404, "车辆不存在"));
        if (!Boolean.TRUE.equals(v.getDispatchSuspended())) {
            throw new BizException("车辆未被冻结");
        }
        Long reviewId = v.getSuspendReviewId();
        Long apptId = reviewId == null ? null
                : reviewRepo.findById(reviewId).map(WeighReview::getAppointmentId).orElse(null);
        v.setDispatchSuspended(false);
        v.setSuspendReason(null);
        v.setSuspendReviewId(null);
        v.setSuspendedAt(null);
        vehicleRepo.save(v);
        traceLogger.record(apptId, "WEIGH_REVIEW", reviewId, property, "VEHICLE_UNSUSPEND",
                "车辆 " + v.getPlateNo() + " 经物业核查后恢复派单权限，说明：" + reason);
        return v;
    }

    // ============================== 投诉联动 ==============================

    private void resolveAnomalyComplaint(WeighReview r, Session property,
                                         ReviewConclusion conclusion, BigDecimal confirmedWeight, String reason) {
        List<Complaint> open = complaintRepo.findAll().stream()
                .filter(c -> c.getType() == ComplaintType.WEIGH_ANOMALY)
                .filter(c -> Objects.equals(c.getTargetAppointmentId(), r.getAppointmentId()))
                .filter(c -> c.getStatus() == ComplaintStatus.OPEN)
                .toList();
        for (Complaint c : open) {
            c.setStatus(ComplaintStatus.RESOLVED);
            c.setHandlerId(property.userId());
            c.setHandlerName(property.name());
            c.setResolution("称重复核 " + r.getReviewNo() + " 结论：" + conclusionText(conclusion)
                    + "，有效计费重量 " + confirmedWeight + "kg"
                    + (reason != null ? "；" + reason : "；" + r.getSuggestionReason()));
            c.setResolvedAt(LocalDateTime.now());
            complaintRepo.save(c);
            traceLogger.record(r.getAppointmentId(), "COMPLAINT", c.getId(), property,
                    "AUTO_RESOLVE_WEIGH", "称重异常投诉随复核确认自动办结");
        }
    }

    // ============================== 查询 ==============================

    public List<WeighReview> list(ReviewStatus status) {
        return status != null ? reviewRepo.findByStatusOrderByIdDesc(status) : reviewRepo.findAll();
    }

    @Transactional
    public Map<String, Object> get(Long id) {
        WeighReview r = reviewRepo.findById(id).orElseThrow(() -> new BizException(404, "复核单不存在"));
        WeighRecord w = weighRepo.findById(r.getWeighRecordId()).orElseThrow();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("review", r);
        result.put("weighRecord", w);
        result.put("shares", shareRepo.findByReviewIdOrderByLoadSequenceAsc(id));
        int windowH = props.getReviewMergeWindowHours();
        result.put("stops", stopRepo.findByPlateNoAndEventTimeBetweenOrderByEventTimeAsc(
                r.getPlateNo(), w.getEntryTime().minusHours(windowH), w.getExitTime().plusHours(windowH)));
        receiptRepo.findByWeighRecordId(r.getWeighRecordId()).ifPresent(rc -> result.put("disposalReceipt", rc));
        return result;
    }

    public long pendingCount() {
        return reviewRepo.findByStatusOrderByIdDesc(ReviewStatus.PENDING).size();
    }

    public List<Vehicle> suspendedVehicles() {
        return vehicleRepo.findAll().stream().filter(v -> Boolean.TRUE.equals(v.getDispatchSuspended())).toList();
    }

    // ============================== 内部工具 ==============================

    private WeighReview requirePending(Long reviewId) {
        WeighReview r = reviewRepo.findById(reviewId)
                .orElseThrow(() -> new BizException(404, "复核单不存在"));
        if (r.getStatus() == ReviewStatus.CONFIRMED) {
            throw new BizException("复核单已确认，不能重复操作");
        }
        return r;
    }

    private Participant loadParticipant(CollectionAppointment appt, DailyReport report,
                                        RenovationRegistration reg, DispatchOrder dispatch,
                                        LocalDateTime collectTime, int sequence) {
        BigDecimal bagWeight = estimatedBagWeight(report);
        BigDecimal est = bagWeight.multiply(BigDecimal.valueOf(report.getBagCount()))
                .setScale(2, RoundingMode.HALF_UP);
        return new Participant(appt, report, reg, dispatch, collectTime, sequence, bagWeight, est);
    }

    /** 依据上报材质取单袋估算重量：多材质取均值，无材质标记时取全局平均袋重 */
    private BigDecimal estimatedBagWeight(DailyReport report) {
        Map<String, BigDecimal> cfg = props.getMaterialBagWeightKg();
        List<BigDecimal> vals = new ArrayList<>();
        if (Boolean.TRUE.equals(report.getContainsWood())) {
            vals.add(cfg.getOrDefault("wood", props.getAvgBagWeightKg()));
        }
        if (Boolean.TRUE.equals(report.getContainsGlass())) {
            vals.add(cfg.getOrDefault("glass", props.getAvgBagWeightKg()));
        }
        if (Boolean.TRUE.equals(report.getContainsTile())) {
            vals.add(cfg.getOrDefault("tile", props.getAvgBagWeightKg()));
        }
        if (Boolean.TRUE.equals(report.getContainsMixedHousehold())) {
            vals.add(cfg.getOrDefault("mixed", props.getAvgBagWeightKg()));
        }
        if (vals.isEmpty()) {
            return props.getAvgBagWeightKg();
        }
        return vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(vals.size()), 2, RoundingMode.HALF_UP);
    }

    /** 重建系统拆分预案：按材质估算占比分摊净重，尾差（最后装车户）兜底使合计严格等于净重 */
    private void rebuildShares(WeighReview r, List<Participant> participants, BigDecimal net) {
        shareRepo.findByReviewIdOrderByLoadSequenceAsc(r.getId())
                .forEach(shareRepo::delete);

        BigDecimal totalEst = participants.stream().map(p -> p.estWeight).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unitPrice = props.getBagUnitPrice();
        BigDecimal extraMult = props.getExtraBagMultiplier();
        BigDecimal pricePerTon = props.getCollectorPricePerTon();
        BigDecimal totalFee = net.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP)
                .multiply(pricePerTon).setScale(2, RoundingMode.HALF_UP);

        BigDecimal allocatedWeight = BigDecimal.ZERO;
        BigDecimal allocatedFee = BigDecimal.ZERO;
        for (int i = 0; i < participants.size(); i++) {
            Participant p = participants.get(i);
            boolean last = i == participants.size() - 1;
            BigDecimal weight;
            BigDecimal fee;
            if (participants.size() == 1) {
                weight = net;
                fee = totalFee;
            } else if (last) {
                weight = net.subtract(allocatedWeight);
                fee = totalFee.subtract(allocatedFee);
            } else {
                weight = p.estWeight.divide(totalEst, 6, RoundingMode.HALF_UP)
                        .multiply(net).setScale(2, RoundingMode.HALF_UP);
                fee = weight.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP)
                        .multiply(pricePerTon).setScale(2, RoundingMode.HALF_UP);
                allocatedWeight = allocatedWeight.add(weight);
                allocatedFee = allocatedFee.add(fee);
            }
            int normalBags = p.report.getBagCount() - p.report.getExtraBagCount();
            BigDecimal ownerFee = unitPrice.multiply(BigDecimal.valueOf(normalBags))
                    .add(unitPrice.multiply(extraMult).multiply(BigDecimal.valueOf(p.report.getExtraBagCount())))
                    .setScale(2, RoundingMode.HALF_UP);

            WeighReviewShare s = new WeighReviewShare();
            s.setReviewId(r.getId());
            s.setAppointmentId(p.appt.getId());
            s.setRegistrationId(p.reg.getId());
            s.setOwnerName(p.reg.getOwnerName());
            s.setBuildingRoom(p.reg.getBuilding() + p.reg.getRoomNumber());
            s.setAcceptedBags(p.report.getBagCount());
            s.setExtraBags(p.report.getExtraBagCount());
            s.setEstBagWeightKg(p.bagWeight);
            s.setEstimatedWeightKg(p.estWeight);
            s.setLoadSequence(p.sequence);
            s.setSystemWeightKg(weight);
            s.setConfirmedWeightKg(weight);
            s.setCollectorFee(fee);
            s.setOwnerFee(ownerFee);
            shareRepo.save(s);
        }
    }

    /** 偏差百分比：|a-b|/b×100 */
    private BigDecimal pctDev(BigDecimal a, BigDecimal b) {
        if (b.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return a.subtract(b).abs().multiply(BigDecimal.valueOf(100))
                .divide(b, 2, RoundingMode.HALF_UP);
    }

    private String conclusionText(ReviewConclusion c) {
        return switch (c) {
            case MERGED_COLLECTION -> "合并清运（混装其他楼栋垃圾）";
            case DEVICE_ANOMALY -> "称重设备异常";
            case DRIVER_MISENTRY -> "司机称重误填";
            case NO_ANOMALY -> "复核无异常";
            case INCONCLUSIVE -> "证据不足";
        };
    }

    /** 合并参与户内部载体 */
    private static final class Participant {
        final CollectionAppointment appt;
        final DailyReport report;
        final RenovationRegistration reg;
        final DispatchOrder dispatch;
        final LocalDateTime collectTime;
        final BigDecimal bagWeight;
        final BigDecimal estWeight;
        int sequence;

        Participant(CollectionAppointment appt, DailyReport report, RenovationRegistration reg,
                    DispatchOrder dispatch, LocalDateTime collectTime, int sequence,
                    BigDecimal bagWeight, BigDecimal estWeight) {
            this.appt = appt;
            this.report = report;
            this.reg = reg;
            this.dispatch = dispatch;
            this.collectTime = collectTime;
            this.sequence = sequence;
            this.bagWeight = bagWeight;
            this.estWeight = estWeight;
        }
    }
}
