import { Injectable } from '@angular/core';

export interface LatLng {
  lat: number;
  lng: number;
}

export interface RouteStep {
  instruction: string;
  maneuverType: string;
  modifier: string;
  roadName: string;
  distanceMetres: number;
  durationSeconds: number;
  latitude: number;
  longitude: number;
  geometryIndex: number;
}

/** Where the driver is, relative to the route they asked for. */
export interface NavigationProgress {
  /** The step being driven right now. */
  stepIndex: number;
  /** Metres to the next manoeuvre. */
  distanceToManeuvre: number;
  /** Metres still to drive to the destination. */
  remainingMetres: number;
  /** Seconds still to drive, from OSRM's own per-step estimates. */
  remainingSeconds: number;
  /** How far the driver is from the route line. Large means they left it. */
  offRouteMetres: number;
  /** Index of the closest point on the route line. */
  nearestIndex: number;
  /** True once the destination is essentially reached. */
  arrived: boolean;
}

/**
 * The maths behind turn-by-turn guidance.
 *
 * <p>Deliberately a separate, dependency-free service: it touches no map, no DOM
 * and no browser API, so it can be reasoned about and tested on its own. The
 * component is left to deal with the map, the voice and the buttons.</p>
 */
@Injectable({ providedIn: 'root' })
export class NavigationService {
  /** Beyond this, the driver is treated as having left the route. */
  static readonly OFF_ROUTE_METRES = 60;

  /** Within this of the final point, the journey counts as finished. */
  static readonly ARRIVAL_METRES = 40;

  /** Great-circle distance in metres. */
  distanceMetres(a: LatLng, b: LatLng): number {
    const earthRadius = 6_371_000;
    const dLat = this.toRadians(b.lat - a.lat);
    const dLng = this.toRadians(b.lng - a.lng);
    const h =
      Math.sin(dLat / 2) ** 2 +
      Math.cos(this.toRadians(a.lat)) * Math.cos(this.toRadians(b.lat)) * Math.sin(dLng / 2) ** 2;
    return earthRadius * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
  }

  /** Index of the route point closest to a position, and how far away it is. */
  nearestOnRoute(geometry: LatLng[], position: LatLng): { index: number; distance: number } {
    let index = 0;
    let best = Number.POSITIVE_INFINITY;
    for (let i = 0; i < geometry.length; i++) {
      const distance = this.distanceMetres(position, geometry[i]);
      if (distance < best) {
        best = distance;
        index = i;
      }
    }
    return { index, distance: best };
  }

  /**
   * Works out where the driver is in the journey.
   *
   * <p>Progress is tracked by the nearest point on the route line rather than by
   * distance to the next turn alone. That matters on a route that doubles back on
   * itself, where a driver can be physically near a manoeuvre they passed twenty
   * minutes ago.</p>
   */
  progress(geometry: LatLng[], steps: RouteStep[], position: LatLng): NavigationProgress | null {
    if (!geometry.length) {
      return null;
    }

    const nearest = this.nearestOnRoute(geometry, position);

    // The current step is the last one whose manoeuvre we have already reached.
    let stepIndex = 0;
    for (let i = 0; i < steps.length; i++) {
      if (steps[i].geometryIndex <= nearest.index) {
        stepIndex = i;
      } else {
        break;
      }
    }

    // Guide towards the NEXT manoeuvre, not the one just completed.
    const upcoming = Math.min(stepIndex + 1, steps.length - 1);
    const target = steps[upcoming];
    const distanceToManeuvre = target
      ? this.distanceMetres(position, { lat: target.latitude, lng: target.longitude })
      : 0;

    // Remaining distance: along the line from here to the end.
    let remainingMetres = 0;
    for (let i = nearest.index; i < geometry.length - 1; i++) {
      remainingMetres += this.distanceMetres(geometry[i], geometry[i + 1]);
    }

    // Remaining time: OSRM's own estimates for the steps not yet driven.
    let remainingSeconds = 0;
    for (let i = upcoming; i < steps.length; i++) {
      remainingSeconds += steps[i].durationSeconds;
    }

    const toDestination = this.distanceMetres(position, geometry[geometry.length - 1]);

    return {
      stepIndex: upcoming,
      distanceToManeuvre,
      remainingMetres,
      remainingSeconds,
      offRouteMetres: nearest.distance,
      nearestIndex: nearest.index,
      arrived: toDestination <= NavigationService.ARRIVAL_METRES
    };
  }

  /** "450 m" / "1.2 km" - the way a driver expects to read a distance. */
  formatDistance(metres: number): string {
    if (!Number.isFinite(metres)) {
      return '';
    }
    if (metres < 1000) {
      return `${Math.max(0, Math.round(metres / 10) * 10)} m`;
    }
    return `${(metres / 1000).toFixed(metres < 10_000 ? 1 : 0)} km`;
  }

  /** "8 min" / "1 h 24 min". */
  formatDuration(seconds: number): string {
    const minutes = Math.max(0, Math.round(seconds / 60));
    if (minutes < 60) {
      return `${minutes} min`;
    }
    return `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
  }

  /**
   * What to say out loud, or null when nothing new needs saying.
   *
   * <p>Announcements fire once each as the driver closes on a manoeuvre: a long
   * warning, a short one, and the turn itself. `alreadySaid` is what stops the
   * app repeating the same instruction on every GPS tick.</p>
   */
  announcement(step: RouteStep, distance: number, alreadySaid: Set<string>): string | null {
    const thresholds: Array<[number, string]> = [
      [1000, `In 1 kilometre, ${this.lowerFirst(step.instruction)}`],
      [400, `In 400 metres, ${this.lowerFirst(step.instruction)}`],
      [120, step.instruction]
    ];

    for (const [limit, phrase] of thresholds) {
      const key = `${step.geometryIndex}:${limit}`;
      if (distance <= limit && !alreadySaid.has(key)) {
        alreadySaid.add(key);
        return phrase;
      }
    }
    return null;
  }

  private lowerFirst(value: string): string {
    return value ? value.charAt(0).toLowerCase() + value.slice(1) : value;
  }

  private toRadians(degrees: number): number {
    return (degrees * Math.PI) / 180;
  }
}
