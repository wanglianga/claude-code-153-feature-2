package com.waste.controller;

import com.waste.auth.AuthService;
import com.waste.auth.Session;
import com.waste.common.ApiResponse;
import com.waste.entity.Vehicle;
import com.waste.enums.Role;
import com.waste.repository.VehicleRepository;
import com.waste.service.WeighReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "车辆", description = "清运车辆档案与派单权限（称重复核认定合并清运/司机误填后冻结，物业核查后解冻）")
@RestController
@RequestMapping("/api/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleRepository vehicleRepository;
    private final WeighReviewService weighReviewService;

    public record CreateRequest(
            @NotBlank String plateNo,
            @NotNull @DecimalMin("0.1") BigDecimal capacityTons,
            @NotBlank String driverName,
            String driverPhone,
            @NotBlank String companyName) {
    }

    public record UnsuspendRequest(@NotBlank String reason) {
    }

    @Operation(summary = "车辆列表")
    @GetMapping
    public ApiResponse<List<Vehicle>> list() {
        return ApiResponse.ok(vehicleRepository.findAll());
    }

    @Operation(summary = "新增车辆（清运公司/管理员）")
    @PostMapping
    public ApiResponse<Vehicle> create(@RequestBody @Valid CreateRequest req) {
        AuthService.require(Role.COLLECTOR);
        Vehicle v = new Vehicle();
        v.setPlateNo(req.plateNo());
        v.setCapacityTons(req.capacityTons());
        v.setDriverName(req.driverName());
        v.setDriverPhone(req.driverPhone());
        v.setCompanyName(req.companyName());
        return ApiResponse.ok(vehicleRepository.save(v));
    }

    @Operation(summary = "物业核查后解冻车辆，恢复后续派单权限（必须填写核查说明）")
    @PostMapping("/{id}/unsuspend")
    public ApiResponse<Vehicle> unsuspend(@PathVariable Long id, @RequestBody @Valid UnsuspendRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(weighReviewService.unsuspend(property, id, req.reason()));
    }
}
