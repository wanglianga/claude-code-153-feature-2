package com.waste.repository;

import com.waste.entity.WeighReview;
import com.waste.enums.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WeighReviewRepository extends JpaRepository<WeighReview, Long> {
    Optional<WeighReview> findByWeighRecordId(Long weighRecordId);
    List<WeighReview> findByAppointmentId(Long appointmentId);
    List<WeighReview> findByStatusOrderByIdDesc(ReviewStatus status);
    List<WeighReview> findByPlateNoOrderByIdDesc(String plateNo);
}
