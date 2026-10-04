package com.waste.service;

import com.waste.auth.Session;
import com.waste.common.BizException;
import com.waste.entity.*;
import com.waste.enums.Role;
import com.waste.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全链路追溯：同一批袋装垃圾从预约、验收、装车、称重、处置场回执到费用结算的完整记录，
 * 投诉时可还原谁堆放、谁审核、谁清走、费用如何产生。
 */
@Service
@RequiredArgsConstructor
public class TraceService {

    private final CollectionAppointmentRepository apptRepo;
    private final DailyReportRepository reportRepo;
    private final RenovationRegistrationRepository regRepo;
    private final WastePointRepository wastePointRepo;
    private final DispatchOrderRepository dispatchRepo;
    private final WeighRecordRepository weighRepo;
    private final DisposalReceiptRepository receiptRepo;
    private final BagTraceRepository bagTraceRepo;
    private final BillRepository billRepo;
    private final ViolationRepository violationRepo;
    private final DepositDeductionRepository deductionRepo;
    private final ComplaintRepository complaintRepo;
    private final TraceEventRepository traceEventRepo;
    private final WeighReviewRepository weighReviewRepo;
    private final WeighReviewShareRepository reviewShareRepo;

    public Map<String, Object> traceByAppointment(Session session, Long appointmentId) {
        CollectionAppointment appt = apptRepo.findById(appointmentId)
                .orElseThrow(() -> new BizException(404, "预约不存在"));
        RenovationRegistration reg = regRepo.findById(appt.getRegistrationId()).orElseThrow();
        checkAccess(session, reg);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registration", reg);
        reportRepo.findById(appt.getReportId()).ifPresent(r -> result.put("dailyReport", r));
        result.put("appointment", appt);
        wastePointRepo.findById(appt.getWastePointId()).ifPresent(p -> result.put("wastePoint", p));
        dispatchRepo.findByAppointmentId(appointmentId).ifPresent(d -> result.put("dispatch", d));
        result.put("weighRecords", weighRepo.findByAppointmentId(appointmentId));
        receiptRepo.findByAppointmentId(appointmentId).ifPresent(r -> result.put("disposalReceipt", r));
        bagTraceRepo.findByAppointmentId(appointmentId).ifPresent(t -> result.put("bagTrace", t));
        result.put("bills", billRepo.findByAppointmentId(appointmentId));
        List<Violation> violations = violationRepo.findByRegistrationIdOrderByIdDesc(reg.getId());
        result.put("violations", violations);
        result.put("complaints", complaintRepo.findByTargetAppointmentId(appointmentId));
        result.put("depositDeductions", deductionRepo.findByRegistrationIdOrderByIdDesc(reg.getId()));

        // 称重差异复核：本户触发的复核单；本户作为被合并户的拆分明细（并带出整单）
        List<com.waste.entity.WeighReview> reviews =
                new ArrayList<>(weighReviewRepo.findByAppointmentId(appointmentId));
        result.put("weighReviews", reviews);
        List<com.waste.entity.WeighReviewShare> myShares = reviewShareRepo.findByAppointmentId(appointmentId);
        result.put("weighReviewShares", myShares);
        for (com.waste.entity.WeighReviewShare s : myShares) {
            if (reviews.stream().noneMatch(r -> r.getId().equals(s.getReviewId()))) {
                weighReviewRepo.findById(s.getReviewId()).ifPresent(reviews::add);
            }
        }

        // 事件流：预约链路事件 + 上报/登记/违规等产生时尚未关联预约的事件
        List<TraceEvent> events = new ArrayList<>(traceEventRepo.findByAppointmentIdOrderByIdAsc(appointmentId));
        events.addAll(traceEventRepo.findByEntityTypeAndEntityIdOrderByIdAsc("REGISTRATION", reg.getId()));
        events.addAll(traceEventRepo.findByEntityTypeAndEntityIdOrderByIdAsc("REPORT", appt.getReportId()));
        for (Violation v : violations) {
            events.addAll(traceEventRepo.findByEntityTypeAndEntityIdOrderByIdAsc("VIOLATION", v.getId()));
        }
        events.sort(Comparator.comparing(TraceEvent::getId));
        result.put("events", events);
        return result;
    }

    public Map<String, Object> traceByCode(Session session, String traceCode) {
        BagTrace trace = bagTraceRepo.findByTraceCode(traceCode)
                .orElseThrow(() -> new BizException(404, "追溯码不存在"));
        return traceByAppointment(session, trace.getAppointmentId());
    }

    private void checkAccess(Session session, RenovationRegistration reg) {
        if (session.role() == Role.OWNER && !reg.getOwnerId().equals(session.userId())) {
            throw new BizException(403, "只能追溯本人房产的清运记录");
        }
        if (session.role() == Role.WORKER && session.teamName() != null
                && !session.teamName().equals(reg.getConstructionTeam())) {
            throw new BizException(403, "只能追溯本施工队的清运记录");
        }
    }
}
