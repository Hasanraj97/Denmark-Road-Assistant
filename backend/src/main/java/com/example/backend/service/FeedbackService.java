package com.example.backend.service;

import com.example.backend.dto.FeedbackRequest;
import com.example.backend.dto.FeedbackResponse;
import com.example.backend.entity.AppUser;
import com.example.backend.entity.RouteFeedback;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.RouteFeedbackRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {

    private final RouteFeedbackRepository feedbackRepository;
    private final RouteHistoryService routeHistoryService;

    public FeedbackService(RouteFeedbackRepository feedbackRepository, RouteHistoryService routeHistoryService) {
        this.feedbackRepository = feedbackRepository;
        this.routeHistoryService = routeHistoryService;
    }

    @Transactional
    public FeedbackResponse saveFeedback(AppUser user, Long historyId, FeedbackRequest request) {
        var history = routeHistoryService.requireOwnedHistory(user, historyId);
        RouteFeedback feedback = feedbackRepository.findByHistoryIdAndUserId(historyId, user.getId())
                .orElseGet(RouteFeedback::new);
        feedback.setUser(user);
        feedback.setHistory(history);
        feedback.setHelpful(Boolean.TRUE.equals(request.helpful()));
        feedback.setComment(request.comment() == null ? null : request.comment().trim());
        return routeHistoryService.toFeedbackResponse(feedbackRepository.save(feedback));
    }

    @Transactional(readOnly = true)
    public FeedbackResponse getFeedback(AppUser user, Long historyId) {
        routeHistoryService.requireOwnedHistory(user, historyId);
        RouteFeedback feedback = feedbackRepository.findByHistoryIdAndUserId(historyId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Feedback not found for this history item."));
        return routeHistoryService.toFeedbackResponse(feedback);
    }
}
