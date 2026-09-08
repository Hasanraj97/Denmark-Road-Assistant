package com.example.backend.repository;

import com.example.backend.entity.RouteAnalysisHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RouteAnalysisHistoryRepository extends JpaRepository<RouteAnalysisHistory, Long> {

    List<RouteAnalysisHistory> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<RouteAnalysisHistory> findByIdAndUserId(Long id, Long userId);
}
