package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "route_analysis_history")
public class RouteAnalysisHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false, length = 255)
    private String originLabel;

    @Column(nullable = false, length = 255)
    private String destinationLabel;

    @Column(nullable = false)
    private double distanceKm;

    @Column(nullable = false)
    private double durationMinutes;

    @Column(length = 10)
    private String selectedDepartureTime;

    @Column(nullable = false, length = 40)
    private String predictedCondition;

    @Column(nullable = false)
    private double confidence;

    @Column(nullable = false)
    private int leaveEarlyMinutes;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String advisoryText;

    @Column(nullable = false)
    private double trafficLevel;

    @Column(nullable = false)
    private double weatherRisk;

    @Column(nullable = false)
    private double accidentRisk;

    @Column(nullable = false)
    private double constructionRisk;

    @Column(nullable = false)
    private double vehicleLoadFactor;

    @Column(length = 255)
    private String routeSummary;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String featureSnapshot;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public void setUser(AppUser user) {
        this.user = user;
    }

    public String getOriginLabel() {
        return originLabel;
    }

    public void setOriginLabel(String originLabel) {
        this.originLabel = originLabel;
    }

    public String getDestinationLabel() {
        return destinationLabel;
    }

    public void setDestinationLabel(String destinationLabel) {
        this.destinationLabel = destinationLabel;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(double distanceKm) {
        this.distanceKm = distanceKm;
    }

    public double getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(double durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public String getSelectedDepartureTime() {
        return selectedDepartureTime;
    }

    public void setSelectedDepartureTime(String selectedDepartureTime) {
        this.selectedDepartureTime = selectedDepartureTime;
    }

    public String getPredictedCondition() {
        return predictedCondition;
    }

    public void setPredictedCondition(String predictedCondition) {
        this.predictedCondition = predictedCondition;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public int getLeaveEarlyMinutes() {
        return leaveEarlyMinutes;
    }

    public void setLeaveEarlyMinutes(int leaveEarlyMinutes) {
        this.leaveEarlyMinutes = leaveEarlyMinutes;
    }

    public String getAdvisoryText() {
        return advisoryText;
    }

    public void setAdvisoryText(String advisoryText) {
        this.advisoryText = advisoryText;
    }

    public double getTrafficLevel() {
        return trafficLevel;
    }

    public void setTrafficLevel(double trafficLevel) {
        this.trafficLevel = trafficLevel;
    }

    public double getWeatherRisk() {
        return weatherRisk;
    }

    public void setWeatherRisk(double weatherRisk) {
        this.weatherRisk = weatherRisk;
    }

    public double getAccidentRisk() {
        return accidentRisk;
    }

    public void setAccidentRisk(double accidentRisk) {
        this.accidentRisk = accidentRisk;
    }

    public double getConstructionRisk() {
        return constructionRisk;
    }

    public void setConstructionRisk(double constructionRisk) {
        this.constructionRisk = constructionRisk;
    }

    public double getVehicleLoadFactor() {
        return vehicleLoadFactor;
    }

    public void setVehicleLoadFactor(double vehicleLoadFactor) {
        this.vehicleLoadFactor = vehicleLoadFactor;
    }

    public String getRouteSummary() {
        return routeSummary;
    }

    public void setRouteSummary(String routeSummary) {
        this.routeSummary = routeSummary;
    }

    public String getFeatureSnapshot() {
        return featureSnapshot;
    }

    public void setFeatureSnapshot(String featureSnapshot) {
        this.featureSnapshot = featureSnapshot;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
