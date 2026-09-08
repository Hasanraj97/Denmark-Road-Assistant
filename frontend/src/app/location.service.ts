import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface ReverseGeocodeResponse {
  display_name?: string;
}

@Injectable({ providedIn: 'root' })
export class LocationService {
  private readonly http = inject(HttpClient);

  getCurrentPosition(): Promise<{ lat: number; lng: number }> {
    return new Promise((resolve, reject) => {
      if (typeof navigator === 'undefined' || !navigator.geolocation) {
        reject(new Error('Geolocation is not supported on this device.'));
        return;
      }

      // Android emulators do not auto-detect a real GPS position.
      // In Android Studio, set it manually from: Extended Controls -> Location.
      navigator.geolocation.getCurrentPosition(
        (position) => {
          resolve({
            lat: position.coords.latitude,
            lng: position.coords.longitude
          });
        },
        (error) => {
          switch (error.code) {
            case error.PERMISSION_DENIED:
              reject(new Error('Location access denied. Please allow permission.'));
              break;
            case error.TIMEOUT:
              reject(new Error('Location request timed out. Please try again.'));
              break;
            default:
              reject(new Error('Unable to fetch your location right now.'));
              break;
          }
        },
        {
          enableHighAccuracy: true,
          timeout: 10000,
          maximumAge: 0
        }
      );
    });
  }

  async reverseGeocode(lat: number, lng: number): Promise<string> {
    const params = new HttpParams()
      .set('lat', lat.toString())
      .set('lon', lng.toString())
      .set('format', 'json');

    const response = await firstValueFrom(
      this.http.get<ReverseGeocodeResponse>('https://nominatim.openstreetmap.org/reverse', {
        params,
        headers: {
          Accept: 'application/json'
        }
      })
    );

    return response.display_name?.trim() || `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
  }
}
