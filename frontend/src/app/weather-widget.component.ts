import { CommonModule } from '@angular/common';
import { Component, computed, input } from '@angular/core';

import { ModelWeatherReading, WEATHER_SOURCE_LIVE, WeatherHour } from './weather.service';

@Component({
  selector: 'app-weather-widget',
  imports: [CommonModule],
  templateUrl: './weather-widget.component.html',
  styleUrl: './weather-widget.component.css'
})
export class WeatherWidgetComponent {
  readonly hours = input<WeatherHour[]>([]);
  readonly loading = input(false);
  readonly error = input('');

  /**
   * The reading the backend gave to the model, with its provenance. Shown
   * beneath the forecast so the user can see whether the grade was computed
   * from the live forecast or from the seasonal fallback.
   */
  readonly modelWeather = input<ModelWeatherReading | null>(null);

  protected readonly modelWeatherIsLive = computed(() => {
    const reading = this.modelWeather();
    return !!reading && (reading.live || reading.source === WEATHER_SOURCE_LIVE);
  });

  protected highlightedHour(): WeatherHour | undefined {
    return this.hours().find((hour) => hour.isDepartureMatch) ?? this.hours()[0];
  }

  protected humidityPercent(value: number): number {
    // The backend works in 0-1; a value above 1 is already a percentage.
    return Math.round(value <= 1 ? value * 100 : value);
  }
}
