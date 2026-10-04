package com.waste.service;

import com.waste.auth.Session;
import com.waste.common.BizException;
import com.waste.common.NoGen;
import com.waste.common.TraceLogger;
import com.waste.config.RabbitConfig;
import com.waste.config.WasteProperties;
import com.waste.entity.*;
import com.waste.enums.*;
import com.waste.mq.EventBus;
import com.waste.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WeighService {

    private final WeighRecordRepository weighRepo;
    private final DispatchOrderRepository dispatchRepo;
    private final CollectionAppointmentRepository apptRepo;
    private final DailyReportRepository reportRepo;
    private final WastePointRepository wastePointRepo;
    private final ComplaintRepository complaintRepo;
    private final WasteProperties props;
    private final EventBus eventBus;
    private final TraceLogger traceLogger;
    private final TraceUpdater traceUpdater;
    private final WeighReviewService weighReviewService;

    /**
     * 车辆进出场称重：记录空车/装载重量、现场照片、清运联单；
     * 净重与按袋预估重量偏差超过阈值时标记称重异常并自动生成投诉。
     */
    @Transactional
    public WeighRecord create(Session collector, Long dispatchId, BigDecimal emptyWeight, BigDecimal loadedWeight,
                              LocalDateTime entryTime, LocalDateTime exitTime, String photos, String manifestNo) {
        DispatchOrder d = dispatchRepo.findById(dispatchId)
                .orElseThrow(() -> new BizException(404, "派单不存在"));
        if (d.getStatus() == DispatchStatus.COMPLETED) {
            throw new BizException("该派单已完成称重，不能重复提交");
        }
        weighRepo.findByDispatchId(dispatchId).ifPresent(w -> {
            throw new BizException("该派单已存在称重记录（联单号 " + w.getManifestNo() + "）");
        });
        if (emptyWeight == null || loadedWeight == null
                || emptyWeight.compareTo(BigDecimal.ZERO) <= 0
                || loadedWeight.compareTo(emptyWeight) <= 0) {
            throw new BizException("重量数据不合法：装载重量必须大于空车重量且均大于 0");
        }
        if (entryTime == null || exitTime == null || exitTime.isBefore(entryTime)) {
            throw new BizException("进出场时间不合法");
        }

        // 未做到场登记的派单，以进场时间自动补登记并判定迟到
        if (d.getStatus() == DispatchStatus.DISPATCHED) {
            d.setActualArrival(entryTime);
            d.setLateFlag(entryTime.isAfter(d.getPlannedArrival().plusMinutes(props.getLateToleranceMinutes())));
            d.setStatus(DispatchStatus.ARRIVED);
        }
        traceUpdater.advance(d.getAppointmentId(), TraceStatus.LOADED);

        CollectionAppointment appt = apptRepo.findById(d.getAppointmentId()).orElseThrow();
        DailyReport report = reportRepo.findById(appt.getReportId()).orElseThrow();

        BigDecimal net = loadedWeight.subtract(emptyWeight);
        BigDecimal est = props.getAvgBagWeightKg().multiply(BigDecimal.valueOf(report.getBagCount()));
        BigDecimal deviation = est.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : net.subtract(est).abs().multiply(BigDecimal.valueOf(100))
                .divide(est, 2, RoundingMode.HALF_UP);
        boolean anomaly = deviation.compareTo(props.getWeighAnomalyThresholdPct()) > 0;

        WeighRecord w = new WeighRecord();
        w.setDispatchId(dispatchId);
        w.setAppointmentId(appt.getId());
        w.setPlateNo(d.getPlateNo());
        w.setDriverName(d.getDriverName());
        w.setEmptyWeightKg(emptyWeight);
        w.setLoadedWeightKg(loadedWeight);
        w.setNetWeightKg(net);
        w.setEstimatedWeightKg(est);
        w.setDeviationPct(deviation);
        w.setAnomalyFlag(anomaly);
        w.setAnomalyReason(anomaly ? "净重 " + net + "kg 与按袋预估 " + est + "kg 偏差 " + deviation
                + "%，超过阈值 " + props.getWeighAnomalyThresholdPct() + "%" : null);
        w.setEntryTime(entryTime);
        w.setExitTime(exitTime);
        w.setPhotos(photos);
        w.setManifestNo(manifestNo != null && !manifestNo.isBlank() ? manifestNo : NoGen.of("MF"));
        weighRepo.save(w);

        d.setStatus(DispatchStatus.COMPLETED);
        dispatchRepo.save(d);
        appt.setStatus(ApptStatus.COMPLETED);
        apptRepo.save(appt);

        // 垃圾点库存释放
        WastePoint point = wastePointRepo.findById(appt.getWastePointId()).orElseThrow();
        point.setCurrentBags(Math.max(0, point.getCurrentBags() - report.getBagCount()));
        wastePointRepo.save(point);

        traceUpdater.advance(appt.getId(), TraceStatus.WEIGHED);

        traceLogger.record(appt.getId(), "WEIGH", w.getId(), collector, "WEIGH_IN_OUT",
                "进出场称重: 车牌=" + d.getPlateNo() + " 司机=" + d.getDriverName() + " 空车=" + emptyWeight
                        + "kg 装载=" + loadedWeight + "kg 净重=" + net + "kg 联单=" + w.getManifestNo()
                        + (anomaly ? " 【称重异常】" + w.getAnomalyReason() : ""));

        if (anomaly) {
            Complaint c = new Complaint();
            c.setComplaintNo(NoGen.of("CPL"));
            c.setComplainantId(0L);
            c.setComplainantName("SYSTEM");
            c.setType(ComplaintType.WEIGH_ANOMALY);
            c.setTargetAppointmentId(appt.getId());
            c.setTargetRegistrationId(appt.getRegistrationId());
            c.setDescription("称重数据异常: " + w.getAnomalyReason() + "，联单号 " + w.getManifestNo());
            complaintRepo.save(c);
            traceLogger.record(appt.getId(), "COMPLAINT", c.getId(), null, "AUTO_COMPLAINT_WEIGH",
                    c.getDescription());

            // 自动立称重复核案（证据可随后补传，物业确认结论）
            weighReviewService.openForAnomaly(w, collector);
        }

        // 触发计费（MQ 异步 / 降级同步）
        eventBus.publish(RabbitConfig.RK_WEIGH_RECORDED, w.getId());
        return w;
    }

    public List<WeighRecord> list() {
        return weighRepo.findAll();
    }

    public WeighRecord get(Long id) {
        return weighRepo.findById(id).orElseThrow(() -> new BizException(404, "称重记录不存在"));
    }
}
