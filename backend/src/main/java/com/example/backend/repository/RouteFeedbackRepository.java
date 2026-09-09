package com.example.backend.repository;

import com.example.backend.entity.RouteFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RouteFeedbackRepository extends JpaRepository<RouteFeedback, Long> {

    Optional<RouteFeedback> findByHistoryIdAndUserId(Long historyId, Long userId);

    /**
     * Removes the feedback attached to one analysis, so the analysis itself can
     * be deleted without violating the not-null foreign key from
     * {@code route_feedback}.
     */
    void deleteByHistoryId(Long historyId);
}
