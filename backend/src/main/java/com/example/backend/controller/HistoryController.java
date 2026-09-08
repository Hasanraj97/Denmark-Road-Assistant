package com.example.backend.controller;

import com.example.backend.dto.FeedbackRequest;
import com.example.backend.dto.FeedbackResponse;
import com.example.backend.dto.HistoryDetailResponse;
import com.example.backend.dto.HistoryResponse;
import com.example.backend.dto.SaveHistoryRequest;
import com.example.backend.entity.AppUser;
import com.example.backend.service.FeedbackService;
import com.example.backend.service.RouteHistoryService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final RouteHistoryService routeHistoryService;
    private final FeedbackService feedbackService;

    public HistoryController(RouteHistoryService routeHistoryService, FeedbackService feedbackService) {
        this.routeHistoryService = routeHistoryService;
        this.feedbackService = feedbackService;
    }

    @GetMapping
    public List<HistoryResponse> history(@AuthenticationPrincipal AppUser user) {
        return routeHistoryService.historyForUser(user);
    }

    @GetMapping("/{id}")
    public HistoryDetailResponse historyDetail(@AuthenticationPrincipal AppUser user, @PathVariable Long id) {
        return routeHistoryService.historyDetail(user, id);
    }

    @PostMapping
    public HistoryResponse saveHistory(
            @AuthenticationPrincipal AppUser user,
            @Valid @RequestBody SaveHistoryRequest request
    ) {
        return routeHistoryService.saveManual(user, request);
    }

    @DeleteMapping("/{id}")
    public void deleteHistory(@AuthenticationPrincipal AppUser user, @PathVariable Long id) {
        routeHistoryService.deleteHistory(user, id);
    }

    @GetMapping("/{historyId}/feedback")
    public FeedbackResponse feedback(@AuthenticationPrincipal AppUser user, @PathVariable Long historyId) {
        return feedbackService.getFeedback(user, historyId);
    }

    @PostMapping("/{historyId}/feedback")
    public FeedbackResponse saveFeedback(
            @AuthenticationPrincipal AppUser user,
            @PathVariable Long historyId,
            @Valid @RequestBody FeedbackRequest request
    ) {
        return feedbackService.saveFeedback(user, historyId, request);
    }
}
