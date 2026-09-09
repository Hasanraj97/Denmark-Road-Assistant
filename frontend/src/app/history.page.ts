import { CommonModule, DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';

import { describeError } from './error-message';
import { FeedbackCardComponent } from './feedback-card.component';
import { FeedbackResponse, HistoryDetail, HistoryItem, HistoryService } from './history.service';

@Component({
  selector: 'app-history-page',
  imports: [CommonModule, DatePipe, FeedbackCardComponent],
  templateUrl: './history.page.html',
  styleUrl: './history.page.css'
})
export class HistoryPageComponent implements OnInit, OnDestroy {
  private readonly historyService = inject(HistoryService);

  protected readonly loading = signal(true);
  protected readonly detailLoading = signal(false);
  protected readonly errorMessage = signal('');
  protected readonly historyItems = signal<HistoryItem[]>([]);
  protected readonly selectedHistory = signal<HistoryDetail | null>(null);

  private readonly subscriptions = new Subscription();

  ngOnInit(): void {
    this.loadHistory();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  protected loadHistory(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    this.subscriptions.add(
      this.historyService.getHistory().subscribe({
        next: (items) => {
          this.historyItems.set(items);
          this.loading.set(false);
          if (items.length > 0) {
            this.openDetails(items[0].id);
          }
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.errorMessage.set(describeError(error, 'History could not be loaded right now.'));
        }
      })
    );
  }

  protected openDetails(id: number): void {
    this.detailLoading.set(true);
    this.errorMessage.set('');

    this.subscriptions.add(
      this.historyService.getHistoryDetail(id).subscribe({
        next: (item) => {
          this.selectedHistory.set(item);
          this.detailLoading.set(false);
        },
        error: (error: unknown) => {
          this.detailLoading.set(false);
          this.errorMessage.set(describeError(error, 'History details could not be loaded.'));
        }
      })
    );
  }

  protected advisoryPreview(text: string): string {
    return text.length > 160 ? `${text.slice(0, 157)}...` : text;
  }

  protected confidencePercent(value: number): number {
    return Math.round(value * 100);
  }

  protected onFeedbackSaved(feedback: FeedbackResponse): void {
    const selected = this.selectedHistory();
    if (selected && selected.id === feedback.historyId) {
      this.selectedHistory.set({
        ...selected,
        feedback
      });
    }

    this.historyItems.update((items) =>
      items.map((item) =>
        item.id === feedback.historyId
          ? {
              ...item,
              feedback
            }
          : item
      )
    );
  }
}
