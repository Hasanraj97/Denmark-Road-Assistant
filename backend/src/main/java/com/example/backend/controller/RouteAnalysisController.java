package com.example.backend.controller;

import com.example.backend.dto.ModelInfoDto;
import com.example.backend.dto.PredictionRequestDto;
import com.example.backend.dto.PredictionResponseDto;
import com.example.backend.dto.RouteAnalysisResponseDto;
import com.example.backend.dto.RouteRequestDto;
import com.example.backend.dto.RouteResponseDto;
import com.example.backend.entity.AppUser;
import com.example.backend.ml.MlServiceClient;
import com.example.backend.routing.GeocodingService;
import com.example.backend.routing.OsrmRoutingService;
import com.example.backend.routing.RouteObservationService;
import com.example.backend.service.RouteHistoryService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RouteAnalysisController {

    /**
     * The honest description of what this app does.
     *
     * <p>The old wording said the whole prediction was synthetic, which was
     * wrong in an unhelpful direction: it told users to disregard the weather,
     * which is a genuine live forecast. What is generated is the road-condition
     * model's training data, and that is what this sentence says.</p>
     */
    private static final String DISCLAIMER =
            "The weather in this advisory is a real Open-Meteo forecast. The road-condition "
                    + "grade comes from a model trained on generated data, so treat it as an "
                    + "illustration of the method rather than a measurement of Danish roads.";

    private final GeocodingService geocodingService;
    private final OsrmRoutingService routingService;
    private final RouteObservationService routeObservationService;
    private final MlServiceClient mlServiceClient;
    private final RouteHistoryService routeHistoryService;

    public RouteAnalysisController(
            GeocodingService geocodingService,
            OsrmRoutingService routingService,
            RouteObservationService routeObservationService,
            MlServiceClient mlServiceClient,
            RouteHistoryService routeHistoryService
    ) {
        this.geocodingService = geocodingService;
        this.routingService = routingService;
        this.routeObservationService = routeObservationService;
        this.mlServiceClient = mlServiceClient;
        this.routeHistoryService = routeHistoryService;
    }

    @PostMapping("/route")
    public RouteResponseDto route(@Valid @RequestBody RouteRequestDto request) {
        var origin = geocodingService.geocode(request.origin());
        var destination = geocodingService.geocode(request.destination());
        return routingService.toDto(routingService.computeRoute(origin, destination));
    }

    @PostMapping("/predict")
    public PredictionResponseDto predict(@Valid @RequestBody PredictionRequestDto request) {
        return mlServiceClient.predict(request);
    }

    @PostMapping("/route-and-predict")
    public RouteAnalysisResponseDto routeAndPredict(
            @Valid @RequestBody RouteRequestDto request,
            @AuthenticationPrincipal AppUser user
    ) {
        var origin = geocodingService.geocode(request.origin());
        var destination = geocodingService.geocode(request.destination());
        var route = routingService.computeRoute(origin, destination);
        var routeDto = routingService.toDto(route);

        // Gather the raw facts, including the real forecast, and let the ML
        // service turn them into features with the same code it trained on.
        var observation = routeObservationService.observe(route, request.selectedDepartureTime());
        var prediction = mlServiceClient.predict(observation);
        var features = prediction.features();

        Long historyId = null;
        if (user != null) {
            historyId = routeHistoryService
                    .saveAnalysis(user, routeDto, features, prediction, request.selectedDepartureTime())
                    .getId();
        }

        return new RouteAnalysisResponseDto(
                routeDto,
                features,
                prediction,
                observation.weather(),
                historyId,
                true,
                DISCLAIMER
        );
    }

    @GetMapping("/model-info")
    public ModelInfoDto modelInfo() {
        return mlServiceClient.getModelInfo();
    }

    @PostMapping("/retrain-model")
    public ModelInfoDto retrainModel() {
        return mlServiceClient.retrain();
    }
}
