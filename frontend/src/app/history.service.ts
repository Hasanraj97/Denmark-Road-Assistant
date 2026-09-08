import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, from, switchMap } from 'rxjs';

import { BackendUrlService } from './backend-url.service';

export interface FeedbackPayload {
  helpful: boolean;
  comment: string;
}

export interface FeedbackResponse {
  id: number;
  historyId: number;
  helpful: boolean;
  comment: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface HistoryItem {
  id: number;
  originLabel: string;
  destinationLabel: string;
  distanceKm: number;
  durationMinutes: number;
  selectedDepartureTime: string | null;
  predictedCondition: string;
  confidence: number;
  leaveEarlyMinutes: number;
  advisoryText: string;
  createdAt: string;
  feedback: FeedbackResponse | null;
}

export interface HistoryDetail extends HistoryItem {
  trafficLevel: number;
  weatherRisk: number;
  accidentRisk: number;
  constructionRisk: number;
  vehicleLoadFactor: number;
  routeSummary: string | null;
  featureSnapshot: string | null;
}

@Injectable({ providedIn: 'root' })
export class HistoryService {
  private readonly http = inject(HttpClient);
  private readonly backendUrl = inject(BackendUrlService);

  getHistory(): Observable<HistoryItem[]> {
    return from(this.backendUrl.ensureConnected()).pipe(
      switchMap(() => this.http.get<HistoryItem[]>(this.backendUrl.apiUrl('/history')))
    );
  }

  getHistoryDetail(id: number): Observable<HistoryDetail> {
    return from(this.backendUrl.ensureConnected()).pipe(
      switchMap(() => this.http.get<HistoryDetail>(this.backendUrl.apiUrl(`/history/${id}`)))
    );
  }

  getFeedback(historyId: number): Observable<FeedbackResponse> {
    return from(this.backendUrl.ensureConnected()).pipe(
      switchMap(() => this.http.get<FeedbackResponse>(this.backendUrl.apiUrl(`/history/${historyId}/feedback`)))
    );
  }

  saveFeedback(historyId: number, payload: FeedbackPayload): Observable<FeedbackResponse> {
    return from(this.backendUrl.ensureConnected()).pipe(
      switchMap(() =>
        this.http.post<FeedbackResponse>(this.backendUrl.apiUrl(`/history/${historyId}/feedback`), payload)
      )
    );
  }
}
