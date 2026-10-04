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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DispatchService {

    private final DispatchOrderRepository dispatchRepo;
    private final CollectionAppointmentRepository apptRepo;
    private final DailyReportRepository reportRepo;
    private final VehicleRepository vehicleRepo;
    private final WastePointRepository wastePointRepo;
    private final ComplaintRepository complaintRepo;
    private final WasteProperties props;
    private final TraceLogger traceLogger;
    private final TraceUpdater traceUpdater;

    /**
     * 清运公司派单：校验车辆限行、核载容量、同时段已有派单，生成小区门禁放行单号。
     */
    @Transactional
    public DispatchOrder create(Session collector, Long appointmentId, Long vehicleId,
                                LocalDateTime plannedArrival, String routeInfo) {
        CollectionAppointment appt = apptRepo.findById(appointmentId)
                .orElseThrow(() -> new BizException(404, "预约不存在"));
        if (appt.getStatus() != ApptStatus.APPROVED) {
            throw new BizException("预约状态为 " + appt.getStatus() + "，不能派单");
        }
        dispatchRepo.findByAppointmentId(appointmentId).ifPresent(d -> {
            throw new BizException("该预约已派单（" + d.getDispatchNo() + "），不能重复派单");
        });
        Vehicle vehicle = vehicleRepo.findById(vehicleId)
                .orElseThrow(() -> new BizException(404, "车辆不存在"));

        // 称重复核认定合并清运/司机误填后冻结的车辆，物业解冻前不得派单
        if (Boolean.TRUE.equals(vehicle.getDispatchSuspended())) {
            throw new BizException("车辆 " + vehicle.getPlateNo() + " 因称重复核问题已被暂停派单权限（"
                    + vehicle.getSuspendReason() + "），须经物业核查解冻后方可派单");
        }

        // 车辆限行校验（按预约日期尾号限行）
        checkPlateRestriction(vehicle.getPlateNo(), appt.getApptDate());

        // 车辆核载校验：预估重量不能超过核载
        DailyReport report = reportRepo.findById(appt.getReportId()).orElseThrow();
        BigDecimal estWeightKg = props.getAvgBagWeightKg().multiply(BigDecimal.valueOf(report.getBagCount()));
        BigDecimal capacityKg = vehicle.getCapacityTons().multiply(BigDecimal.valueOf(1000));
        if (estWeightKg.compareTo(capacityKg) > 0) {
            throw new BizException("车辆核载不足：预估垃圾 " + estWeightKg + "kg，超过 "
                    + vehicle.getPlateNo() + " 核载 " + capacityKg + "kg，请更换更大车辆");
        }

        // 已有派单冲突：同一车辆计划到场时间 ±2 小时内不允许重复派单
        List<DispatchOrder> conflicts = dispatchRepo.findByVehicleIdAndPlannedArrivalBetween(
                vehicleId, plannedArrival.minusHours(2), plannedArrival.plusHours(2));
        if (!conflicts.isEmpty()) {
            throw new BizException("车辆 " + vehicle.getPlateNo() + " 在该时段前后 2 小时内已有派单（"
                    + conflicts.get(0).getDispatchNo() + "），请调整时间或更换车辆");
        }

        DispatchOrder d = new DispatchOrder();
        d.setDispatchNo(NoGen.of("DSP"));
        d.setAppointmentId(appointmentId);
        d.setVehicleId(vehicleId);
        d.setPlateNo(vehicle.getPlateNo());
        d.setDriverName(vehicle.getDriverName());
        d.setCompanyName(vehicle.getCompanyName());
        d.setGatePassNo(NoGen.of("GP"));
        d.setPlannedArrival(plannedArrival);
        d.setRouteInfo(routeInfo);
        dispatchRepo.save(d);

        appt.setStatus(ApptStatus.DISPATCHED);
        apptRepo.save(appt);
        traceUpdater.advance(appointmentId, TraceStatus.DISPATCHED);

        traceLogger.record(appointmentId, "DISPATCH", d.getId(), collector, "DISPATCH_CREATE",
                "派单 " + d.getDispatchNo() + ": 车辆=" + vehicle.getPlateNo() + " 司机=" + vehicle.getDriverName()
                        + " 计划到场=" + plannedArrival + " 门禁放行单=" + d.getGatePassNo()
                        + (routeInfo != null ? " 路线=" + routeInfo : ""));
        return d;
    }

    /** 尾号限行：周一 1/6，周二 2/7，周三 3/8，周四 4/9，周五 5/0，周末不限行 */
    private void checkPlateRestriction(String plateNo, LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return;
        }
        char last = plateNo.charAt(plateNo.length() - 1);
        if (!Character.isDigit(last)) {
            return;
        }
        int digit = last - '0';
        Set<Integer> restricted = switch (dow) {
            case MONDAY -> Set.of(1, 6);
            case TUESDAY -> Set.of(2, 7);
            case WEDNESDAY -> Set.of(3, 8);
            case THURSDAY -> Set.of(4, 9);
            case FRIDAY -> Set.of(5, 0);
            default -> Set.of();
        };
        if (restricted.contains(digit)) {
            throw new BizException("车辆限行：车牌尾号 " + digit + " 在 " + date + "（" + dow + "）限行，请更换车辆或改期");
        }
    }

    /** 司机到场登记：记录实际到场时间，判定迟到；迟到且垃圾点已超容量时自动生成溢出投诉 */
    @Transactional
    public DispatchOrder arrive(Session collector, Long dispatchId, LocalDateTime actualArrival) {
        DispatchOrder d = dispatchRepo.findById(dispatchId)
                .orElseThrow(() -> new BizException(404, "派单不存在"));
        if (d.getStatus() != DispatchStatus.DISPATCHED) {
            throw new BizException("派单状态为 " + d.getStatus() + "，不能重复到场登记");
        }
        LocalDateTime arrival = actualArrival != null ? actualArrival : LocalDateTime.now();
        d.setActualArrival(arrival);
        boolean late = arrival.isAfter(d.getPlannedArrival().plusMinutes(props.getLateToleranceMinutes()));
        d.setLateFlag(late);
        d.setStatus(DispatchStatus.ARRIVED);
        dispatchRepo.save(d);
        traceUpdater.advance(d.getAppointmentId(), TraceStatus.LOADED);

        CollectionAppointment appt = apptRepo.findById(d.getAppointmentId()).orElseThrow();
        traceLogger.record(d.getAppointmentId(), "DISPATCH", d.getId(), collector, "DISPATCH_ARRIVE",
                "司机到场: 实际=" + arrival + " 计划=" + d.getPlannedArrival() + (late ? "（迟到）" : "（准时）"));

        if (late) {
            WastePoint point = wastePointRepo.findById(appt.getWastePointId()).orElseThrow();
            if (point.getCurrentBags() > point.getCapacityBags()) {
                Complaint c = new Complaint();
                c.setComplaintNo(NoGen.of("CPL"));
                c.setComplainantId(0L);
                c.setComplainantName("SYSTEM");
                c.setType(ComplaintType.OVERFLOW);
                c.setTargetAppointmentId(appt.getId());
                c.setTargetRegistrationId(appt.getRegistrationId());
                c.setDescription("车辆迟到（计划 " + d.getPlannedArrival() + "，实际 " + arrival + "），垃圾点["
                        + point.getName() + "]已溢出：当前 " + point.getCurrentBags()
                        + " 袋 / 容量 " + point.getCapacityBags() + " 袋");
                complaintRepo.save(c);
                traceLogger.record(appt.getId(), "COMPLAINT", c.getId(), null, "AUTO_COMPLAINT_OVERFLOW",
                        c.getDescription());
            }
        }
        return d;
    }

    public List<DispatchOrder> list(Session session) {
        if (session.role() == Role.COLLECTOR && session.teamName() != null) {
            return dispatchRepo.findByCompanyNameOrderByIdDesc(session.teamName());
        }
        return dispatchRepo.findAll();
    }

    public DispatchOrder get(Long id) {
        return dispatchRepo.findById(id).orElseThrow(() -> new BizException(404, "派单不存在"));
    }
}
