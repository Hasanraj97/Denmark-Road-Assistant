import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Subscription } from 'rxjs';

import { AuthService } from './auth.service';

@Component({
  selector: 'app-root',
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App implements OnInit, OnDestroy {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly user = this.authService.user;
  protected readonly isLoggedIn = computed(() => this.authService.isLoggedIn());

  private bootstrap: Subscription | null = null;

  ngOnInit(): void {
    this.bootstrap = this.authService.bootstrapSession();
  }

  /**
   * Nothing in this app used to clean anything up. The session check started on
   * boot is cancelled here so a still-open request cannot write to a destroyed
   * component during a test teardown or a hot reload.
   */
  ngOnDestroy(): void {
    this.bootstrap?.unsubscribe();
    this.bootstrap = null;
  }

  protected logout(): void {
    this.authService.logout();
    void this.router.navigateByUrl('/login');
  }
}
