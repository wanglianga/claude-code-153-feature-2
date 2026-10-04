package com.waste.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * 称重差异复核服务。
 *
 * 车辆回传净重明显高于现场验收袋数时，物业发起复核，系统汇总五类证据：
 * 1) 装车照片（weigh_record.photos）；
 * 2) 车辆路线（dispatch_order.route_info）；
 * 3) 司机停留点（vehicle_stop，匹配同日其他楼栋垃圾点）；
 * 4) 处置场回执（disposal_receipt，与小区地磅净重交叉比对）；
 * 5) 同日其他预约（未单独称重的疑似被混装预约）。
 *
 * 判定：合并清运 / 称重设备异常 / 司机误填 / 疑似混装证据不足 / 无异常。
 * 合并清运按"各户验收袋数 × 材质单袋重量"占比拆分整车净重（装车顺序决定末位尾差），
 * 联动业主分摊账单、清运公司结算拆分与车辆派单权限；物业可人工调整拆分重量但必须留理由。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeightReviewService {

    private final WeightReviewRepository reviewRepo;
    private final WeightReviewItemRepository itemRepo;
    private final WeighRecordRepository weighRepo;
    private final DispatchOrderRepository dispatchRepo;
    private final CollectionAppointmentRepository apptRepo;
    private final DailyReportRepository reportRepo;
    private final RenovationRegistrationRepository regRepo;
    private final WastePointRepository pointRepo;
    private final DisposalReceiptRepository receiptRepo;
    private final VehicleStopRepository stopRepo;
    private final VehicleRepository vehicleRepo;
    private final BillRepository billRepo;
    private final DepositDeductionRepository deductionRepo;
    private final ComplaintRepository complaintRepo;
    private final WasteProperties props;
    private final TraceLogger traceLogger;
    private final TraceUpdater traceUpdater;
    private final ObjectMapper objectMapper;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    // ============================ 证据采集 ============================

    /** 发起复核：汇总证据并给出系统判定建议（不改变任何账单/车辆状态） */
    @Transactional
    public WeightReview create(Session property, Long weighRecordId, List<Long> suspectAppointmentIds, String note) {
        WeighRecord w = weighRepo.findById(weighRecordId)
                .orElseThrow(() -> new BizException(404, "称重记录不存在"));
        reviewRepo.findByWeighRecordId(weighRecordId).ifPresent(r -> {
            throw new BizException("该称重记录已存在复核单 " + r.getReviewNo());
        });
        DispatchOrder dispatch = dispatchRepo.findById(w.getDispatchId())
                .orElseThrow(() -> new BizException(404, "派单不存在"));
        CollectionAppointment primaryAppt = apptRepo.findById(w.getAppointmentId())
                .orElseThrow(() -> new BizException(404, "预约不存在"));

        WeightReview r = analyze(w, dispatch, primaryAppt, suspectAppointmentIds, note);
        reviewRepo.save(r);

        traceLogger.record(primaryAppt.getId(), "WEIGHT_REVIEW", r.getId(), property, "REVIEW_CREATE",
                "发起称重差异复核 " + r.getReviewNo() + "：净重 " + w.getNetWeightKg() + "kg / 预估 "
                        + w.getEstimatedWeightKg() + "kg，偏差 " + w.getDeviationPct() + "%，系统建议="
                        + r.getSuggestion() + (note != null ? "，备注: " + note : ""));
        return r;
    }

    /**
     * 补充证据后重新分析（仅待定论复核单）：
     * 物业补录停留点/手工指认疑似预约或处置场补传回执后，重新汇总五类证据并刷新判定建议。
     */
    @Transactional
    public WeightReview reanalyze(Session property, Long reviewId, List<Long> suspectAppointmentIds, String note) {
        WeightReview r = reviewRepo.findById(reviewId)
                .orElseThrow(() -> new BizException(404, "复核单不存在"));
        if (r.getStatus() != ReviewStatus.PENDING) {
            throw new BizException("复核单已定论，不能重新分析");
        }
        WeighRecord w = weighRepo.findById(r.getWeighRecordId()).orElseThrow();
        DispatchOrder dispatch = dispatchRepo.findById(r.getDispatchId()).orElseThrow();
        CollectionAppointment primaryAppt = apptRepo.findById(r.getAppointmentId()).orElseThrow();

        // 未显式传备注时保留原备注
        String keepNote = note;
        if (keepNote == null) {
            Object oldNote = readEvidence(r).get("note");
            keepNote = oldNote == null ? null : String.valueOf(oldNote);
        }
        // 未显式传疑似预约时沿用原手工指认
        List<Long> keepSuspects = suspectAppointmentIds;
        if (keepSuspects == null) {
            Object oldIds = readEvidence(r).get("manualSuspectAppointmentIds");
            if (oldIds instanceof List<?> list) {
                keepSuspects = list.stream().map(String::valueOf).map(Long::valueOf).toList();
            } else {
                keepSuspects = List.of();
            }
        }
        WeightReview refreshed = analyze(w, dispatch, primaryAppt, keepSuspects, keepNote);
        r.setEvidenceSummary(refreshed.getEvidenceSummary());
        r.setDisposalWeightKg(refreshed.getDisposalWeightKg());
        r.setSuggestion(refreshed.getSuggestion());
        reviewRepo.save(r);
        traceLogger.record(r.getAppointmentId(), "WEIGHT_REVIEW", r.getId(), property, "REVIEW_REANALYZE",
                "补充证据后重新分析 " + r.getReviewNo() + "，系统建议刷新为=" + r.getSuggestion());
        return r;
    }

    /** 汇总五类证据并生成判定建议（不落库） */
    private WeightReview analyze(WeighRecord w, DispatchOrder dispatch, CollectionAppointment primaryAppt,
                                 List<Long> suspectAppointmentIds, String note) {
        LocalDate day = w.getEntryTime() != null ? w.getEntryTime().toLocalDate() : primaryAppt.getApptDate();

        // ---- 证据 1：装车照片 ----
        List<String> photos = w.getPhotos() == null || w.getPhotos().isBlank()
                ? List.of() : Arrays.stream(w.getPhotos().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();

        // ---- 证据 2/3：车辆路线 + 当日司机停留点 ----
        List<VehicleStop> dayStops = stopRepo.findByPlateNoAndStopTimeBetweenOrderByStopTimeAsc(
                w.getPlateNo(), day.atStartOfDay(), day.atTime(LocalTime.MAX));

        // ---- 证据 5：同日其他预约（预约日期=称重当日，排除已有独立称重记录或已被其他复核拆分的） ----
        List<CollectionAppointment> sameDay = apptRepo.findAll().stream()
                .filter(a -> !a.getId().equals(primaryAppt.getId()))
                .filter(a -> a.getStatus() == ApptStatus.APPROVED
                        || a.getStatus() == ApptStatus.DISPATCHED
                        || a.getStatus() == ApptStatus.COMPLETED)
                .filter(a -> day.equals(a.getApptDate()))
                .filter(a -> weighRepo.findByAppointmentId(a.getId()).isEmpty())
                .filter(a -> !itemRepo.existsByAppointmentId(a.getId()))
                .toList();

        // 停留点 ↔ 垃圾点/同日预约匹配
        List<Map<String, Object>> stopEvidence = new ArrayList<>();
        LinkedHashSet<Long> matchedApptIds = new LinkedHashSet<>();
        if (suspectAppointmentIds != null) {
            matchedApptIds.addAll(suspectAppointmentIds);
        }
        for (VehicleStop stop : dayStops) {
            Map<String, Object> se = new LinkedHashMap<>();
            se.put("stopTime", stop.getStopTime());
            se.put("location", stop.getLocation());
            se.put("durationMinutes", stop.getDurationMinutes());
            se.put("source", stop.getSource());
            se.put("note", stop.getNote());
            String matched = null;
            if (stop.getMatchedAppointmentId() != null) {
                // 已绑定的停留点：绑定对象仍是本次候选预约 → 沿用；否则属于其他复核，标记已绑定不重复计入
                CollectionAppointment bound = sameDay.stream()
                        .filter(a -> a.getId().equals(stop.getMatchedAppointmentId()))
                        .findFirst().orElse(null);
                if (bound != null) {
                    WastePoint bp = pointRepo.findById(bound.getWastePointId()).orElse(null);
                    matched = bound.getApptNo() + "(" + (bp == null ? "" : bp.getName()) + ")";
                    matchedApptIds.add(bound.getId());
                } else {
                    String boundApptNo = apptRepo.findById(stop.getMatchedAppointmentId())
                            .map(a -> a.getApptNo() + "(已绑定)").orElse("(已绑定)");
                    se.put("matchedAppointment", boundApptNo);
                    se.put("loadingSuspected", false);
                    stopEvidence.add(se);
                    continue;
                }
            } else {
                for (CollectionAppointment a : sameDay) {
                    WastePoint p = pointRepo.findById(a.getWastePointId()).orElse(null);
                    if (p != null && locationHit(stop.getLocation(), p)
                            && stop.getDurationMinutes() >= props.getMergedStopMinutes()) {
                        matched = a.getApptNo() + "(" + p.getName() + ")";
                        stop.setMatchedAppointmentId(a.getId());
                        stopRepo.save(stop);
                        matchedApptIds.add(a.getId());
                        break;
                    }
                }
            }
            se.put("matchedAppointment", matched);
            se.put("loadingSuspected", matched != null);
            stopEvidence.add(se);
        }
        // 手工指定的疑似预约也进入证据
        List<Map<String, Object>> otherApptEvidence = new ArrayList<>();
        for (Long aid : matchedApptIds) {
            apptRepo.findById(aid).filter(a -> !a.getId().equals(primaryAppt.getId())).ifPresent(a -> {
                DailyReport r = reportRepo.findById(a.getReportId()).orElseThrow();
                RenovationRegistration reg = regRepo.findById(a.getRegistrationId()).orElseThrow();
                WastePoint p = pointRepo.findById(a.getWastePointId()).orElseThrow();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("appointmentId", a.getId());
                m.put("apptNo", a.getApptNo());
                m.put("buildingRoom", reg.getBuilding() + "-" + reg.getRoomNumber());
                m.put("ownerName", reg.getOwnerName());
                m.put("wastePoint", p.getName());
                m.put("acceptedBags", r.getBagCount());
                m.put("materialBagWeightKg", materialBagWeight(r));
                m.put("materialEstimateKg", materialEstimate(r));
                m.put("status", a.getStatus().name());
                otherApptEvidence.add(m);
            });
        }

        // ---- 证据 4：处置场回执，与小区地磅净重交叉比对 ----
        DisposalReceipt receipt = receiptRepo.findByWeighRecordId(w.getId()).orElse(null);
        Map<String, Object> receiptEvidence = new LinkedHashMap<>();
        if (receipt != null) {
            BigDecimal diffPct = pctDiff(w.getNetWeightKg(), receipt.getReceivedWeightKg(), w.getNetWeightKg());
            receiptEvidence.put("receiptNo", receipt.getReceiptNo());
            receiptEvidence.put("disposalSite", receipt.getDisposalSite());
            receiptEvidence.put("receivedWeightKg", receipt.getReceivedWeightKg());
            receiptEvidence.put("netWeightKg", w.getNetWeightKg());
            receiptEvidence.put("diffPct", diffPct);
            receiptEvidence.put("scaleMismatch",
                    diffPct.compareTo(props.getScaleDiffThresholdPct()) > 0);
        }

        // ---- 综合判定建议 ----
        WeightConclusion suggestion = decide(w, !matchedApptIds.isEmpty(), receipt, receiptEvidence);
        List<String> reasons = new ArrayList<>();
        if (!matchedApptIds.isEmpty()) {
            reasons.add("车辆当日在其他楼栋垃圾点存在 " + props.getMergedStopMinutes()
                    + " 分钟以上装车停留，且对应预约无独立称重记录，高度疑似合并清运混装");
        }
        if (receipt != null && Boolean.TRUE.equals(receiptEvidence.get("scaleMismatch"))) {
            reasons.add("小区地磅净重 " + w.getNetWeightKg() + "kg 与处置场回执 "
                    + receipt.getReceivedWeightKg() + "kg 偏差 " + receiptEvidence.get("diffPct")
                    + "%，超过阈值 " + props.getScaleDiffThresholdPct() + "%");
        }
        if (suggestion == WeightConclusion.DRIVER_MISENTRY) {
            reasons.add("处置场回执重量与按袋预估吻合，而与车辆回传净重差异巨大，符合司机误填特征");
        }
        if (suggestion == WeightConclusion.SUSPICIOUS_MIXED) {
            reasons.add("净重明显高于按袋预估，但缺少处置场回执与他楼装车停留证据，疑似混装需补充证据");
        }
        if (suggestion == WeightConclusion.NORMAL) {
            reasons.add("处置场回执与净重吻合，重量差异可由材质密度/压实程度解释，未发现混装证据");
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("weighRecordId", w.getId());
        evidence.put("manifestNo", w.getManifestNo());
        evidence.put("photos", photos);
        evidence.put("routeInfo", dispatch.getRouteInfo());
        evidence.put("driverName", w.getDriverName());
        evidence.put("plateNo", w.getPlateNo());
        evidence.put("netWeightKg", w.getNetWeightKg());
        evidence.put("estimatedWeightKg", w.getEstimatedWeightKg());
        evidence.put("deviationPct", w.getDeviationPct());
        evidence.put("stops", stopEvidence);
        evidence.put("otherSameDayAppointments", otherApptEvidence);
        evidence.put("disposalReceipt", receiptEvidence.isEmpty() ? null : receiptEvidence);
        evidence.put("manualSuspectAppointmentIds", suspectAppointmentIds);
        evidence.put("suggestion", suggestion.name());
        evidence.put("suggestionReasons", reasons);
        evidence.put("note", note);
        String evidenceJson;
        try {
            evidenceJson = objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            throw new BizException("证据汇总序列化失败: " + e.getMessage());
        }

        WeightReview r = new WeightReview();
        r.setReviewNo(NoGen.of("REV"));
        r.setWeighRecordId(w.getId());
        r.setAppointmentId(primaryAppt.getId());
        r.setDispatchId(dispatch.getId());
        r.setPlateNo(w.getPlateNo());
        r.setDriverName(w.getDriverName());
        r.setNetWeightKg(w.getNetWeightKg());
        r.setEstimatedWeightKg(w.getEstimatedWeightKg());
        r.setDisposalWeightKg(receipt == null ? null : receipt.getReceivedWeightKg());
        r.setDeviationPct(w.getDeviationPct());
        r.setEvidenceSummary(evidenceJson);
        r.setSuggestion(suggestion);
        return r;
    }

    private boolean locationHit(String stopLocation, WastePoint point) {
        if (stopLocation == null) {
            return false;
        }
        return stopLocation.contains(point.getName()) || point.getName().contains(stopLocation)
                || stopLocation.contains(point.getLocation()) || point.getLocation().contains(stopLocation);
    }

    /** 综合判定 */
    private WeightConclusion decide(WeighRecord w, boolean hasMergedStops,
                                    DisposalReceipt receipt, Map<String, Object> receiptEvidence) {
        if (hasMergedStops) {
            return WeightConclusion.MERGED_COLLECTION;
        }
        boolean highDeviation = w.getDeviationPct().compareTo(props.getWeighAnomalyThresholdPct()) > 0;
        if (receipt != null) {
            if (Boolean.TRUE.equals(receiptEvidence.get("scaleMismatch"))) {
                // 处置场重量与按袋预估吻合（差异在异常阈值内）→ 司机误填；否则认定地磅设备异常
                BigDecimal receiptVsEst = pctDiff(receipt.getReceivedWeightKg(),
                        w.getEstimatedWeightKg(), w.getEstimatedWeightKg());
                if (receiptVsEst.compareTo(props.getWeighAnomalyThresholdPct()) <= 0) {
                    return WeightConclusion.DRIVER_MISENTRY;
                }
                return WeightConclusion.SCALE_MALFUNCTION;
            }
            return WeightConclusion.NORMAL;
        }
        return highDeviation ? WeightConclusion.SUSPICIOUS_MIXED : WeightConclusion.NORMAL;
    }

    /** |a-b|/base×100，保留 2 位 */
    private BigDecimal pctDiff(BigDecimal a, BigDecimal b, BigDecimal base) {
        if (base.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return a.subtract(b).abs().multiply(BigDecimal.valueOf(100))
                .divide(base, 2, RoundingMode.HALF_UP);
    }

    /** 材质单袋重量：按上报材质标记取均值，未标注材质时用全局平均袋重 */
    private BigDecimal materialBagWeight(DailyReport r) {
        List<BigDecimal> mats = new ArrayList<>();
        if (Boolean.TRUE.equals(r.getContainsWood())) {
            mats.add(props.getWoodBagWeightKg());
        }
        if (Boolean.TRUE.equals(r.getContainsGlass())) {
            mats.add(props.getGlassBagWeightKg());
        }
        if (Boolean.TRUE.equals(r.getContainsTile())) {
            mats.add(props.getTileBagWeightKg());
        }
        if (mats.isEmpty()) {
            return props.getAvgBagWeightKg();
        }
        return mats.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(mats.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal materialEstimate(DailyReport r) {
        return materialBagWeight(r).multiply(BigDecimal.valueOf(r.getBagCount()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // ============================ 定论与费用拆分 ============================

    public record AdjustmentReq(Long appointmentId, BigDecimal finalWeightKg, String reason) {
    }

    /**
     * 物业提交复核结论：
     * - MERGED_COLLECTION：多户按袋数+材质估算+装车顺序拆分整车净重，联动业主账单与清运结算拆分；
     * - SCALE_MALFUNCTION / DRIVER_MISENTRY：以处置场回执重量核定，调整清运结算；
     * - NORMAL：重量成立，仅归档证据。
     * blockVehicle=true 时暂停车辆后续派单权限。
     */
    @Transactional
    public WeightReview confirm(Session property, Long reviewId, WeightConclusion conclusion, String note,
                                boolean blockVehicle, List<AdjustmentReq> adjustments) {
        WeightReview r = reviewRepo.findById(reviewId)
                .orElseThrow(() -> new BizException(404, "复核单不存在"));
        if (r.getStatus() != ReviewStatus.PENDING) {
            throw new BizException("复核单已定论，不能重复提交");
        }
        if (conclusion == null) {
            throw new BizException("必须给出复核结论");
        }
        if (conclusion == WeightConclusion.SUSPICIOUS_MIXED) {
            throw new BizException("证据不足不能定论为疑似混装：请补充装车照片/停留点证据后再发起，或改判其他结论");
        }
        if (adjustments != null && !adjustments.isEmpty() && conclusion != WeightConclusion.MERGED_COLLECTION) {
            throw new BizException("只有定论为合并清运时才能提交多户拆分人工调整");
        }
        WeighRecord w = weighRepo.findById(r.getWeighRecordId()).orElseThrow();
        CollectionAppointment primaryAppt = apptRepo.findById(r.getAppointmentId()).orElseThrow();
        DisposalReceipt receipt = receiptRepo.findByWeighRecordId(w.getId()).orElse(null);

        Map<Long, AdjustmentReq> adjustMap = new LinkedHashMap<>();
        if (adjustments != null) {
            for (AdjustmentReq a : adjustments) {
                if (a.finalWeightKg() == null || a.finalWeightKg().compareTo(BigDecimal.ZERO) < 0) {
                    throw new BizException("人工拆分重量不合法");
                }
                adjustMap.put(a.appointmentId(), a);
            }
        }

        switch (conclusion) {
            case MERGED_COLLECTION -> confirmMerged(r, w, primaryAppt, adjustMap, property);
            case SCALE_MALFUNCTION, DRIVER_MISENTRY ->
                    confirmWeightCorrection(r, w, primaryAppt, receipt, conclusion, property);
            case NORMAL -> confirmNormal(r, w, primaryAppt, receipt, property);
            default -> throw new BizException("不支持的结论类型: " + conclusion);
        }

        r.setConclusion(conclusion);
        r.setStatus(ReviewStatus.CONFIRMED);
        r.setConclusionNote(note);
        r.setConfirmerId(property.userId());
        r.setConfirmerName(property.name());
        r.setConfirmedAt(LocalDateTime.now());

        // 车辆后续派单权限
        if (blockVehicle) {
            DispatchOrder d = dispatchRepo.findById(r.getDispatchId()).orElseThrow();
            Vehicle v = vehicleRepo.findById(d.getVehicleId())
                    .orElseThrow(() -> new BizException(404, "车辆不存在"));
            v.setDispatchBlocked(true);
            v.setBlockReason("称重复核 " + r.getReviewNo() + " 认定[" + conclusion.getText()
                    + "]，自 " + LocalDateTime.now() + " 起暂停派单");
            v.setBlockedAt(LocalDateTime.now());
            vehicleRepo.save(v);
            r.setVehicleBlocked(true);
            traceLogger.record(r.getAppointmentId(), "WEIGHT_REVIEW", r.getId(), property, "REVIEW_VEHICLE_BLOCK",
                    "暂停车辆 " + v.getPlateNo() + " 后续派单权限：" + v.getBlockReason());
        }
        reviewRepo.save(r);

        // 自动办结该预约因本次称重异常生成的投诉
        complaintRepo.findByTargetAppointmentId(r.getAppointmentId()).stream()
                .filter(c -> c.getType() == ComplaintType.WEIGH_ANOMALY && c.getStatus() == ComplaintStatus.OPEN)
                .forEach(c -> {
                    c.setStatus(ComplaintStatus.RESOLVED);
                    c.setHandlerId(property.userId());
                    c.setHandlerName(property.name());
                    c.setResolution("称重差异复核 " + r.getReviewNo() + " 定论[" + conclusion.getText() + "]"
                            + (note != null ? "：" + note : ""));
                    c.setResolvedAt(LocalDateTime.now());
                    complaintRepo.save(c);
                });

        traceLogger.record(r.getAppointmentId(), "WEIGHT_REVIEW", r.getId(), property, "REVIEW_CONFIRM",
                "称重差异复核定论[" + conclusion.getText() + "] " + r.getReviewNo()
                        + (note != null ? "，说明: " + note : "")
                        + (blockVehicle ? "，车辆已暂停派单" : ""));
        return r;
    }

    /** 合并清运：多户重量拆分 + 账单联动 */
    private void confirmMerged(WeightReview r, WeighRecord w, CollectionAppointment primaryAppt,
                               Map<Long, AdjustmentReq> adjustMap, Session property) {
        Map<String, Object> evidence = readEvidence(r);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> others = (List<Map<String, Object>>) evidence.getOrDefault("otherSameDayAppointments", List.of());
        if (others.isEmpty()) {
            throw new BizException("未发现其他楼栋/户的合并装车证据，不能定论为合并清运");
        }

        // ---- 组装拆分组：主预约 + 被混装预约，确定装车顺序 ----
        List<SplitGroup> groups = new ArrayList<>();
        DailyReport primaryReport = reportRepo.findById(primaryAppt.getReportId()).orElseThrow();
        groups.add(buildGroup(primaryAppt, primaryReport, 0));
        for (Map<String, Object> o : others) {
            Long aid = Long.valueOf(String.valueOf(o.get("appointmentId")));
            CollectionAppointment a = apptRepo.findById(aid).orElseThrow();
            DailyReport rep = reportRepo.findById(a.getReportId()).orElseThrow();
            groups.add(buildGroup(a, rep, 0));
        }

        // 装车顺序：按当日停留点时间升序（最先装车=1）；无停留点记录的组排在后面
        LocalDate day = w.getEntryTime() != null ? w.getEntryTime().toLocalDate() : primaryAppt.getApptDate();
        Map<Long, LocalDateTime> stopTimeByAppt = new HashMap<>();
        for (VehicleStop stop : stopRepo.findByPlateNoAndStopTimeBetweenOrderByStopTimeAsc(
                r.getPlateNo(), day.atStartOfDay(), day.atTime(LocalTime.MAX))) {
            if (stop.getMatchedAppointmentId() != null
                    && groups.stream().anyMatch(g -> g.appt.getId().equals(stop.getMatchedAppointmentId()))) {
                stopTimeByAppt.merge(stop.getMatchedAppointmentId(), stop.getStopTime(),
                        (x, y) -> x.isBefore(y) ? x : y);
            }
        }
        groups.sort(Comparator.<SplitGroup>comparingLong(g ->
                stopTimeByAppt.getOrDefault(g.appt.getId(), LocalDateTime.MAX).toEpochSecond(java.time.ZoneOffset.UTC))
                .thenComparing(g -> g.appt.getId()));
        for (int i = 0; i < groups.size(); i++) {
            groups.get(i).loadOrder = i + 1;
        }

        // ---- 按 验收袋数 × 材质单袋重量 占比拆分整车净重（尾差归最后装车组）----
        BigDecimal totalEstimate = groups.stream().map(g -> g.materialEstimate).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalNet = w.getNetWeightKg();
        BigDecimal allocatedSum = BigDecimal.ZERO;
        for (int i = 0; i < groups.size(); i++) {
            SplitGroup g = groups.get(i);
            BigDecimal alloc;
            if (i == groups.size() - 1) {
                alloc = totalNet.subtract(allocatedSum);   // 尾差归末位
            } else {
                alloc = totalNet.multiply(g.materialEstimate)
                        .divide(totalEstimate, 2, RoundingMode.HALF_UP);
                allocatedSum = allocatedSum.add(alloc);
            }
            g.allocated = alloc.setScale(2, RoundingMode.HALF_UP);
        }

        // ---- 物业人工调整：被调整组重量锁定，剩余重量在未调整组间按估算占比重分（尾差仍归末位）----
        Set<Long> adjustedIds = new HashSet<>(adjustMap.keySet());
        for (Long aid : adjustedIds) {
            if (groups.stream().noneMatch(g -> g.appt.getId().equals(aid))) {
                throw new BizException("人工调整的预约 " + aid + " 不在本次合并拆分范围内");
            }
        }
        if (!adjustedIds.isEmpty()) {
            BigDecimal fixed = BigDecimal.ZERO;
            for (SplitGroup g : groups) {
                AdjustmentReq req = adjustMap.get(g.appt.getId());
                if (req != null) {
                    if (req.reason() == null || req.reason().isBlank()) {
                        throw new BizException("对预约 " + g.appt.getApptNo() + " 的人工拆分调整必须填写调整理由");
                    }
                    g.finalWeight = req.finalWeightKg().setScale(2, RoundingMode.HALF_UP);
                    fixed = fixed.add(g.finalWeight);
                }
            }
            if (fixed.compareTo(totalNet) >= 0) {
                throw new BizException("人工调整后重量合计 " + fixed + "kg 已达到/超过整车净重 " + totalNet
                        + "kg，无法拆分，请重新调整");
            }
            List<SplitGroup> free = groups.stream().filter(g -> !adjustedIds.contains(g.appt.getId())).toList();
            BigDecimal freeTotalEst = free.stream().map(g -> g.materialEstimate).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal remain = totalNet.subtract(fixed);
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < free.size(); i++) {
                SplitGroup g = free.get(i);
                if (i == free.size() - 1) {
                    g.finalWeight = remain.subtract(sum).setScale(2, RoundingMode.HALF_UP);
                } else {
                    g.finalWeight = remain.multiply(g.materialEstimate)
                            .divide(freeTotalEst, 2, RoundingMode.HALF_UP);
                    sum = sum.add(g.finalWeight);
                }
            }
        } else {
            groups.forEach(g -> g.finalWeight = g.allocated);
        }

        // ---- 落拆分明细 + 账单联动 ----
        String period = primaryAppt.getApptDate().toString().substring(0, 7);
        Bill primaryCollectorBill = requireCollectorBill(primaryAppt.getId());
        String companyName = dispatchRepo.findById(r.getDispatchId()).orElseThrow().getCompanyName();
        for (SplitGroup g : groups) {
            WeightReviewItem item = new WeightReviewItem();
            item.setReviewId(r.getId());
            item.setAppointmentId(g.appt.getId());
            item.setRegistrationId(g.reg.getId());
            item.setOwnerName(g.reg.getOwnerName());
            item.setBuildingRoom(g.reg.getBuilding() + "-" + g.reg.getRoomNumber());
            item.setAcceptedBags(g.report.getBagCount());
            item.setMaterialBagWeightKg(g.perBag);
            item.setMaterialEstimateKg(g.materialEstimate);
            item.setLoadOrder(g.loadOrder);
            item.setAllocatedWeightKg(g.allocated);
            item.setFinalWeightKg(g.finalWeight);
            item.setAllocatedFee(collectorFee(g.finalWeight));
            AdjustmentReq req = adjustMap.get(g.appt.getId());
            if (req != null) {
                item.setManualAdjusted(true);
                item.setAdjustReason(req.reason());
            }

            if (g.appt.getId().equals(primaryAppt.getId())) {
                // 主预约：业主账单仍按本人验收袋数计（袋计费模型），追加重量认定说明
                Bill ownerBill = requireOwnerBill(g.appt.getId());
                annotateBill(ownerBill, "称重复核 " + r.getReviewNo() + " 认定合并清运，本户拆分净重 "
                        + g.finalWeight + "kg（装车顺序第 " + g.loadOrder + " 位，材质估算 "
                        + g.materialEstimate + "kg），业主分摊按验收 " + g.report.getBagCount() + " 袋不变");
                item.setOwnerBillId(ownerBill.getId());
                // 清运结算单调整为本户拆分重量对应金额
                adjustCollectorBill(primaryCollectorBill, g.finalWeight, period,
                        "称重复核 " + r.getReviewNo() + " 拆分：本户 " + g.finalWeight + "kg", property);
                item.setCollectorBillId(primaryCollectorBill.getId());
            } else {
                // 被混装预约：此前无独立称重/账单，补齐业主分摊（押金优先抵扣）与清运结算
                Bill ownerBill = createOwnerBillForMerged(g, r, period, property);
                item.setOwnerBillId(ownerBill.getId());
                Bill collectorBill = createCollectorBill(g, r, period, companyName);
                item.setCollectorBillId(collectorBill.getId());

                // 该预约实际已由本车合并清运：置完成、释放垃圾点库存、推进袋级追溯
                g.appt.setStatus(ApptStatus.COMPLETED);
                apptRepo.save(g.appt);
                dispatchRepo.findByAppointmentId(g.appt.getId()).ifPresent(od -> {
                    if (od.getStatus() != DispatchStatus.COMPLETED) {
                        od.setStatus(DispatchStatus.COMPLETED);
                        od.setRouteInfo((od.getRouteInfo() == null ? "" : od.getRouteInfo())
                                + "（实际并入复核 " + r.getReviewNo() + " 的合并清运车次 " + w.getPlateNo() + "）");
                        dispatchRepo.save(od);
                    }
                });
                WastePoint p = pointRepo.findById(g.appt.getWastePointId()).orElseThrow();
                p.setCurrentBags(Math.max(0, p.getCurrentBags() - g.report.getBagCount()));
                pointRepo.save(p);
                traceUpdater.advance(g.appt.getId(), TraceStatus.LOADED);
                traceUpdater.advance(g.appt.getId(), TraceStatus.WEIGHED);
                traceUpdater.advance(g.appt.getId(), TraceStatus.SETTLED);
                traceLogger.record(g.appt.getId(), "WEIGHT_REVIEW", r.getId(), property, "REVIEW_MERGE_SETTLE",
                        "复核 " + r.getReviewNo() + " 认定本预约由车辆 " + w.getPlateNo()
                                + " 合并清运：拆分净重 " + g.finalWeight + "kg，业主账单 " + ownerBill.getBillNo()
                                + "，清运结算 " + collectorBill.getBillNo());
            }
            itemRepo.save(item);
        }

        BigDecimal confirmedPrimary = groups.stream()
                .filter(g -> g.appt.getId().equals(primaryAppt.getId()))
                .findFirst().orElseThrow().finalWeight;
        r.setConfirmedWeightKg(confirmedPrimary);
    }

    /** 设备异常 / 司机误填：以处置场回执重量为准核定单车重量 */
    private void confirmWeightCorrection(WeightReview r, WeighRecord w, CollectionAppointment primaryAppt,
                                         DisposalReceipt receipt, WeightConclusion conclusion, Session property) {
        if (receipt == null) {
            throw new BizException("缺少处置场回执，无法核定实际重量，不能定论为" + conclusion.getText());
        }
        BigDecimal confirmed = receipt.getReceivedWeightKg().setScale(2, RoundingMode.HALF_UP);
        r.setConfirmedWeightKg(confirmed);

        Bill collectorBill = requireCollectorBill(primaryAppt.getId());
        if (collectorBill.getStatus() == BillStatus.PAID) {
            throw new BizException("清运公司结算单 " + collectorBill.getBillNo() + " 已付款，请先由财务红冲后再复核调整");
        }
        String period = primaryAppt.getApptDate().toString().substring(0, 7);
        adjustCollectorBill(collectorBill, confirmed, period,
                "称重复核 " + r.getReviewNo() + " 认定[" + conclusion.getText() + "]，以处置场回执 "
                        + receipt.getReceiptNo() + " 重量 " + confirmed + "kg 为准（原地磅净重 "
                        + w.getNetWeightKg() + "kg）", property);

        requireOwnerBill(primaryAppt.getId()); // 业主账单按袋计费，不受设备/误填影响，仅校验存在
        WeightReviewItem item = new WeightReviewItem();
        item.setReviewId(r.getId());
        item.setAppointmentId(primaryAppt.getId());
        DailyReport report = reportRepo.findById(primaryAppt.getReportId()).orElseThrow();
        RenovationRegistration reg = regRepo.findById(primaryAppt.getRegistrationId()).orElseThrow();
        item.setRegistrationId(reg.getId());
        item.setOwnerName(reg.getOwnerName());
        item.setBuildingRoom(reg.getBuilding() + "-" + reg.getRoomNumber());
        item.setAcceptedBags(report.getBagCount());
        item.setMaterialBagWeightKg(materialBagWeight(report));
        item.setMaterialEstimateKg(materialEstimate(report));
        item.setLoadOrder(1);
        item.setAllocatedWeightKg(confirmed);
        item.setFinalWeightKg(confirmed);
        item.setAllocatedFee(collectorFee(confirmed));
        item.setCollectorBillId(collectorBill.getId());
        itemRepo.save(item);
    }

    /** 复核无异常：净重成立，归档证据 */
    private void confirmNormal(WeightReview r, WeighRecord w, CollectionAppointment primaryAppt,
                               DisposalReceipt receipt, Session property) {
        BigDecimal confirmed = receipt != null ? receipt.getReceivedWeightKg() : w.getNetWeightKg();
        r.setConfirmedWeightKg(confirmed.setScale(2, RoundingMode.HALF_UP));
        Bill collectorBill = requireCollectorBill(primaryAppt.getId());
        annotateBill(collectorBill, "称重复核 " + r.getReviewNo() + " 结论：无异常，净重 "
                + w.getNetWeightKg() + "kg 成立");
        DailyReport report = reportRepo.findById(primaryAppt.getReportId()).orElseThrow();
        RenovationRegistration reg = regRepo.findById(primaryAppt.getRegistrationId()).orElseThrow();
        WeightReviewItem item = new WeightReviewItem();
        item.setReviewId(r.getId());
        item.setAppointmentId(primaryAppt.getId());
        item.setRegistrationId(reg.getId());
        item.setOwnerName(reg.getOwnerName());
        item.setBuildingRoom(reg.getBuilding() + "-" + reg.getRoomNumber());
        item.setAcceptedBags(report.getBagCount());
        item.setMaterialBagWeightKg(materialBagWeight(report));
        item.setMaterialEstimateKg(materialEstimate(report));
        item.setLoadOrder(1);
        item.setAllocatedWeightKg(r.getConfirmedWeightKg());
        item.setFinalWeightKg(r.getConfirmedWeightKg());
        item.setAllocatedFee(collectorFee(r.getConfirmedWeightKg()));
        item.setCollectorBillId(collectorBill.getId());
        itemRepo.save(item);
    }

    // ============================ 账单联动辅助 ============================

    private BigDecimal collectorFee(BigDecimal weightKg) {
        return weightKg.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP)
                .multiply(props.getCollectorPricePerTon()).setScale(2, RoundingMode.HALF_UP);
    }

    private Bill requireCollectorBill(Long appointmentId) {
        return billRepo.findByAppointmentId(appointmentId).stream()
                .filter(b -> b.getBillType() == BillType.COLLECTOR).findFirst()
                .orElseThrow(() -> new BizException("预约 " + appointmentId + " 的清运公司结算单尚未生成，请稍后再试"));
    }

    private Bill requireOwnerBill(Long appointmentId) {
        return billRepo.findByAppointmentId(appointmentId).stream()
                .filter(b -> b.getBillType() == BillType.OWNER).findFirst()
                .orElseThrow(() -> new BizException("预约 " + appointmentId + " 的业主账单尚未生成，请稍后再试"));
    }

    private void annotateBill(Bill bill, String note) {
        bill.setDetail((bill.getDetail() == null ? "" : bill.getDetail()) + " [称重复核] " + note);
        billRepo.save(bill);
    }

    /** 调整清运结算单金额（仅未付款） */
    private void adjustCollectorBill(Bill bill, BigDecimal weightKg, String period, String note, Session property) {
        if (bill.getStatus() == BillStatus.PAID) {
            throw new BizException("清运公司结算单 " + bill.getBillNo() + " 已付款，须先由财务红冲");
        }
        BigDecimal old = bill.getAmount();
        BigDecimal fee = collectorFee(weightKg);
        bill.setPeriod(period);
        bill.setAmount(fee);
        bill.setDetail((bill.getDetail() == null ? "" : bill.getDetail())
                + " [称重复核] " + note + "；结算金额 " + old + " → " + fee + " 元");
        billRepo.save(bill);
        traceLogger.record(bill.getAppointmentId(), "BILL", bill.getId(), property, "REVIEW_BILL_ADJUST",
                "称重复核调整清运结算单 " + bill.getBillNo() + "：" + old + " → " + fee + " 元（" + note + "）");
    }

    /** 为被混装的预约补生成业主分摊账单（押金优先抵扣，不足物业垫付） */
    private Bill createOwnerBillForMerged(SplitGroup g, WeightReview r, String period, Session property) {
        int normalBags = g.report.getBagCount() - g.report.getExtraBagCount();
        BigDecimal baseFee = props.getBagUnitPrice().multiply(BigDecimal.valueOf(normalBags));
        BigDecimal extraFee = props.getBagUnitPrice().multiply(props.getExtraBagMultiplier())
                .multiply(BigDecimal.valueOf(g.report.getExtraBagCount()));
        BigDecimal total = baseFee.add(extraFee).setScale(2, RoundingMode.HALF_UP);

        Bill ownerBill = new Bill();
        ownerBill.setBillNo(NoGen.of("BILL"));
        ownerBill.setBillType(BillType.OWNER);
        ownerBill.setPartyId(g.reg.getOwnerId());
        ownerBill.setPartyName(g.reg.getOwnerName());
        ownerBill.setRegistrationId(g.reg.getId());
        ownerBill.setAppointmentId(g.appt.getId());
        ownerBill.setPeriod(period);
        ownerBill.setAmount(total);
        ownerBill.setDetail("{\"normalBags\":" + normalBags + ",\"extraBags\":" + g.report.getExtraBagCount()
                + ",\"bagUnitPrice\":" + props.getBagUnitPrice() + ",\"extraMultiplier\":"
                + props.getExtraBagMultiplier() + ",\"baseFee\":" + baseFee + ",\"extraFee\":" + extraFee
                + ",\"mergeReviewNo\":\"" + r.getReviewNo() + "\"}");

        BigDecimal deduct = g.reg.getDepositBalance().min(total);
        if (deduct.compareTo(BigDecimal.ZERO) > 0) {
            g.reg.setDepositBalance(g.reg.getDepositBalance().subtract(deduct));
            regRepo.save(g.reg);
            DepositDeduction d = new DepositDeduction();
            d.setRegistrationId(g.reg.getId());
            d.setType("FEE");
            d.setAmount(deduct);
            d.setReason("合并清运费抵扣（预约 " + g.appt.getApptNo() + "，复核 " + r.getReviewNo() + "）");
            d.setBalanceAfter(g.reg.getDepositBalance());
            deductionRepo.save(d);
        }
        BigDecimal gap = total.subtract(deduct);
        String payNote = "押金抵扣 " + deduct + " 元";
        if (gap.compareTo(BigDecimal.ZERO) > 0) {
            Bill advance = new Bill();
            advance.setBillNo(NoGen.of("BILL"));
            advance.setBillType(BillType.PROPERTY_ADVANCE);
            advance.setPartyName("物业");
            advance.setRegistrationId(g.reg.getId());
            advance.setAppointmentId(g.appt.getId());
            advance.setPeriod(period);
            advance.setAmount(gap);
            advance.setDetail("合并清运：业主押金不足，物业垫付（业主账单待补，复核 " + r.getReviewNo() + "）");
            billRepo.save(advance);
            payNote += "，物业垫付 " + gap + " 元";
        }
        ownerBill.setPaidAmount(total);
        ownerBill.setStatus(BillStatus.PAID);
        ownerBill.setPaidAt(LocalDateTime.now());
        ownerBill.setDetail(ownerBill.getDetail().replace("}", ",\"payNote\":\"" + payNote + "\"}"));
        billRepo.save(ownerBill);
        traceLogger.record(g.appt.getId(), "BILL", ownerBill.getId(), property, "REVIEW_MERGE_OWNER_BILL",
                "合并清运补生成业主账单 " + ownerBill.getBillNo() + "：" + g.reg.getOwnerName()
                        + " 验收 " + g.report.getBagCount() + " 袋，金额 " + total + " 元（" + payNote + "）");
        return ownerBill;
    }

    /** 为被混装的预约补生成清运公司结算拆分单 */
    private Bill createCollectorBill(SplitGroup g, WeightReview r, String period, String companyName) {
        BigDecimal fee = collectorFee(g.finalWeight);
        Bill bill = new Bill();
        bill.setBillNo(NoGen.of("BILL"));
        bill.setBillType(BillType.COLLECTOR);
        bill.setPartyName(companyName);
        bill.setRegistrationId(g.reg.getId());
        bill.setAppointmentId(g.appt.getId());
        bill.setPeriod(period);
        bill.setAmount(fee);
        BigDecimal tons = g.finalWeight.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        bill.setDetail("{\"mergeReviewNo\":\"" + r.getReviewNo() + "\",\"netWeightKg\":" + g.finalWeight
                + ",\"tons\":" + tons + ",\"pricePerTon\":" + props.getCollectorPricePerTon()
                + ",\"loadOrder\":" + g.loadOrder + ",\"plateNo\":\"" + r.getPlateNo() + "\"}");
        billRepo.save(bill);
        traceLogger.record(g.appt.getId(), "BILL", bill.getId(), null, "REVIEW_MERGE_COLLECTOR_BILL",
                "合并清运拆分清运结算单 " + bill.getBillNo() + "：" + g.finalWeight + "kg（第 "
                        + g.loadOrder + " 装车位）→ " + fee + " 元");
        return bill;
    }

    private SplitGroup buildGroup(CollectionAppointment appt, DailyReport report, int loadOrder) {
        SplitGroup g = new SplitGroup();
        g.appt = appt;
        g.report = report;
        g.reg = regRepo.findById(appt.getRegistrationId()).orElseThrow();
        g.perBag = materialBagWeight(report);
        g.materialEstimate = materialEstimate(report);
        g.loadOrder = loadOrder;
        return g;
    }

    private static class SplitGroup {
        CollectionAppointment appt;
        DailyReport report;
        RenovationRegistration reg;
        BigDecimal perBag;
        BigDecimal materialEstimate;
        int loadOrder;
        BigDecimal allocated;
        BigDecimal finalWeight;
    }

    // ============================ 结案归档 ============================

    /** 物业结案归档：费用拆分与结算调整全部落地后归档（不改变任何金额/权限） */
    @Transactional
    public WeightReview close(Session property, Long reviewId, String note) {
        WeightReview r = reviewRepo.findById(reviewId)
                .orElseThrow(() -> new BizException(404, "复核单不存在"));
        if (r.getStatus() != ReviewStatus.CONFIRMED) {
            throw new BizException("仅已定论的复核单可以结案归档");
        }
        r.setStatus(ReviewStatus.CLOSED);
        if (note != null && !note.isBlank()) {
            r.setConclusionNote((r.getConclusionNote() == null ? "" : r.getConclusionNote())
                    + " [结案] " + note);
        }
        reviewRepo.save(r);
        traceLogger.record(r.getAppointmentId(), "WEIGHT_REVIEW", r.getId(), property, "REVIEW_CLOSE",
                "称重差异复核结案归档 " + r.getReviewNo() + (note != null ? "：" + note : ""));
        return r;
    }

    // ============================ 查询 ============================

    @Transactional(readOnly = true)
    public List<WeightReview> list(ReviewStatus status) {
        return status != null ? reviewRepo.findByStatusOrderByIdDesc(status) : reviewRepo.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public WeightReview getEntity(Long id) {
        return reviewRepo.findById(id).orElseThrow(() -> new BizException(404, "复核单不存在"));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        WeightReview r = getEntity(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("review", r);
        result.put("items", itemRepo.findByReviewIdOrderByLoadOrderAsc(id));
        result.put("evidence", readEvidence(r));
        return result;
    }

    private Map<String, Object> readEvidence(WeightReview r) {
        try {
            return objectMapper.readValue(r.getEvidenceSummary(), MAP_TYPE);
        } catch (Exception e) {
            log.warn("复核单 {} 证据 JSON 解析失败: {}", r.getReviewNo(), e.getMessage());
            return Map.of("raw", r.getEvidenceSummary() == null ? "" : r.getEvidenceSummary());
        }
    }
}
