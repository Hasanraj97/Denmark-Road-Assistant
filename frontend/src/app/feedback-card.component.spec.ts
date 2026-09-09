import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { FeedbackCardComponent } from './feedback-card.component';
import { FeedbackResponse } from './history.service';

/**
 * Mirrors how both real callers use the card: the saved feedback is fed straight
 * back in through [existingFeedback]. That echo is what used to erase the
 * success message before anyone could read it.
 */
@Component({
  imports: [FeedbackCardComponent],
  template: `
    <app-feedback-card
      [loggedIn]="true"
      [historyId]="historyId()"
      [existingFeedback]="feedback()"
      (saved)="feedback.set($event)"
    />
  `
})
class HostComponent {
  readonly historyId = signal<number | null>(7);
  readonly feedback = signal<FeedbackResponse | null>(null);
}

describe('FeedbackCardComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    }).compileComponents();

    fixture = TestBed.createComponent(HostComponent);
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => httpMock.verify());

  function click(text: string): void {
    const button = Array.from(
      fixture.nativeElement.querySelectorAll('button')
    ).find((candidate) => (candidate as HTMLButtonElement).textContent?.includes(text));
    (button as HTMLButtonElement).click();
  }

  async function saveAndAnswer(): Promise<void> {
    click('Yes, helpful');
    fixture.detectChanges();
    click('Save feedback');

    httpMock.expectOne((request) => request.url.endsWith('/api/health')).flush({ status: 'UP' });
    await fixture.whenStable();

    httpMock.expectOne((request) => request.url.endsWith('/api/history/7/feedback')).flush({
      id: 12,
      historyId: 7,
      helpful: true,
      comment: '',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z'
    } satisfies FeedbackResponse);

    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('keeps the success message after the saved feedback is fed back in', async () => {
    await saveAndAnswer();

    expect(fixture.nativeElement.textContent).toContain('Feedback saved successfully.');
  });

  it('clears the message once the card is pointed at a different analysis', async () => {
    await saveAndAnswer();
    expect(fixture.nativeElement.textContent).toContain('Feedback saved successfully.');

    fixture.componentInstance.historyId.set(9);
    fixture.componentInstance.feedback.set(null);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('Feedback saved successfully.');
  });
});
