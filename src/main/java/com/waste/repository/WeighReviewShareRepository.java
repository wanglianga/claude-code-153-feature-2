package com.waste.repository;

import com.waste.entity.WeighReviewShare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WeighReviewShareRepository extends JpaRepository<WeighReviewShare, Long> {
    List<WeighReviewShare> findByReviewIdOrderByLoadSequenceAsc(Long reviewId);
    List<WeighReviewShare> findByAppointmentId(Long appointmentId);
}
