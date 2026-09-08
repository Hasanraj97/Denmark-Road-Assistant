import { CommonModule } from '@angular/common';
import { Component, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { FeedbackResponse, HistoryService } from './history.service';

@Component({
  selector: 'app-feedback-card',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './feedback-card.component.html',
  styleUrl: './feedback-card.component.css'
})
export class FeedbackCardComponent {
  private readonly historyService = inject(HistoryService);

  readonly historyId = input<number | null>(null);
  readonly loggedIn = input(false);
  readonly existingFeedback = input<FeedbackResponse | null>(null);
  readonly title = input('Was this advisory helpful?');

  readonly saved = output<FeedbackResponse>();

  protected readonly selectedHelpful = signal<boolean | null>(null);
  protected readonly comment = signal('');
  protected readonly saving = signal(false);
  protected readonly successMessage = signal('');
  protected readonly errorMessage = signal('');

  constructor() {
    effect(() => {
      const feedback = this.existingFeedback();
      this.selectedHelpful.set(feedback ? feedback.helpful : null);
      this.comment.set(feedback?.comment ?? '');
      this.successMessage.set('');
      this.errorMessage.set('');
    });
  }

  protected choose(helpful: boolean): void {
    this.selectedHelpful.set(helpful);
  }

  protected saveFeedback(): void {
    if (!this.loggedIn()) {
      this.errorMessage.set('Please log in to save feedback.');
      return;
    }

    if (this.historyId() == null) {
      this.errorMessage.set('This analysis is not available for feedback yet.');
      return;
    }

    if (this.selectedHelpful() == null) {
      this.errorMessage.set('Please choose whether the advisory was helpful.');
      return;
    }

    this.saving.set(true);
    this.errorMessage.set('');
    this.successMessage.set('');

    this.historyService.saveFeedback(this.historyId()!, {
      helpful: this.selectedHelpful()!,
      comment: this.comment().trim()
    }).subscribe({
      next: (feedback) => {
        this.saving.set(false);
        this.successMessage.set('Feedback saved successfully.');
        this.saved.emit(feedback);
      },
      error: (error) => {
        this.saving.set(false);
        this.errorMessage.set(error?.error?.message ?? 'Feedback could not be saved right now.');
      }
    });
  }
}
