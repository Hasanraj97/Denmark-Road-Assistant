import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { map, Observable } from 'rxjs';

export interface WeatherHour {
  timeIso: string;
  displayHour: string;
  temperatureC: number;
  precipitationProbability: number;
  precipitationMm: number;
  weatherCode: number;
  weatherLabel: string;
  weatherIcon: string;
  windSpeedKmh: number;
  isDepartureMatch: boolean;
}

interface OpenMeteoHourlyResponse {
  hourly: {
    time: string[];
    temperature_2m: number[];
    precipitation_probability: number[];
    precipitation: number[];
    weather_code: number[];
    wind_speed_10m: number[];
  };
}

@Injectable({ providedIn: 'root' })
export class WeatherService {
  private readonly http = inject(HttpClient);

  // Open-Meteo is public and keyless, so calling it directly from Angular keeps the app simpler.
  getHourlyForecast(latitude: number, longitude: number, selectedDepartureTime?: string): Observable<WeatherHour[]> {
    return this.http
      .get<OpenMeteoHourlyResponse>('https://api.open-meteo.com/v1/forecast', {
        params: {
          latitude: latitude.toFixed(5),
          longitude: longitude.toFixed(5),
          hourly:
            'temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m',
          timezone: 'auto'
        }
      })
      .pipe(
        map((response) => {
          const now = new Date();
          const rawHours = response.hourly.time.map((timeIso, index) => {
            const date = new Date(timeIso);
            const weather = this.describeWeatherCode(response.hourly.weather_code[index] ?? 0);

            return {
              timeIso,
              date,
              timeMs: date.getTime(),
              displayHour: new Intl.DateTimeFormat('en-GB', {
                hour: 'numeric'
              }).format(date),
              temperatureC: Math.round(response.hourly.temperature_2m[index] ?? 0),
              precipitationProbability: Math.round(response.hourly.precipitation_probability[index] ?? 0),
              precipitationMm: Math.round((response.hourly.precipitation[index] ?? 0) * 10) / 10,
              weatherCode: response.hourly.weather_code[index] ?? 0,
              weatherLabel: weather.label,
              weatherIcon: weather.icon,
              windSpeedKmh: Math.round(response.hourly.wind_speed_10m[index] ?? 0)
            };
          });

          const futureHours = rawHours.filter((hour) => hour.timeMs >= now.getTime() - 60 * 60 * 1000);
          const anchorTime = this.resolveDepartureAnchor(selectedDepartureTime, futureHours, now);
          const startIndex = Math.max(
            0,
            futureHours.findIndex((hour) => hour.timeMs >= anchorTime)
          );
          const sliceStart = startIndex === -1 ? 0 : startIndex;
          const selectedHours = futureHours.slice(sliceStart, sliceStart + 10);
          const selectedIndex = this.closestHourIndex(selectedHours, anchorTime);

          return selectedHours.map((hour, index) => ({
            ...hour,
            isDepartureMatch: index === selectedIndex
          }));
        })
      );
  }

  private resolveDepartureAnchor(selectedDepartureTime: string | undefined, hours: Array<{ date: Date; timeMs: number }>, now: Date): number {
    if (!hours.length) {
      return now.getTime();
    }

    if (!selectedDepartureTime) {
      return hours[0].timeMs;
    }

    const [hourText, minuteText] = selectedDepartureTime.split(':');
    const hour = Number(hourText);
    const minute = Number(minuteText);
    if (Number.isNaN(hour) || Number.isNaN(minute)) {
      return hours[0].timeMs;
    }

    const candidate = new Date(now);
    candidate.setHours(hour, minute, 0, 0);
    if (candidate.getTime() < now.getTime() - 30 * 60 * 1000) {
      candidate.setDate(candidate.getDate() + 1);
    }

    return candidate.getTime();
  }

  private closestHourIndex(hours: Array<{ timeMs: number }>, anchorTime: number): number {
    if (!hours.length) {
      return -1;
    }

    return hours.reduce(
      (closestIndex, hour, index, collection) =>
        Math.abs(hour.timeMs - anchorTime) < Math.abs(collection[closestIndex].timeMs - anchorTime)
          ? index
          : closestIndex,
      0
    );
  }

  private describeWeatherCode(code: number): { icon: string; label: string } {
    if (code === 0) {
      return { icon: '☀', label: 'Clear' };
    }
    if ([1, 2].includes(code)) {
      return { icon: '⛅', label: 'Partly cloudy' };
    }
    if (code === 3) {
      return { icon: '☁', label: 'Cloudy' };
    }
    if ([45, 48].includes(code)) {
      return { icon: '🌫', label: 'Fog' };
    }
    if ([51, 53, 55, 56, 57].includes(code)) {
      return { icon: '🌦', label: 'Drizzle' };
    }
    if ([61, 63, 65, 66, 67, 80, 81, 82].includes(code)) {
      return { icon: '🌧', label: 'Rain' };
    }
    if ([71, 73, 75, 77, 85, 86].includes(code)) {
      return { icon: '🌨', label: 'Snow' };
    }
    if ([95, 96, 99].includes(code)) {
      return { icon: '⛈', label: 'Thunderstorm' };
    }

    return { icon: '☁', label: 'Mixed weather' };
  }
}
