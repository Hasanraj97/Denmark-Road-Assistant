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
  /** The item waiting for a second click to confirm deletion, if any. */
  protected readonly confirmDeleteId = signal<number | null>(null);

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

  protected deleteItem(id: number): void {
    this.subscriptions.add(
      this.historyService.deleteHistory(id).subscribe({
        next: () => {
          this.confirmDeleteId.set(null);
          this.historyItems.update((items) => items.filter((item) => item.id !== id));
          if (this.selectedHistory()?.id === id) {
            this.selectedHistory.set(null);
          }
        },
        error: (error: unknown) => this.errorMessage.set(describeError(error, 'The analysis could not be deleted.'))
      })
    );
  }

  /** Turns a model value between 0 and 1 into a word a driver can read. */
  protected levelWord(value: number | null | undefined): string {
    if (value === null || value === undefined) {
      return 'Unknown';
    }
    return value < 0.34 ? 'Low' : value < 0.67 ? 'Medium' : 'High';
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
