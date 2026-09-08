import { CommonModule } from '@angular/common';
import { Component, input } from '@angular/core';

import { WeatherHour } from './weather.service';

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

  protected highlightedHour(): WeatherHour | undefined {
    return this.hours().find((hour) => hour.isDepartureMatch) ?? this.hours()[0];
  }
}
