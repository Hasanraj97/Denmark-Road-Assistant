import { HttpClient } from '@angular/common/http';
import { CommonModule } from '@angular/common';
import {
  AfterViewInit,
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  ViewChild,
  computed,
  inject,
  signal
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import * as L from 'leaflet';

import { AuthService } from './auth.service';
import { BackendUrlService } from './backend-url.service';
import { describeError, statusOf } from './error-message';
import { FeedbackCardComponent } from './feedback-card.component';
import { FeedbackResponse } from './history.service';
import { LocationService } from './location.service';
import { NavigationProgress, NavigationService, RouteStep } from './navigation.service';
import { WeatherWidgetComponent } from './weather-widget.component';
import { ModelWeatherReading, WeatherHour, WeatherService } from './weather.service';

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
  steps: RouteStep[];
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

/**
 * One reason the model gave for its grade. `impact` is a measured explanation
 * of what that value did to this prediction, not a generic description of the
 * feature, so it is worth showing in full.
 */
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
  features: RouteFeatures;
  syntheticPrediction: boolean;
}

/**
 * The response of POST /api/route-and-predict.
 *
 * <p>`syntheticModel` replaces the old `syntheticDemo`. The rename matters: the
 * flag is about the road-condition <em>model</em>, which is trained on generated
 * data. It is not about the weather, which is a real Open-Meteo forecast and is
 * reported separately in `weather` along with the source it came from.</p>
 */
/**
 * A real event Vejdirektoratet reports on this route.
 *
 * This is the only genuinely measured road information in the app. It is shown
 * beside the grade but deliberately NOT fed into the model - see
 * RouteIncidentDto on the backend for why.
 */
interface RouteIncident {
  category: string;
  title: string;
  headline: string;
  latitude: number;
  longitude: number;
  distanceFromRouteMetres: number;
  startsAt: string | null;
  endsAt: string | null;
  activeAtDeparture: boolean;
}

interface RouteAnalysisResponse {
  route: RouteInfo;
  features: RouteFeatures;
  prediction: Prediction;
  weather: ModelWeatherReading;
  incidents: RouteIncident[];
  historyId: number | null;
  syntheticModel: boolean;
  disclaimer: string;
}

/**
 * What GET /api/model-info now returns.
 *
 * <p>`datasetPath` and `modelPath` used to be here. They were removed from the
 * backend because they exposed absolute paths from the developer's own machine
 * on a public endpoint, so they are gone from this interface too rather than
 * sitting here as fields that are always undefined.</p>
 */
interface ModelInfo {
  modelName: string;
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

type LoadState = 'idle' | 'loading' | 'ready' | 'failed' | 'skipped';

/** Shown before the first analysis, when the server has not sent its own wording yet. */
const DEFAULT_DISCLAIMER =
  'The weather in this advisory is a real Open-Meteo forecast. The road-condition grade comes ' +
  'from a model trained on generated data, so treat it as an illustration of the method rather ' +
  'than a measurement of Danish roads.';

@Component({
  selector: 'app-route-analysis-page',
  imports: [CommonModule, FormsModule, WeatherWidgetComponent, FeedbackCardComponent],
  templateUrl: './route-analysis.page.html',
  styleUrl: './route-analysis.page.css'
})
export class RouteAnalysisPageComponent implements AfterViewInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly navigation = inject(NavigationService);
  private readonly backendUrlService = inject(BackendUrlService);
  private readonly locationService = inject(LocationService);
  private readonly weatherService = inject(WeatherService);

  @ViewChild('mapContainer', { static: true })
  private readonly mapContainer?: ElementRef<HTMLDivElement>;

  // Deliberately blank. These used to be pre-filled with Copenhagen and Roskilde,
  // which meant the form always looked like it had already been filled in and the
  // route chip claimed a journey the user had not asked for. The placeholders on
  // the inputs still show those two as examples.
  protected readonly origin = signal('');
  protected readonly destination = signal('');
  protected readonly selectedDepartureTime = signal('08:00');

  /**
   * The day to travel, as yyyy-MM-dd. Defaults to today.
   *
   * The app used to offer a time only, and the server guessed the date - today,
   * or tomorrow if the time had already gone. That capped every question at 24
   * hours ahead, which mattered because day_of_week and season are two of the
   * model's nineteen features: neither could ever be varied, so you could not ask
   * what a route looks like next Saturday.
   */
  protected readonly selectedDepartureDate = signal(RouteAnalysisPageComponent.isoDate(0));

  /** Today. Nothing earlier is offered - you cannot forecast the past. */
  protected readonly earliestDepartureDate = signal(RouteAnalysisPageComponent.isoDate(0));

  /**
   * The last day Open-Meteo will forecast for. Must match FORECAST_WINDOW_DAYS in
   * the backend's RouteObservationService, which rejects anything later.
   */
  protected readonly latestDepartureDate = signal(RouteAnalysisPageComponent.isoDate(6));
  protected readonly loadingMap = signal(true);
  protected readonly analyzing = signal(false);
  protected readonly retraining = signal(false);
  protected readonly locating = signal(false);

  // Each part of the page reports its own state. They used to share a single
  // anonymous error banner, so a mistyped town name, an unreachable ML service,
  // a refused geolocation prompt and a failed Open-Meteo call all produced the
  // same red box and the user could not tell which of them had happened.
  protected readonly routeState = signal<LoadState>('idle');
  protected readonly routeError = signal('');
  protected readonly predictionState = signal<LoadState>('idle');
  protected readonly predictionError = signal('');
  protected readonly weatherLoading = signal(false);
  protected readonly weatherError = signal('');
  protected readonly weatherHours = signal<WeatherHour[]>([]);
  protected readonly modelState = signal<LoadState>('idle');
  protected readonly modelError = signal('');
  protected readonly locationError = signal('');
  protected readonly locationMessage = signal('');

  protected readonly statusMessage = signal('Preparing Denmark map...');
  protected readonly analysis = signal<RouteAnalysisResponse | null>(null);
  protected readonly latestFeedback = signal<FeedbackResponse | null>(null);
  protected readonly modelInfo = signal<ModelInfo | null>(null);
  protected readonly advisoryHistory = signal<AdvisoryHistoryItem[]>([]);
  protected readonly isLoggedIn = computed(() => this.authService.isLoggedIn());
  protected readonly disclaimer = computed(() => this.analysis()?.disclaimer || DEFAULT_DISCLAIMER);
  protected readonly modelWeather = computed(() => this.analysis()?.weather ?? null);

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
  private resizeTimeout?: number;
  private destroyed = false;

  ngAfterViewInit(): void {
    this.initializeMap();
    void this.initializeBackendConnection();
  }

  /**
   * Tears the Leaflet map down.
   *
   * <p>The map was never destroyed. Leaflet attaches listeners to `window` and
   * `document` as well as to the container, so every visit to this route left
   * another live map behind: the tile layers kept their handlers, the pending
   * `invalidateSize` timer still fired into a detached component, and memory
   * grew for as long as the app stayed open. This is the first `ngOnDestroy` in
   * the application.</p>
   */
  ngOnDestroy(): void {
    this.endJourney();
    this.destroyed = true;

    if (this.resizeTimeout !== undefined) {
      window.clearTimeout(this.resizeTimeout);
      this.resizeTimeout = undefined;
    }

    this.userLocationMarker?.remove();
    this.userLocationMarker = undefined;
    this.routeLayer?.remove();
    this.routeLayer = undefined;
    this.markerLayer?.clearLayers();
    this.markerLayer = undefined;

    this.map?.remove();
    this.map = undefined;
  }

  @HostListener('window:resize')
  protected onWindowResize(): void {
    this.invalidateMapSize();
  }

  protected async analyzeRoute(): Promise<void> {
    // Check here as well as on the server. The backend does validate this and
    // returns a clear 400, but there is no reason to make the user wait for a
    // network round trip to be told they left a box empty.
    const from = this.origin().trim();
    const to = this.destination().trim();
    if (!from || !to) {
      const missing = !from && !to ? 'a start and a destination' : !from ? 'a starting point' : 'a destination';
      this.routeState.set('failed');
      this.routeError.set(`Enter ${missing} first, for example Copenhagen and Roskilde.`);
      this.statusMessage.set('');
      return;
    }

    this.analyzing.set(true);
    this.routeState.set('loading');
    this.routeError.set('');
    this.predictionState.set('loading');
    this.predictionError.set('');
    this.statusMessage.set('Finding the route and grading its road conditions...');

    try {
      await this.backendUrlService.ensureConnected();
      const response = await firstValueFrom(
        this.http.post<RouteAnalysisResponse>(this.backendUrlService.apiUrl('/route-and-predict'), {
          origin: this.origin().trim(),
          destination: this.destination().trim(),
          selectedDepartureDate: this.selectedDepartureDate(),
          selectedDepartureTime: this.selectedDepartureTime()
        })
      );

      if (this.destroyed) {
        return;
      }

      this.analysis.set(response);
      this.latestFeedback.set(null);
      this.routeState.set('ready');
      this.predictionState.set('ready');
      this.drawRoute(response.route);
      await this.loadWeather(response.route);
      this.statusMessage.set('Advisory ready: real forecast, modelled road grade.');
      this.advisoryHistory.update((history) =>
        [
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
        ].slice(0, 5)
      );
    } catch (error) {
      this.clearRoute();
      this.analysis.set(null);
      this.latestFeedback.set(null);
      this.weatherHours.set([]);
      this.weatherError.set('');
      this.statusMessage.set('No advisory available yet.');
      this.reportAnalysisFailure(error);
    } finally {
      this.analyzing.set(false);
    }
  }

  protected async retrainModel(): Promise<void> {
    this.retraining.set(true);
    this.modelState.set('loading');
    this.modelError.set('');

    try {
      await this.backendUrlService.ensureConnected();
      const info = await firstValueFrom(
        this.http.post<ModelInfo>(this.backendUrlService.apiUrl('/retrain-model'), {})
      );
      this.modelInfo.set(info);
      this.modelState.set('ready');
      this.statusMessage.set('Road-condition model retrained on freshly generated data.');
    } catch (error) {
      this.modelState.set('failed');
      this.modelError.set(describeError(error, 'The road-condition model could not be retrained.'));
    } finally {
      this.retraining.set(false);
    }
  }

  protected applySample(sample: SampleRoute): void {
    this.origin.set(sample.origin);
    this.destination.set(sample.destination);
    this.selectedDepartureTime.set('08:00');
    this.selectedDepartureDate.set(RouteAnalysisPageComponent.isoDate(0));
  }

  protected swapRoute(): void {
    const currentOrigin = this.origin();
    this.origin.set(this.destination());
    this.destination.set(currentOrigin);
  }

  protected async useMyLocation(): Promise<void> {
    this.locationError.set('');
    this.locationMessage.set('');
    this.locating.set(true);

    try {
      const position = await this.locationService.getCurrentPosition();
      this.origin.set(await this.locationService.reverseGeocode(position.lat, position.lng));
      this.showUserLocation(position);
      this.locationMessage.set('Location detected and origin updated.');
    } catch (error) {
      // Its own message, next to its own button. This used to overwrite the
      // shared banner, so a denied location permission looked exactly like a
      // failed route analysis.
      this.locationError.set(
        describeError(error, 'Unable to use your current location right now.')
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

  /** The five grades in their natural order, with the model's probability for each. */
  protected readonly probabilityRows = computed(() => {
    const probabilities = this.analysis()?.prediction.classProbabilities ?? {};
    const order = ['Excellent', 'Good', 'Moderate', 'Poor', 'Very Poor'];
    return order.map((label) => ({
      label,
      percent: Math.round((probabilities[label] ?? 0) * 1000) / 10,
      predicted: label === this.analysis()?.prediction.roadConditionLabel
    }));
  });

  /**
   * Every feature the model was given, as readable label/value pairs.
   *
   * These are the actual nineteen inputs behind the grade. Showing them is the
   * difference between "the model said Moderate" and an explainable system.
   */
  protected readonly featureRows = computed(() => {
    const features = this.analysis()?.features;
    if (!features) {
      return [];
    }
    const units: Record<string, string> = {
      distanceKm: ' km',
      estimatedDurationMin: ' min',
      avgSpeedKmh: ' km/h',
      rainfallMm: ' mm',
      temperatureC: ' °C',
      windSpeedKmh: ' km/h'
    };
    return Object.entries(features as unknown as Record<string, string | number>)
      .map(([key, value]) => ({
        key,
        label: key
          .replace(/([A-Z])/g, ' $1')
          .replace(/^./, (c) => c.toUpperCase())
          .trim(),
        value: typeof value === 'number'
          ? `${Math.round(value * 100) / 100}${units[key] ?? ''}`
          : String(value).replace(/_/g, ' ')
      }))
      .sort((a, b) => a.label.localeCompare(b.label));
  });

  /** Feature importances as percentages, for the bar chart. */
  protected readonly importanceRows = computed(() =>
    this.topFeatureImportances().map(([name, weight]) => ({
      // The model's own column names are snake_case with unit suffixes, e.g.
      // "avg_speed_kmh". Title-casing the lot turns that into "Avg Speed Kmh",
      // so units are spelled out and only the first letter is capitalised.
      name: name
        .replace(/_kmh/g, ' km/h')
        .replace(/_mm/g, ' mm')
        .replace(/_c/g, ' °C')
        .replace(/_min/g, ' min')
        .replace(/_km/g, ' km')
        .replace(/_/g, ' ')
        .replace(/^./, (c) => c.toUpperCase()),
      percent: Math.round(weight * 1000) / 10
    }))
  );

  // ---------------------------------------------------------------- guidance
  // Turn-by-turn navigation. The route already comes back with OSRM's
  // manoeuvres; this follows the device GPS along them and says what to do next.

  protected readonly navigating = signal(false);
  protected readonly navProgress = signal<NavigationProgress | null>(null);
  protected readonly navError = signal('');
  protected readonly voiceOn = signal(true);

  private watchId: number | null = null;
  private readonly spoken = new Set<string>();
  private meMarker?: L.CircleMarker;

  protected navSteps(): RouteStep[] {
    return this.analysis()?.route.steps ?? [];
  }

  protected canNavigate(): boolean {
    return this.navSteps().length > 0;
  }

  /** The instruction to follow right now. */
  protected currentStep(): RouteStep | null {
    const progress = this.navProgress();
    const steps = this.navSteps();
    return progress && steps[progress.stepIndex] ? steps[progress.stepIndex] : null;
  }

  /** The one after that, so the driver can see what is coming. */
  protected followingStep(): RouteStep | null {
    const progress = this.navProgress();
    const steps = this.navSteps();
    return progress && steps[progress.stepIndex + 1] ? steps[progress.stepIndex + 1] : null;
  }

  protected offRoute(): boolean {
    const progress = this.navProgress();
    return !!progress && progress.offRouteMetres > NavigationService.OFF_ROUTE_METRES;
  }

  protected formatDistance(metres: number): string {
    return this.navigation.formatDistance(metres);
  }

  protected formatDuration(seconds: number): string {
    return this.navigation.formatDuration(seconds);
  }

  /** Clock time the driver should arrive, from the remaining-time estimate. */
  protected arrivalClock(): string {
    const progress = this.navProgress();
    if (!progress) {
      return '';
    }
    const arrival = new Date(Date.now() + progress.remainingSeconds * 1000);
    return arrival.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  protected startJourney(): void {
    if (!this.canNavigate()) {
      this.navError.set('Analyze a route first, then you can start the journey.');
      return;
    }
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      this.navError.set('This device cannot report its location, so guidance is not available.');
      return;
    }

    this.navError.set('');
    this.spoken.clear();
    this.navigating.set(true);
    this.speak('Starting guidance.');

    this.watchId = navigator.geolocation.watchPosition(
      (position) => this.onPosition(position),
      (error) => {
        // One failed fix is not worth ending the journey over - the watch keeps
        // running and the next fix usually succeeds. A refused permission is
        // different: guidance can never work, so stop cleanly.
        if (error.code === error.PERMISSION_DENIED) {
          this.navError.set('Location permission was refused, so guidance cannot follow you.');
          this.endJourney();
          return;
        }
        this.navError.set('Waiting for a GPS signal...');
      },
      { enableHighAccuracy: true, maximumAge: 2000, timeout: 15000 }
    );
  }

  protected endJourney(): void {
    if (this.watchId !== null && typeof navigator !== 'undefined' && navigator.geolocation) {
      navigator.geolocation.clearWatch(this.watchId);
    }
    this.watchId = null;
    this.navigating.set(false);
    this.navProgress.set(null);
    this.spoken.clear();
    if (this.meMarker && this.map) {
      this.map.removeLayer(this.meMarker);
      this.meMarker = undefined;
    }
    this.stopSpeaking();
  }

  protected toggleVoice(): void {
    this.voiceOn.set(!this.voiceOn());
    if (!this.voiceOn()) {
      this.stopSpeaking();
    }
  }

  private onPosition(position: GeolocationPosition): void {
    if (this.destroyed || !this.navigating()) {
      return;
    }

    const here = { lat: position.coords.latitude, lng: position.coords.longitude };
    const route = this.analysis()?.route;
    if (!route) {
      return;
    }

    const progress = this.navigation.progress(
      route.geometry.map((point) => ({ lat: point.lat, lng: point.lng })),
      this.navSteps(),
      here
    );
    if (!progress) {
      return;
    }

    this.navProgress.set(progress);
    this.navError.set('');
    this.drawMe(here);

    if (progress.arrived) {
      this.speak('You have arrived at your destination.');
      this.endJourney();
      return;
    }

    if (progress.offRouteMetres > NavigationService.OFF_ROUTE_METRES) {
      // Said once per departure from the line, not on every GPS tick.
      if (!this.spoken.has('off-route')) {
        this.spoken.add('off-route');
        this.speak('You have left the route. Rejoin it when you can.');
      }
      return;
    }
    this.spoken.delete('off-route');

    const step = this.currentStep();
    if (step) {
      const phrase = this.navigation.announcement(step, progress.distanceToManeuvre, this.spoken);
      if (phrase) {
        this.speak(phrase);
      }
    }
  }

  private drawMe(here: { lat: number; lng: number }): void {
    if (!this.map) {
      return;
    }
    if (!this.meMarker) {
      this.meMarker = L.circleMarker([here.lat, here.lng], {
        radius: 9,
        color: '#0b111c',
        weight: 3,
        fillColor: '#33d6c0',
        fillOpacity: 1
      }).addTo(this.map);
    } else {
      this.meMarker.setLatLng([here.lat, here.lng]);
    }
    // Keep the driver centred, but pan rather than setView so a pinch-zoom is
    // not yanked back on every fix.
    this.map.panTo([here.lat, here.lng], { animate: true, duration: 0.6 });
  }

  /** Spoken guidance, through the browser's own speech synthesiser. */
  private speak(phrase: string): void {
    if (!this.voiceOn() || typeof window === 'undefined' || !('speechSynthesis' in window)) {
      return;
    }
    try {
      const utterance = new SpeechSynthesisUtterance(phrase);
      utterance.rate = 1.02;
      utterance.lang = 'en-GB';
      window.speechSynthesis.speak(utterance);
    } catch {
      // Speech is a bonus. Losing it must never interrupt the guidance itself.
    }
  }

  private stopSpeaking(): void {
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
  }

  /** Real reported events on this route, closest first. Empty when none. */
  protected incidents(): RouteIncident[] {
    return this.analysis()?.incidents ?? [];
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

  /** The model's own reasons for this grade, with the measured impact of each. */
  protected influentialFeatures(): FeatureInfluence[] {
    return this.analysis()?.prediction.influentialFeatures ?? [];
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
        summary: 'A modelled indicator of how much caution the trip may need.'
      },
      {
        label: 'Weather disruption',
        value: features.weatherRisk.toFixed(2),
        meter: Math.round(features.weatherRisk * 100),
        summary: 'How much the real forecast for your departure hour could slow the drive.'
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
        summary: 'Rainfall in the departure hour, taken from the Open-Meteo forecast.'
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
        help: 'How much the real Open-Meteo forecast for your departure hour could interfere with the trip.'
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

  protected formatFeatureName(key: string): string {
    return key.replaceAll('_', ' ');
  }

  /**
   * yyyy-MM-dd for today plus an offset, in the BROWSER's local timezone.
   *
   * Deliberately not toISOString(), which converts to UTC first and can therefore
   * report yesterday or tomorrow depending on the hour and the user's offset -
   * exactly the kind of quiet off-by-one-day bug this feature is meant to remove.
   */
  private static isoDate(offsetDays: number): string {
    const date = new Date();
    date.setDate(date.getDate() + offsetDays);
    const month = `${date.getMonth() + 1}`.padStart(2, '0');
    const day = `${date.getDate()}`.padStart(2, '0');
    return `${date.getFullYear()}-${month}-${day}`;
  }

  /** "Today, 5:00 PM" / "Sat 13 Sep, 5:00 PM" for the Route Overview panel. */
  protected formatSelectedDeparture(): string {
    const time = this.formatSelectedTime(this.selectedDepartureTime());
    const chosen = this.selectedDepartureDate();

    if (!chosen) {
      return time;
    }

    if (chosen === RouteAnalysisPageComponent.isoDate(0)) {
      return `Today, ${time}`;
    }
    if (chosen === RouteAnalysisPageComponent.isoDate(1)) {
      return `Tomorrow, ${time}`;
    }

    const [year, month, day] = chosen.split('-').map(Number);
    const date = new Date(year, month - 1, day);
    if (Number.isNaN(date.getTime())) {
      return `${chosen}, ${time}`;
    }

    const label = date.toLocaleDateString(undefined, {
      weekday: 'short',
      day: 'numeric',
      month: 'short'
    });
    return `${label}, ${time}`;
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
    this.statusMessage.set('Map ready. Analyze a Denmark route to see the advisory.');
  }

  private async initializeBackendConnection(): Promise<void> {
    this.modelState.set('loading');

    try {
      await this.backendUrlService.ensureConnected();
      await this.loadModelInfo();
    } catch (error) {
      if (this.destroyed) {
        return;
      }

      this.modelState.set('failed');
      this.modelError.set(
        describeError(
          error,
          'Could not reach the backend. Start the Spring Boot backend and the Python ml-service, then reload.'
        )
      );
    }
  }

  private async loadModelInfo(): Promise<void> {
    const info = await firstValueFrom(
      this.http.get<ModelInfo>(this.backendUrlService.apiUrl('/model-info'))
    );

    if (this.destroyed) {
      return;
    }

    this.modelInfo.set(info);
    this.modelState.set('ready');
    this.modelError.set('');
  }

  /**
   * Decides which half of the analysis to blame.
   *
   * <p>Route building and grading happen in one backend call, so the split is
   * made from what the failure actually says: the ML client's messages name the
   * model service, and a place that cannot be matched comes back as 404 from
   * geocoding. When the route could not be built the prediction was never
   * attempted, and the page says exactly that instead of showing a second
   * failure the user did not cause.</p>
   */
  private reportAnalysisFailure(error: unknown): void {
    const message = describeError(error, 'The route analysis could not be completed.');
    const lower = message.toLowerCase();
    const blameModel =
      lower.includes('model service') ||
      lower.includes('ml service') ||
      lower.includes('road-condition model') ||
      lower.includes('predict');

    if (blameModel) {
      this.routeState.set('ready');
      this.routeError.set('');
      this.predictionState.set('failed');
      this.predictionError.set(message);
      return;
    }

    this.routeState.set('failed');
    this.routeError.set(
      statusOf(error) === 404
        ? `${message} Check the spelling of both places.`
        : message
    );
    this.predictionState.set('skipped');
    this.predictionError.set('Not attempted, because there was no route to grade.');
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

      if (this.destroyed) {
        return;
      }

      this.weatherHours.set(hours);
    } catch (error) {
      this.weatherHours.set([]);
      this.weatherError.set(
        describeError(error, 'The Open-Meteo forecast could not be loaded for this route right now.')
      );
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
    if (!this.map || this.destroyed) {
      return;
    }

    // Tracked so it can be cancelled in ngOnDestroy; it used to be able to fire
    // into a component that no longer existed.
    window.clearTimeout(this.resizeTimeout);
    this.resizeTimeout = window.setTimeout(() => {
      this.resizeTimeout = undefined;
      if (!this.destroyed) {
        this.map?.invalidateSize();
      }
    }, 0);
  }
}
