package com.waste.repository;

import com.waste.entity.VehicleStop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface VehicleStopRepository extends JpaRepository<VehicleStop, Long> {
    List<VehicleStop> findByPlateNoAndEventTimeBetweenOrderByEventTimeAsc(
            String plateNo, LocalDateTime from, LocalDateTime to);
    List<VehicleStop> findByPlateNoOrderByEventTimeDesc(String plateNo);
}
