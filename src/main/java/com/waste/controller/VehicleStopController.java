package com.waste.controller;

import com.waste.auth.AuthService;
import com.waste.auth.Session;
import com.waste.common.ApiResponse;
import com.waste.entity.Vehicle;
import com.waste.entity.VehicleStop;
import com.waste.enums.Role;
import com.waste.repository.VehicleRepository;
import com.waste.repository.VehicleStopRepository;
import com.waste.service.WeighReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "车辆轨迹停留点",
        description = "车载 GPS 回传行驶打点与装车停留点，作为称重复核判定合并清运（混装其他楼栋垃圾）的证据")
@RestController
@RequestMapping("/api/vehicle-stops")
@RequiredArgsConstructor
public class VehicleStopController {

    private final VehicleStopRepository stopRepo;
    private final VehicleRepository vehicleRepo;
    private final WeighReviewService weighReviewService;

    public record CreateRequest(
            @NotBlank String plateNo,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime eventTime,
            @NotBlank String location,
            Long wastePointId,
            Integer stayMinutes,
            String photos) {
    }

    @Operation(summary = "回传车辆 GPS 打点/停留点（清运公司车载设备）")
    @PostMapping
    public ApiResponse<VehicleStop> create(@RequestBody @Valid CreateRequest req) {
        Session collector = AuthService.require(Role.COLLECTOR);
        VehicleStop s = new VehicleStop();
        s.setPlateNo(req.plateNo());
        s.setEventTime(req.eventTime());
        s.setLocation(req.location());
        s.setWastePointId(req.wastePointId());
        s.setStayMinutes(req.stayMinutes() == null ? 0 : Math.max(0, req.stayMinutes()));
        s.setPhotos(req.photos());
        s.setDriverName(vehicleRepo.findAll().stream()
                .filter(v -> v.getPlateNo().equals(req.plateNo())).map(Vehicle::getDriverName)
                .findFirst().orElse(collector.name()));
        VehicleStop saved = stopRepo.save(s);
        // GPS 停留点是合并清运关键证据：自动刷新时间窗内待确认复核单
        weighReviewService.refreshPendingByPlate(req.plateNo(), req.eventTime());
        return ApiResponse.ok(saved);
    }

    @Operation(summary = "停留点查询（可按车牌过滤；物业/清运/财务可查）")
    @GetMapping
    public ApiResponse<List<VehicleStop>> list(@RequestParam(required = false) String plateNo) {
        AuthService.require(Role.PROPERTY, Role.COLLECTOR, Role.FINANCE);
        if (plateNo != null && !plateNo.isBlank()) {
            return ApiResponse.ok(stopRepo.findByPlateNoOrderByEventTimeDesc(plateNo));
        }
        List<VehicleStop> all = stopRepo.findAll();
        return ApiResponse.ok(all);
    }
}
