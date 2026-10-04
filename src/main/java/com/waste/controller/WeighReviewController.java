package com.waste.controller;

import com.waste.auth.AuthService;
import com.waste.auth.Session;
import com.waste.common.ApiResponse;
import com.waste.entity.WeighReview;
import com.waste.enums.ReviewConclusion;
import com.waste.enums.ReviewStatus;
import com.waste.enums.Role;
import com.waste.service.WeighReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Tag(name = "称重差异复核",
        description = "比对装车照片、车辆路线、司机停留点、处置场回执与同日其他预约，判定合并清运/设备异常/司机误填，并拆分多户费用、联动车辆派单权限")
@RestController
@RequestMapping("/api/weigh-reviews")
@RequiredArgsConstructor
public class WeighReviewController {

    private final WeighReviewService reviewService;

    public record PhotoEvidenceRequest(Integer photoVisibleBags, String photoLabels) {
    }

    public record ShareAdjustRequest(Long appointmentId, BigDecimal confirmedWeightKg, String reason) {
    }

    public record ConfirmRequest(
            ReviewConclusion conclusion,        // 空则采纳系统建议
            String reason,                      // 推翻建议/人工调整/证据不足时必填
            BigDecimal manualWeightKg,          // 物业人工认定有效重量
            Boolean suspendVehicle,             // 是否冻结车辆后续派单（合并/误填默认冻结）
            List<ShareAdjustRequest> shareAdjusts) { // 多户拆账逐户人工调整
    }

    @Operation(summary = "物业对指定称重记录人工发起复核（自动完成首次证据比对）")
    @PostMapping("/weigh-records/{weighRecordId}/open")
    public ApiResponse<WeighReview> open(@PathVariable Long weighRecordId) {
        Session property = AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(reviewService.openManually(property, weighRecordId));
    }

    @Operation(summary = "复核单列表（可按状态过滤）")
    @GetMapping
    public ApiResponse<List<WeighReview>> list(@RequestParam(required = false) ReviewStatus status) {
        AuthService.require(Role.PROPERTY, Role.FINANCE, Role.COLLECTOR);
        return ApiResponse.ok(reviewService.list(status));
    }

    @Operation(summary = "复核单详情（含系统拆分明细、GPS 停留点、处置回执）")
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        AuthService.require(Role.PROPERTY, Role.FINANCE, Role.COLLECTOR);
        return ApiResponse.ok(reviewService.get(id));
    }

    @Operation(summary = "补传装车照片清点证据（可见袋数、照片中的楼栋/房号标签），自动重新比对")
    @PostMapping("/{id}/photo-evidence")
    public ApiResponse<WeighReview> photoEvidence(@PathVariable Long id,
                                                  @RequestBody PhotoEvidenceRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(reviewService.submitPhotoEvidence(property, id,
                req.photoVisibleBags(), req.photoLabels()));
    }

    @Operation(summary = "重新执行系统证据比对（补传 GPS/回执/同车预约后刷新建议与拆分预案）")
    @PostMapping("/{id}/analyze")
    public ApiResponse<WeighReview> analyze(@PathVariable Long id) {
        AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(reviewService.analyze(id));
    }

    @Operation(summary = "物业确认复核结论（合并清运自动拆账补单；设备异常/误填订正结算；默认冻结车辆并办结异常投诉）")
    @PostMapping("/{id}/confirm")
    public ApiResponse<WeighReview> confirm(@PathVariable Long id, @RequestBody ConfirmRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        List<WeighReviewService.ShareAdjust> adjusts = req.shareAdjusts() == null ? List.of()
                : req.shareAdjusts().stream()
                .map(a -> new WeighReviewService.ShareAdjust(a.appointmentId(), a.confirmedWeightKg(), a.reason()))
                .toList();
        return ApiResponse.ok(reviewService.confirm(property, id, req.conclusion(), req.reason(),
                req.manualWeightKg(), req.suspendVehicle(), adjusts));
    }
}
