package com.waste.repository;

import com.waste.entity.WeightReview;
import com.waste.enums.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WeightReviewRepository extends JpaRepository<WeightReview, Long> {
    Optional<WeightReview> findByWeighRecordId(Long weighRecordId);
    List<WeightReview> findByStatusOrderByIdDesc(ReviewStatus status);
    List<WeightReview> findAllByOrderByIdDesc();
    List<WeightReview> findByPlateNoOrderByIdDesc(String plateNo);
}
