package com.example.backend.service;

import com.example.backend.dto.FeedbackResponse;
import com.example.backend.dto.HistoryDetailResponse;
import com.example.backend.dto.HistoryResponse;
import com.example.backend.dto.PredictionResponseDto;
import com.example.backend.dto.RouteFeaturesDto;
import com.example.backend.dto.RouteResponseDto;
import com.example.backend.dto.SaveHistoryRequest;
import com.example.backend.entity.AppUser;
import com.example.backend.entity.RouteAnalysisHistory;
import com.example.backend.entity.RouteFeedback;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.RouteAnalysisHistoryRepository;
import com.example.backend.repository.RouteFeedbackRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RouteHistoryService {

    private final RouteAnalysisHistoryRepository historyRepository;
    private final RouteFeedbackRepository feedbackRepository;
    private final ObjectMapper objectMapper;

    public RouteHistoryService(
            RouteAnalysisHistoryRepository historyRepository,
            RouteFeedbackRepository feedbackRepository,
            ObjectMapper objectMapper
    ) {
        this.historyRepository = historyRepository;
        this.feedbackRepository = feedbackRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RouteAnalysisHistory saveAnalysis(
            AppUser user,
            RouteResponseDto route,
            RouteFeaturesDto features,
            PredictionResponseDto prediction,
            String selectedDepartureTime
    ) {
        RouteAnalysisHistory history = new RouteAnalysisHistory();
        history.setUser(user);
        history.setOriginLabel(route.originLabel());
        history.setDestinationLabel(route.destinationLabel());
        history.setDistanceKm(route.distanceKm());
        history.setDurationMinutes(route.durationMinutes());
        history.setSelectedDepartureTime(selectedDepartureTime);
        history.setPredictedCondition(prediction.roadConditionLabel());
        history.setConfidence(prediction.confidence());
        history.setLeaveEarlyMinutes(prediction.leaveEarlyMinutes());
        history.setAdvisoryText(prediction.advisory());
        history.setTrafficLevel(features.trafficLevel());
        history.setWeatherRisk(features.weatherRisk());
        history.setAccidentRisk(features.accidentRiskScore());
        history.setConstructionRisk(features.constructionRiskScore());
        history.setVehicleLoadFactor(features.vehicleLoadFactor());
        history.setRouteSummary(route.originLabel() + " to " + route.destinationLabel());
        history.setFeatureSnapshot(writeSnapshot(features));
        return historyRepository.save(history);
    }

    @Transactional
    public HistoryResponse saveManual(AppUser user, SaveHistoryRequest request) {
        RouteAnalysisHistory history = new RouteAnalysisHistory();
        history.setUser(user);
        history.setOriginLabel(request.originLabel());
        history.setDestinationLabel(request.destinationLabel());
        history.setDistanceKm(request.distanceKm());
        history.setDurationMinutes(request.durationMinutes());
        history.setSelectedDepartureTime(request.selectedDepartureTime());
        history.setPredictedCondition(request.predictedCondition());
        history.setConfidence(request.confidence());
        history.setLeaveEarlyMinutes(request.leaveEarlyMinutes());
        history.setAdvisoryText(request.advisoryText());
        history.setTrafficLevel(request.trafficLevel());
        history.setWeatherRisk(request.weatherRisk());
        history.setAccidentRisk(request.accidentRisk());
        history.setConstructionRisk(request.constructionRisk());
        history.setVehicleLoadFactor(request.vehicleLoadFactor());
        history.setRouteSummary(request.routeSummary());
        history.setFeatureSnapshot(request.featureSnapshot());
        return toHistoryResponse(historyRepository.save(history), null);
    }

    @Transactional(readOnly = true)
    public List<HistoryResponse> historyForUser(AppUser user) {
        return historyRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(history -> toHistoryResponse(
                        history,
                        feedbackRepository.findByHistoryIdAndUserId(history.getId(), user.getId()).orElse(null)
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public HistoryDetailResponse historyDetail(AppUser user, Long historyId) {
        RouteAnalysisHistory history = requireOwnedHistory(user, historyId);
        RouteFeedback feedback = feedbackRepository.findByHistoryIdAndUserId(historyId, user.getId()).orElse(null);
        return toHistoryDetailResponse(history, feedback);
    }

    @Transactional
    public void deleteHistory(AppUser user, Long historyId) {
        RouteAnalysisHistory history = requireOwnedHistory(user, historyId);
        historyRepository.delete(history);
    }

    @Transactional(readOnly = true)
    public RouteAnalysisHistory requireOwnedHistory(AppUser user, Long historyId) {
        return historyRepository.findByIdAndUserId(historyId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("History item not found."));
    }

    public HistoryResponse toHistoryResponse(RouteAnalysisHistory history, RouteFeedback feedback) {
        return new HistoryResponse(
                history.getId(),
                history.getOriginLabel(),
                history.getDestinationLabel(),
                history.getDistanceKm(),
                history.getDurationMinutes(),
                history.getSelectedDepartureTime(),
                history.getPredictedCondition(),
                history.getConfidence(),
                history.getLeaveEarlyMinutes(),
                history.getAdvisoryText(),
                history.getCreatedAt(),
                toFeedbackResponse(feedback)
        );
    }

    public HistoryDetailResponse toHistoryDetailResponse(RouteAnalysisHistory history, RouteFeedback feedback) {
        return new HistoryDetailResponse(
                history.getId(),
                history.getOriginLabel(),
                history.getDestinationLabel(),
                history.getDistanceKm(),
                history.getDurationMinutes(),
                history.getSelectedDepartureTime(),
                history.getPredictedCondition(),
                history.getConfidence(),
                history.getLeaveEarlyMinutes(),
                history.getAdvisoryText(),
                history.getTrafficLevel(),
                history.getWeatherRisk(),
                history.getAccidentRisk(),
                history.getConstructionRisk(),
                history.getVehicleLoadFactor(),
                history.getRouteSummary(),
                history.getFeatureSnapshot(),
                history.getCreatedAt(),
                toFeedbackResponse(feedback)
        );
    }

    public FeedbackResponse toFeedbackResponse(RouteFeedback feedback) {
        if (feedback == null) {
            return null;
        }

        return new FeedbackResponse(
                feedback.getId(),
                feedback.getHistory().getId(),
                feedback.isHelpful(),
                feedback.getComment(),
                feedback.getCreatedAt(),
                feedback.getUpdatedAt()
        );
    }

    private String writeSnapshot(RouteFeaturesDto features) {
        try {
            return objectMapper.writeValueAsString(features);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }
}
