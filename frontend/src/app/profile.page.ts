import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';

import { AuthService } from './auth.service';
import { describeError } from './error-message';

@Component({
  selector: 'app-profile-page',
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './profile.page.html',
  styleUrl: './profile.page.css'
})
export class ProfilePageComponent implements OnInit, OnDestroy {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly successMessage = signal('');
  protected readonly errorMessage = signal('');
  protected readonly user = this.authService.user;

  protected readonly form = this.fb.nonNullable.group({
    fullName: ['', [Validators.required, Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email]],
    phone: ['', [Validators.required, Validators.maxLength(40)]],
    address: ['', [Validators.required, Validators.maxLength(255)]]
  });

  private readonly subscriptions = new Subscription();

  ngOnInit(): void {
    this.subscriptions.add(
      this.authService.getCurrentUser().subscribe({
        next: (user) => {
          this.form.patchValue({
            fullName: user.fullName,
            email: user.email,
            phone: user.phone,
            address: user.address
          });
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.errorMessage.set(describeError(error, 'Profile could not be loaded right now.'));
        }
      })
    );
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.errorMessage.set('');
    this.successMessage.set('');
    this.saving.set(true);

    // AuthService now stores the fresh token that comes back with the updated
    // user, so the header picks up a changed name or email immediately instead
    // of holding a token whose claims no longer match the account.
    this.subscriptions.add(
      this.authService.updateProfile(this.form.getRawValue()).subscribe({
        next: (response) => {
          this.form.patchValue({
            fullName: response.user.fullName,
            email: response.user.email,
            phone: response.user.phone,
            address: response.user.address
          });
          this.successMessage.set('Profile updated successfully.');
          this.saving.set(false);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.errorMessage.set(describeError(error, 'Profile update failed.'));
        }
      })
    );
  }
}
