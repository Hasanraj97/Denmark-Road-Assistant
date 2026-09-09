import { CommonModule } from '@angular/common';
import { Component, OnDestroy, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';

import { describeError } from './error-message';
import { FeedbackResponse, HistoryService } from './history.service';

@Component({
  selector: 'app-feedback-card',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './feedback-card.component.html',
  styleUrl: './feedback-card.component.css'
})
export class FeedbackCardComponent implements OnDestroy {
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

  /**
   * Which analysis the form is currently showing. Used to tell a real change of
   * subject apart from the echo of our own save.
   */
  private syncedHistoryId: number | null = null;
  private hasSynced = false;

  private saveSubscription: Subscription | null = null;

  constructor() {
    // The success message used to be wiped the instant it appeared. Saving
    // emits (saved), the parent feeds the new FeedbackResponse straight back in
    // through [existingFeedback], that input change re-runs this effect, and the
    // effect unconditionally cleared successMessage - so "Feedback saved
    // successfully." was set and erased within the same tick and the user never
    // saw any confirmation that their feedback had been stored.
    //
    // The messages belong to one analysis, so they are only cleared when the
    // card is actually pointed at a different analysis. A new value for the same
    // history id - which is exactly what our own save produces - refreshes the
    // form fields and leaves the confirmation standing.
    effect(() => {
      const feedback = this.existingFeedback();
      const historyId = this.historyId();

      untracked(() => {
        this.selectedHelpful.set(feedback ? feedback.helpful : null);
        this.comment.set(feedback?.comment ?? '');

        const changedSubject = !this.hasSynced || historyId !== this.syncedHistoryId;
        this.hasSynced = true;
        this.syncedHistoryId = historyId;

        if (changedSubject) {
          this.successMessage.set('');
          this.errorMessage.set('');
        }
      });
    });
  }

  ngOnDestroy(): void {
    this.saveSubscription?.unsubscribe();
    this.saveSubscription = null;
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

    this.saveSubscription?.unsubscribe();
    this.saveSubscription = this.historyService
      .saveFeedback(this.historyId()!, {
        helpful: this.selectedHelpful()!,
        comment: this.comment().trim()
      })
      .subscribe({
        next: (feedback) => {
          this.saving.set(false);
          this.successMessage.set('Feedback saved successfully.');
          this.saved.emit(feedback);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.errorMessage.set(describeError(error, 'Feedback could not be saved right now.'));
        }
      });
  }
}
