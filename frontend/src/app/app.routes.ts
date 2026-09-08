import { Routes } from '@angular/router';

import { authGuard } from './auth.guard';
import { HistoryPageComponent } from './history.page';
import { LoginPageComponent } from './login.page';
import { ProfilePageComponent } from './profile.page';
import { RouteAnalysisPageComponent } from './route-analysis.page';
import { SignupPageComponent } from './signup.page';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'analysis'
  },
  {
    path: 'analysis',
    component: RouteAnalysisPageComponent
  },
  {
    path: 'login',
    component: LoginPageComponent
  },
  {
    path: 'signup',
    component: SignupPageComponent
  },
  {
    path: 'profile',
    component: ProfilePageComponent,
    canActivate: [authGuard]
  },
  {
    path: 'history',
    component: HistoryPageComponent,
    canActivate: [authGuard]
  },
  {
    path: '**',
    redirectTo: 'analysis'
  }
];
