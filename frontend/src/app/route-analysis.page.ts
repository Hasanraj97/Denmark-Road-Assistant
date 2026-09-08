import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { CommonModule } from '@angular/common';
import { AfterViewInit, Component, ElementRef, HostListener, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import * as L from 'leaflet';

import { AuthService } from './auth.service';
import { BackendUrlService } from './backend-url.service';
import { FeedbackCardComponent } from './feedback-card.component';
import { FeedbackResponse } from './history.service';
import { LocationService } from './location.service';
import { WeatherWidgetComponent } from './weather-widget.component';
import { WeatherHour, WeatherService } from './weather.service';

interface RoutePoint {
  lat: number;
  lng: number;
}

interface RouteInfo {
  originLabel: string;
  destinationLabel: string;
  distanceKm: number;
  durationMinutes: number;
  geometry: RoutePoint[];
}

interface RouteFeatures {
  distanceKm: number;
  estimatedDurationMin: number;
  avgSpeedKmh: number;
  roadTypeScore: number;
  trafficLevel: number;
  weatherRisk: number;
  rainfallMm: number;
  timeOfDay: string;
  dayOfWeek: string;
  urbanDensity: number;
  accidentRiskScore: number;
  constructionRiskScore: number;
  elevationVariation: number;
  temperatureC: number;
  humidity: number;
  windSpeedKmh: number;
  season: string;
  regionType: string;
  vehicleLoadFactor: number;
}

interface FeatureInfluence {
  feature: string;
  value: string;
  impact: string;
}

interface Prediction {
  roadConditionLabel: string;
  confidence: number;
  leaveEarlyMinutes: number;
  advisory: string;
  influentialFeatures: FeatureInfluence[];
  classProbabilities: Record<string, number>;
  syntheticPrediction: boolean;
}

interface RouteAnalysisResponse {
  route: RouteInfo;
  features: RouteFeatures;
  prediction: Prediction;
  historyId: number | null;
  syntheticDemo: boolean;
  disclaimer: string;
}

interface ModelInfo {
  modelName: string;
  datasetPath: string;
  modelPath: string;
  sampleCount: number;
  accuracy: number;
  labels: string[];
  featureImportances: Record<string, number>;
  syntheticDisclaimer: string;
}

interface AdvisoryHistoryItem {
  timestamp: string;
  origin: string;
  destination: string;
  label: string;
  advisory: string;
}

interface SampleRoute {
  origin: string;
  destination: string;
}

interface RiskSignal {
  label: string;
  value: string;
  meter: number;
  summary: string;
}

interface PredictionInputCard {
  label: string;
  value: string;
  help: string;
}

@Component({
  selector: 'app-route-analysis-page',
  imports: [CommonModule, FormsModule, WeatherWidgetComponent, FeedbackCardComponent],
  templateUrl: './route-analysis.page.html',
  styleUrl: './route-analysis.page.css'
})
export class RouteAnalysisPageComponent implements AfterViewInit {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly backendUrlService = inject(BackendUrlService);
  private readonly locationService = inject(LocationService);
  private readonly weatherService = inject(WeatherService);

  @ViewChild('mapContainer', { static: true })
  private readonly mapContainer?: ElementRef<HTMLDivElement>;

  protected readonly origin = signal('Copenhagen');
  protected readonly destination = signal('Roskilde');
  protected readonly selectedDepartureTime = signal('08:00');
  protected readonly loadingMap = signal(true);
  protected readonly analyzing = signal(false);
  protected readonly retraining = signal(false);
  protected readonly locating = signal(false);
  protected readonly weatherLoading = signal(false);
  protected readonly weatherError = signal('');
  protected readonly weatherHours = signal<WeatherHour[]>([]);
  protected readonly errorMessage = signal('');
  protected readonly locationMessage = signal('');
  protected readonly statusMessage = signal('Preparing Denmark map...');
  protected readonly analysis = signal<RouteAnalysisResponse | null>(null);
  protected readonly latestFeedback = signal<FeedbackResponse | null>(null);
  protected readonly modelInfo = signal<ModelInfo | null>(null);
  protected readonly advisoryHistory = signal<AdvisoryHistoryItem[]>([]);
  protected readonly isLoggedIn = computed(() => this.authService.isLoggedIn());
  protected readonly sampleRoutes: SampleRoute[] = [
    { origin: 'Copenhagen', destination: 'Roskilde' },
    { origin: 'Odense', destination: 'Aarhus' },
    { origin: 'Aalborg', destination: 'Aarhus' },
    { origin: 'Copenhagen Airport', destination: 'Odense' }
  ];
  protected readonly canAnalyze = computed(
    () =>
      !this.loadingMap() &&
      !this.analyzing() &&
      this.origin().trim().length > 0 &&
      this.destination().trim().length > 0
  );
  protected readonly topFeatureImportances = computed(() =>
    Object.entries(this.modelInfo()?.featureImportances ?? {}).slice(0, 6)
  );
  private map?: L.Map;
  private routeLayer?: L.Polyline;
  private markerLayer?: L.LayerGroup;
  private userLocationMarker?: L.Marker;

  ngAfterViewInit(): void {
    this.initializeMap();
    void this.initializeBackendConnection();
  }

  @HostListener('window:resize')
  protected onWindowResize(): void {
    this.invalidateMapSize();
  }

  protected async analyzeRoute(): Promise<void> {
    this.errorMessage.set('');
    this.analyzing.set(true);
    this.statusMessage.set('Analyzing synthetic Denmark road conditions...');

    try {
      await this.backendUrlService.ensureConnected();
      const response = await firstValueFrom(
        this.http.post<RouteAnalysisResponse>(this.backendUrlService.apiUrl('/route-and-predict'), {
          origin: this.origin().trim(),
          destination: this.destination().trim(),
          selectedDepartureTime: this.selectedDepartureTime()
        })
      );

      this.analysis.set(response);
      this.latestFeedback.set(null);
      this.drawRoute(response.route);
      await this.loadWeather(response.route);
      this.statusMessage.set('Synthetic advisory ready.');
      this.advisoryHistory.update((history) => [
        {
          timestamp: new Intl.DateTimeFormat('en-GB', {
            hour: '2-digit',
            minute: '2-digit',
            day: '2-digit',
            month: 'short'
          }).format(new Date()),
          origin: response.route.originLabel,
          destination: response.route.destinationLabel,
          label: response.prediction.roadConditionLabel,
          advisory: response.prediction.advisory
        },
        ...history
      ].slice(0, 5));
    } catch (error) {
      this.clearRoute();
      this.analysis.set(null);
      this.latestFeedback.set(null);
      this.weatherHours.set([]);
      this.weatherError.set('');
      this.statusMessage.set('No synthetic advisory available yet.');
      this.errorMessage.set(this.extractErrorMessage(error));
    } finally {
      this.analyzing.set(false);
    }
  }

  protected async retrainModel(): Promise<void> {
    this.errorMessage.set('');
    this.retraining.set(true);

    try {
      await this.backendUrlService.ensureConnected();
      const info = await firstValueFrom(
        this.http.post<ModelInfo>(this.backendUrlService.apiUrl('/retrain-model'), {})
      );
      this.modelInfo.set(info);
      this.statusMessage.set('Synthetic model retrained successfully.');
    } catch (error) {
      this.errorMessage.set(this.extractErrorMessage(error));
    } finally {
      this.retraining.set(false);
    }
  }

  protected applySample(sample: SampleRoute): void {
    this.origin.set(sample.origin);
    this.destination.set(sample.destination);
    this.selectedDepartureTime.set('08:00');
  }

  protected swapRoute(): void {
    const currentOrigin = this.origin();
    this.origin.set(this.destination());
    this.destination.set(currentOrigin);
  }

  protected async useMyLocation(): Promise<void> {
    this.errorMessage.set('');
    this.locationMessage.set('');
    this.locating.set(true);

    try {
      const position = await this.locationService.getCurrentPosition();
      this.origin.set(await this.locationService.reverseGeocode(position.lat, position.lng));
      this.showUserLocation(position);
      this.locationMessage.set('Location detected and origin updated.');
    } catch (error) {
      this.errorMessage.set(
        error instanceof Error ? error.message : 'Unable to use your current location right now.'
      );
    } finally {
      this.locating.set(false);
    }
  }

  protected conditionClass(label: string | undefined): string {
    switch (label) {
      case 'Excellent':
        return 'excellent';
      case 'Good':
        return 'good';
      case 'Moderate':
        return 'moderate';
      case 'Poor':
        return 'poor';
      case 'Very Poor':
        return 'very-poor';
      default:
        return '';
    }
  }

  protected confidencePercent(confidence: number | undefined): number {
    return Math.round((confidence ?? 0) * 100);
  }

  protected routeLabel(): string {
    const analysis = this.analysis();
    if (analysis) {
      return `${analysis.route.originLabel.split(',')[0]} to ${analysis.route.destinationLabel.split(',')[0]}`;
    }

    return `${this.origin().trim() || 'Origin'} to ${this.destination().trim() || 'Destination'}`;
  }

  protected advisoryTags(): string[] {
    const prediction = this.analysis()?.prediction;
    if (!prediction) {
      return [];
    }

    return prediction.influentialFeatures.slice(0, 3).map((feature) => feature.feature);
  }

  protected riskSignals(): RiskSignal[] {
    const features = this.analysis()?.features;
    if (!features) {
      return [];
    }

    return [
      {
        label: 'Traffic pressure',
        value: features.trafficLevel.toFixed(2),
        meter: Math.round(features.trafficLevel * 100),
        summary: 'How likely the route is to feel busy or congested.'
      },
      {
        label: 'Safety disruption risk',
        value: features.accidentRiskScore.toFixed(2),
        meter: Math.round(features.accidentRiskScore * 100),
        summary: 'A synthetic indicator of how much caution the trip may need.'
      },
      {
        label: 'Weather disruption',
        value: features.weatherRisk.toFixed(2),
        meter: Math.round(features.weatherRisk * 100),
        summary: 'Estimated chance that weather could slow or complicate the drive.'
      },
      {
        label: 'Urban delay potential',
        value: features.urbanDensity.toFixed(2),
        meter: Math.round(features.urbanDensity * 100),
        summary: 'How city-heavy the route appears, which can increase stop-and-go travel.'
      },
      {
        label: 'Rain outlook',
        value: `${features.rainfallMm.toFixed(1)} mm`,
        meter: Math.min(100, Math.round((features.rainfallMm / 10) * 100)),
        summary: 'Estimated rainfall level tied to the synthetic weather scenario.'
      }
    ];
  }

  protected predictionInputs(): PredictionInputCard[] {
    const features = this.analysis()?.features;
    if (!features) {
      return [];
    }

    return [
      {
        label: 'Average speed',
        value: `${features.avgSpeedKmh.toFixed(1)} km/h`,
        help: 'Used to judge whether the route behaves more like city driving or a smoother intercity run.'
      },
      {
        label: 'Traffic pressure',
        value: `${Math.round(features.trafficLevel * 100)}%`,
        help: 'Shows how busy or congested the route is expected to feel at your selected departure time.'
      },
      {
        label: 'Weather disruption',
        value: `${Math.round(features.weatherRisk * 100)}%`,
        help: 'Summarizes how much weather could interfere with the trip experience.'
      }
    ];
  }

  protected formatFeatureValue(key: string, value: string | number): string {
    if (typeof value === 'string') {
      return value.replaceAll('_', ' ');
    }

    if (key.toLowerCase().includes('temperature')) {
      return `${value} deg C`;
    }

    if (key.toLowerCase().includes('rainfall')) {
      return `${value} mm`;
    }

    if (key.toLowerCase().includes('wind')) {
      return `${value} km/h`;
    }

    if (key.toLowerCase().includes('distance')) {
      return `${value} km`;
    }

    if (key.toLowerCase().includes('duration')) {
      return `${value} min`;
    }

    return `${value}`;
  }

  protected formatSelectedTime(value: string | undefined): string {
    if (!value) {
      return 'Not set';
    }

    const [hourText, minuteText] = value.split(':');
    const hour = Number(hourText);
    const minute = Number(minuteText);

    if (Number.isNaN(hour) || Number.isNaN(minute)) {
      return value;
    }

    const formattedHour = hour % 12 === 0 ? 12 : hour % 12;
    const period = hour >= 12 ? 'PM' : 'AM';
    return `${formattedHour}:${minute.toString().padStart(2, '0')} ${period}`;
  }

  protected formatTimeOfDayLabel(value: string): string {
    return value
      .split('_')
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
      .join(' ');
  }

  private initializeMap(): void {
    if (!this.mapContainer) {
      return;
    }

    this.map = L.map(this.mapContainer.nativeElement, {
      zoomControl: true
    }).setView([56.2639, 9.5018], 7);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap contributors'
    }).addTo(this.map);

    this.markerLayer = L.layerGroup().addTo(this.map);
    this.invalidateMapSize();
    this.loadingMap.set(false);
    this.statusMessage.set('Map ready. Analyze a Denmark route to see the synthetic advisory.');
  }

  private async initializeBackendConnection(): Promise<void> {
    try {
      await this.backendUrlService.ensureConnected();
      await this.loadModelInfo();
    } catch {
      this.errorMessage.set(
        'Backend discovery failed. Make sure the Spring Boot backend and Python ML service are running.'
      );
    }
  }

  private async loadModelInfo(): Promise<void> {
    const info = await firstValueFrom(
      this.http.get<ModelInfo>(this.backendUrlService.apiUrl('/model-info'))
    );
    this.modelInfo.set(info);
    this.errorMessage.set('');
  }

  private drawRoute(route: RouteInfo): void {
    if (!this.map || !this.markerLayer || route.geometry.length === 0) {
      return;
    }

    this.clearRoute();

    const points = route.geometry.map((point) => L.latLng(point.lat, point.lng));
    this.routeLayer = L.polyline(points, {
      color: '#0f766e',
      weight: 6,
      opacity: 0.92
    }).addTo(this.map);

    const start = points[0];
    const end = points[points.length - 1];

    L.circleMarker(start, {
      radius: 8,
      color: '#0b4f4a',
      fillColor: '#14b8a6',
      fillOpacity: 1,
      weight: 3
    })
      .bindPopup(`Origin: ${route.originLabel}`)
      .addTo(this.markerLayer);

    L.circleMarker(end, {
      radius: 8,
      color: '#7f1d1d',
      fillColor: '#ef4444',
      fillOpacity: 1,
      weight: 3
    })
      .bindPopup(`Destination: ${route.destinationLabel}`)
      .addTo(this.markerLayer);

    this.invalidateMapSize();
    this.map.fitBounds(this.routeLayer.getBounds(), { padding: [36, 36] });
  }

  private clearRoute(): void {
    this.routeLayer?.remove();
    this.routeLayer = undefined;
    this.markerLayer?.clearLayers();
  }

  private showUserLocation(position: RoutePoint): void {
    if (!this.map) {
      return;
    }

    this.userLocationMarker?.remove();
    this.userLocationMarker = L.marker([position.lat, position.lng])
      .addTo(this.map)
      .bindPopup('You are here')
      .openPopup();

    this.map.setView([position.lat, position.lng], 13);
  }

  private extractErrorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      const body = error.error;
      if (typeof body === 'string' && body.length > 0) {
        return body;
      }
      if (body?.message) {
        return body.message;
      }
      return `Request failed with status ${error.status}.`;
    }

    return 'Something went wrong while analyzing the route.';
  }

  private async loadWeather(route: RouteInfo): Promise<void> {
    const weatherPoint = this.routeWeatherPoint(route);
    if (!weatherPoint) {
      this.weatherHours.set([]);
      this.weatherError.set('No route coordinates were available for weather lookup.');
      return;
    }

    this.weatherLoading.set(true);
    this.weatherError.set('');

    try {
      const hours = await firstValueFrom(
        this.weatherService.getHourlyForecast(
          weatherPoint.lat,
          weatherPoint.lng,
          this.selectedDepartureTime()
        )
      );
      this.weatherHours.set(hours);
    } catch {
      this.weatherHours.set([]);
      this.weatherError.set('Open-Meteo forecast could not be loaded for this route right now.');
    } finally {
      this.weatherLoading.set(false);
    }
  }

  private routeWeatherPoint(route: RouteInfo): RoutePoint | null {
    if (!route.geometry.length) {
      return null;
    }

    return route.geometry[Math.floor(route.geometry.length / 2)] ?? route.geometry[0] ?? null;
  }

  private invalidateMapSize(): void {
    if (!this.map) {
      return;
    }

    window.setTimeout(() => this.map?.invalidateSize(), 0);
  }
}
