package com.waste.repository;

import com.waste.entity.WeightReviewItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WeightReviewItemRepository extends JpaRepository<WeightReviewItem, Long> {
    List<WeightReviewItem> findByReviewIdOrderByLoadOrderAsc(Long reviewId);
    boolean existsByAppointmentId(Long appointmentId);
}
