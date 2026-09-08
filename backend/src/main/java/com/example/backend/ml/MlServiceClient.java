package com.example.backend.ml;

import com.example.backend.config.AppProperties;
import com.example.backend.dto.ModelInfoDto;
import com.example.backend.dto.PredictionRequestDto;
import com.example.backend.dto.PredictionResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Service
public class MlServiceClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public MlServiceClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.objectMapper = objectMapper;
        this.baseUrl = appProperties.mlServiceBaseUrl();
    }

    public PredictionResponseDto predict(PredictionRequestDto request) {
        return postJson("/predict-road-condition", request, PredictionResponseDto.class,
                "The synthetic ML service is unavailable. Start the Python ml-service on port 8000.");
    }

    public ModelInfoDto getModelInfo() {
        return getJson("/model-info", ModelInfoDto.class,
                "Could not load model info from the synthetic ML service.");
    }

    public ModelInfoDto retrain() {
        return postJson("/retrain", null, ModelInfoDto.class,
                "Could not retrain the synthetic ML model.");
    }

    private <T> T getJson(String path, Class<T> responseType, String message) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response, message);
            return objectMapper.readValue(response.body(), responseType);
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_GATEWAY, message, ex);
        }
    }

    private <T> T postJson(String path, Object body, Class<T> responseType, String message) {
        try {
            String json = body == null ? "{}" : objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response, message);
            return objectMapper.readValue(response.body(), responseType);
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_GATEWAY, message, ex);
        }
    }

    private void ensureSuccess(HttpResponse<String> response, String message) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ResponseStatusException(
                    BAD_GATEWAY,
                    message + " ML service returned status " + response.statusCode() + ". Body: " + response.body()
            );
        }
    }
}
