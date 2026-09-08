package com.example.backend.repository;

import com.example.backend.entity.RouteFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RouteFeedbackRepository extends JpaRepository<RouteFeedback, Long> {

    Optional<RouteFeedback> findByHistoryIdAndUserId(Long historyId, Long userId);
}
