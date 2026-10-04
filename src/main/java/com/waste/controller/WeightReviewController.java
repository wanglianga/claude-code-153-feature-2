package com.waste.controller;

import com.waste.auth.AuthService;
import com.waste.auth.Session;
import com.waste.common.ApiResponse;
import com.waste.common.BizException;
import com.waste.entity.Vehicle;
import com.waste.entity.VehicleStop;
import com.waste.entity.WeightReview;
import com.waste.enums.ReviewStatus;
import com.waste.enums.Role;
import com.waste.enums.WeightConclusion;
import com.waste.repository.VehicleRepository;
import com.waste.repository.VehicleStopRepository;
import com.waste.service.WeightReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Tag(name = "称重差异复核",
        description = "清运净重明显高于现场验收袋数时，比对装车照片/车辆路线/司机停留点/处置场回执/同日其他预约，"
                + "判定合并清运、称重设备异常或司机误填；合并清运按袋数+材质估算+装车顺序拆分费用并支持物业人工调整")
@RestController
@RequestMapping("/api/weight-reviews")
@RequiredArgsConstructor
public class WeightReviewController {

    private final WeightReviewService reviewService;
    private final VehicleRepository vehicleRepo;
    private final VehicleStopRepository stopRepo;

    public record CreateRequest(
            @NotNull Long weighRecordId,
            /** 物业手工指认的疑似被混装预约 ID（可空，系统也会按停留点自动匹配） */
            List<Long> suspectAppointmentIds,
            String note) {
    }

    public record ReanalyzeRequest(
            /** 重新指认疑似被混装预约 ID（不传则沿用原手工指认，系统同时重新自动匹配停留点） */
            List<Long> suspectAppointmentIds,
            String note) {
    }

    public record AdjustmentRequest(
            @NotNull Long appointmentId,
            @NotNull BigDecimal finalWeightKg,
            @NotBlank String reason) {
    }

    public record ConfirmRequest(
            @NotNull WeightConclusion conclusion,
            String note,
            /** 是否暂停涉事车辆后续派单权限 */
            boolean blockVehicle,
            /** MERGED_COLLECTION 时物业对系统拆分重量的人工调整（必须填理由） */
            List<AdjustmentRequest> adjustments) {
    }

    public record StopRequest(
            @NotBlank String plateNo,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime stopTime,
            @NotBlank String location,
            @NotNull @Positive Integer durationMinutes,
            String source,
            String note) {
    }

    @Operation(summary = "发起称重差异复核（物业；自动汇总五类证据并给出判定建议）")
    @PostMapping
    public ApiResponse<WeightReview> create(@RequestBody @Valid CreateRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(reviewService.create(property, req.weighRecordId(),
                req.suspectAppointmentIds(), req.note()));
    }

    @Operation(summary = "补证后重新分析（物业补录停留点/回执补传后刷新证据与判定建议，仅限待定论复核单）")
    @PutMapping("/{id}/reanalyze")
    public ApiResponse<WeightReview> reanalyze(@PathVariable Long id, @RequestBody(required = false) ReanalyzeRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        List<Long> ids = req == null ? null : req.suspectAppointmentIds();
        String note = req == null ? null : req.note();
        return ApiResponse.ok(reviewService.reanalyze(property, id, ids, note));
    }

    @Operation(summary = "提交复核结论并执行费用拆分/结算调整/车辆派单限制（物业）")
    @PutMapping("/{id}/confirm")
    public ApiResponse<WeightReview> confirm(@PathVariable Long id, @RequestBody @Valid ConfirmRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        List<WeightReviewService.AdjustmentReq> adjustments = req.adjustments() == null ? List.of()
                : req.adjustments().stream()
                .map(a -> new WeightReviewService.AdjustmentReq(a.appointmentId(), a.finalWeightKg(), a.reason()))
                .toList();
        return ApiResponse.ok(reviewService.confirm(property, id, req.conclusion(), req.note(),
                req.blockVehicle(), adjustments));
    }

    @Operation(summary = "结案归档（费用拆分与结算调整落地后归档，不改变金额/权限）")
    @PostMapping("/{id}/close")
    public ApiResponse<WeightReview> close(@PathVariable Long id,
                                           @RequestBody(required = false) Map<String, String> body) {
        Session property = AuthService.require(Role.PROPERTY);
        String note = body == null ? null : body.get("note");
        return ApiResponse.ok(reviewService.close(property, id, note));
    }

    @Operation(summary = "复核单列表（可按状态过滤）")
    @GetMapping
    public ApiResponse<List<WeightReview>> list(@RequestParam(required = false) ReviewStatus status) {
        AuthService.require(Role.PROPERTY, Role.FINANCE, Role.COLLECTOR);
        return ApiResponse.ok(reviewService.list(status));
    }

    @Operation(summary = "复核单详情（含证据汇总与多户拆分明细）")
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        AuthService.require(Role.PROPERTY, Role.FINANCE, Role.COLLECTOR);
        return ApiResponse.ok(reviewService.detail(id));
    }

    @Operation(summary = "补录司机停留点（清运公司回传 GPS 失败时由物业/调度人工补录，参与复核取证）")
    @PostMapping("/stops")
    public ApiResponse<VehicleStop> addStop(@RequestBody @Valid StopRequest req) {
        AuthService.require(Role.COLLECTOR, Role.PROPERTY);
        VehicleStop stop = new VehicleStop();
        stop.setPlateNo(req.plateNo());
        stop.setStopTime(req.stopTime());
        stop.setLocation(req.location());
        stop.setDurationMinutes(req.durationMinutes());
        stop.setSource(req.source() == null || req.source().isBlank() ? "MANUAL" : req.source());
        stop.setNote(req.note());
        return ApiResponse.ok(stopRepo.save(stop));
    }

    @Operation(summary = "查询车辆停留点轨迹（默认返回该车全部记录，可按车牌过滤）")
    @GetMapping("/stops")
    public ApiResponse<List<VehicleStop>> stops(@RequestParam(required = false) String plateNo) {
        AuthService.require(Role.PROPERTY, Role.COLLECTOR, Role.FINANCE);
        return ApiResponse.ok(plateNo == null || plateNo.isBlank()
                ? stopRepo.findAll() : stopRepo.findByPlateNoOrderByStopTimeDesc(plateNo));
    }

    @Operation(summary = "解除车辆派单限制（物业复核处理完毕后恢复车辆派单权限）")
    @PostMapping("/vehicles/{vehicleId}/unblock")
    public ApiResponse<Vehicle> unblock(@PathVariable Long vehicleId,
                                        @RequestBody(required = false) Map<String, String> body) {
        Session property = AuthService.require(Role.PROPERTY);
        Vehicle v = vehicleRepo.findById(vehicleId).orElseThrow(() -> new BizException(404, "车辆不存在"));
        v.setDispatchBlocked(false);
        v.setBlockReason(null);
        v.setBlockedAt(null);
        vehicleRepo.save(v);
        return ApiResponse.ok(v);
    }
}
