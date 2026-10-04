package com.waste.service;

import com.waste.auth.Session;
import com.waste.entity.*;
import com.waste.enums.*;
import com.waste.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final RenovationRegistrationRepository regRepo;
    private final CollectionAppointmentRepository apptRepo;
    private final DailyReportRepository reportRepo;
    private final ViolationRepository violationRepo;
    private final DepositDeductionRepository deductionRepo;
    private final DispatchOrderRepository dispatchRepo;
    private final WeighRecordRepository weighRepo;
    private final VehicleRepository vehicleRepo;
    private final BillRepository billRepo;
    private final WastePointRepository wastePointRepo;
    private final WeightReviewRepository weightReviewRepo;

    /** 物业端：各楼栋预约、超时未清、违规堆放、押金扣款、清运公司履约 */
    public Map<String, Object> property() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 各楼栋预约情况
        Map<String, List<RenovationRegistration>> byBuilding = regRepo.findAll().stream()
                .collect(Collectors.groupingBy(RenovationRegistration::getBuilding));
        List<Map<String, Object>> buildings = new ArrayList<>();
        List<CollectionAppointment> allAppts = apptRepo.findAll();
        for (var entry : byBuilding.entrySet()) {
            List<Long> regIds = entry.getValue().stream().map(RenovationRegistration::getId).toList();
            List<CollectionAppointment> appts = allAppts.stream()
                    .filter(a -> regIds.contains(a.getRegistrationId())).toList();
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("building", entry.getKey());
            b.put("activeRegistrations", entry.getValue().stream()
                    .filter(r -> r.getStatus() == RegStatus.ACTIVE).count());
            b.put("approvedAppointments", appts.stream().filter(a -> a.getStatus() == ApptStatus.APPROVED).count());
            b.put("dispatchedAppointments", appts.stream().filter(a -> a.getStatus() == ApptStatus.DISPATCHED).count());
            b.put("completedAppointments", appts.stream().filter(a -> a.getStatus() == ApptStatus.COMPLETED).count());
            b.put("capacityRejected", appts.stream().filter(a -> a.getStatus() == ApptStatus.REJECTED_CAPACITY).count());
            buildings.add(b);
        }
        result.put("buildings", buildings);

        // 超时未清：预约日期已过但仍未完成的预约
        List<Map<String, Object>> overdue = apptRepo
                .findByApptDateBeforeAndStatusIn(LocalDate.now(),
                        List.of(ApptStatus.APPROVED, ApptStatus.DISPATCHED))
                .stream().map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("appointmentId", a.getId());
                    m.put("apptNo", a.getApptNo());
                    m.put("apptDate", a.getApptDate());
                    m.put("status", a.getStatus());
                    regRepo.findById(a.getRegistrationId()).ifPresent(r -> {
                        m.put("building", r.getBuilding());
                        m.put("roomNumber", r.getRoomNumber());
                        m.put("constructionTeam", r.getConstructionTeam());
                    });
                    return m;
                }).toList();
        result.put("overdueAppointments", overdue);

        // 违规堆放与押金扣款
        List<Violation> violations = violationRepo.findAll();
        result.put("violations", violations);
        result.put("violationTotalDeducted", violations.stream()
                .filter(v -> v.getStatus() == ViolationStatus.DEDUCTED)
                .map(Violation::getDeductionAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        result.put("recentDepositDeductions", deductionRepo.findAll().stream()
                .sorted(Comparator.comparing(DepositDeduction::getId).reversed()).limit(20).toList());

        // 垃圾点容量
        result.put("wastePoints", wastePointRepo.findAll());

        // 清运公司履约：准时率、异常率
        result.put("collectorPerformance", collectorPerformance());

        // 称重差异复核：待复核单数、被暂停派单的车辆
        List<WeightReview> reviews = weightReviewRepo.findAllByOrderByIdDesc();
        result.put("weightReviews", reviews);
        result.put("pendingWeightReviews", reviews.stream()
                .filter(r -> r.getStatus() == ReviewStatus.PENDING).count());
        result.put("mergedCollectionReviews", reviews.stream()
                .filter(r -> r.getConclusion() == WeightConclusion.MERGED_COLLECTION).count());
        result.put("blockedVehicles", vehicleRepo.findAll().stream()
                .filter(v -> Boolean.TRUE.equals(v.getDispatchBlocked())).toList());
        return result;
    }

    private List<Map<String, Object>> collectorPerformance() {
        Map<String, List<DispatchOrder>> byCompany = dispatchRepo.findAll().stream()
                .collect(Collectors.groupingBy(DispatchOrder::getCompanyName));
        List<WeighRecord> weighs = weighRepo.findAll();
        List<Map<String, Object>> perf = new ArrayList<>();
        for (var entry : byCompany.entrySet()) {
            List<DispatchOrder> ds = entry.getValue();
            long arrived = ds.stream().filter(d -> d.getActualArrival() != null).count();
            long late = ds.stream().filter(d -> Boolean.TRUE.equals(d.getLateFlag())).count();
            Set<Long> dispatchIds = ds.stream().map(DispatchOrder::getId).collect(Collectors.toSet());
            long anomalies = weighs.stream()
                    .filter(w -> dispatchIds.contains(w.getDispatchId()) && Boolean.TRUE.equals(w.getAnomalyFlag()))
                    .count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("company", entry.getKey());
            m.put("totalDispatches", ds.size());
            m.put("arrived", arrived);
            m.put("late", late);
            m.put("onTimeRate", arrived == 0 ? 1.0
                    : BigDecimal.valueOf(arrived - late).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(arrived), 1, RoundingMode.HALF_UP).doubleValue() / 100.0);
            m.put("weighAnomalies", anomalies);
            perf.add(m);
        }
        return perf;
    }

    /** 清运公司端：派单路线、车辆容量、称重回传、司机到场 */
    public Map<String, Object> collector(Session session) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<DispatchOrder> dispatches = session.teamName() != null
                ? dispatchRepo.findByCompanyNameOrderByIdDesc(session.teamName())
                : dispatchRepo.findAll();
        result.put("dispatches", dispatches);

        // 车辆容量与当日已分配预估载重
        List<Map<String, Object>> vehicles = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Vehicle v : vehicleRepo.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vehicle", v);
            List<DispatchOrder> todayOrders = dispatches.stream()
                    .filter(d -> d.getVehicleId().equals(v.getId())
                            && d.getPlannedArrival().toLocalDate().equals(today)).toList();
            m.put("todayDispatchCount", todayOrders.size());
            m.put("todayRoutes", todayOrders.stream().map(d -> d.getRouteInfo() == null ? "" : d.getRouteInfo()).toList());
            vehicles.add(m);
        }
        result.put("vehicles", vehicles);

        // 待称重回传（已派单/已到场但未完成）
        result.put("pendingWeigh", dispatches.stream()
                .filter(d -> d.getStatus() != DispatchStatus.COMPLETED).toList());

        // 司机到场记录
        result.put("recentArrivals", dispatches.stream()
                .filter(d -> d.getActualArrival() != null)
                .sorted(Comparator.comparing(DispatchOrder::getActualArrival).reversed()).limit(20).toList());

        // 最近称重回传
        result.put("recentWeighRecords", weighRepo.findAll().stream()
                .sorted(Comparator.comparing(WeighRecord::getId).reversed()).limit(20).toList());
        return result;
    }

    /** 财务端：按业主/施工队/物业垫付/清运公司四类汇总账单 */
    public Map<String, Object> finance(String period) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Bill> bills = period != null && !period.isBlank()
                ? billRepo.findByPeriodOrderByIdDesc(period)
                : billRepo.findAll();
        Map<String, Object> byType = new LinkedHashMap<>();
        for (BillType type : BillType.values()) {
            List<Bill> ofType = bills.stream().filter(b -> b.getBillType() == type).toList();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", ofType.size());
            m.put("totalAmount", ofType.stream().map(Bill::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
            m.put("paidAmount", ofType.stream().filter(b -> b.getStatus() == BillStatus.PAID)
                    .map(Bill::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
            m.put("unpaidAmount", ofType.stream().filter(b -> b.getStatus() != BillStatus.PAID)
                    .map(Bill::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
            byType.put(type.name(), m);
        }
        result.put("period", period == null ? "ALL" : period);
        result.put("summaryByType", byType);
        result.put("disputedBills", bills.stream().filter(b -> b.getStatus() == BillStatus.DISPUTED).toList());
        result.put("recentBills", bills.stream()
                .sorted(Comparator.comparing(Bill::getId).reversed()).limit(50).toList());
        return result;
    }
}
