import { CommonModule } from '@angular/common';
import { Component, OnDestroy, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';

import { AuthService } from './auth.service';
import { describeError } from './error-message';

@Component({
  selector: 'app-login-page',
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './login.page.html',
  styleUrl: './login.page.css'
})
export class LoginPageComponent implements OnDestroy {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly loading = signal(false);
  protected readonly errorMessage = signal('');

  /**
   * Set by the auth interceptor when it clears a dead session, so the user is
   * told why they are suddenly looking at the login page instead of being
   * dumped here with no explanation.
   */
  protected readonly sessionExpired = signal(
    this.route.snapshot.queryParamMap.get('reason') === 'session-expired'
  );

  protected readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]]
  });

  private loginSubscription: Subscription | null = null;

  ngOnDestroy(): void {
    this.loginSubscription?.unsubscribe();
    this.loginSubscription = null;
  }

  protected login(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.errorMessage.set('');
    this.sessionExpired.set(false);
    this.loading.set(true);

    this.loginSubscription?.unsubscribe();
    this.loginSubscription = this.authService.login(this.form.getRawValue()).subscribe({
      next: () => {
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/analysis';
        void this.router.navigateByUrl(returnUrl);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.errorMessage.set(
          describeError(error, 'Login failed. Please check your credentials.')
        );
      },
      complete: () => this.loading.set(false)
    });
  }

  protected showError(controlName: 'email' | 'password'): boolean {
    const control = this.form.controls[controlName];
    return !!(control.invalid && (control.dirty || control.touched));
  }
}
