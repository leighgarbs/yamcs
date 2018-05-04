import { NgModule } from '@angular/core';
import { PreloadAllModules, RouterModule, Routes } from '@angular/router';
import { AuthGuard } from './core/guards/AuthGuard';
import { UnselectInstanceGuard } from './core/guards/UnselectInstanceGuard';
import { ForbiddenPage } from './core/pages/ForbiddenPage';
import { HomePage } from './core/pages/HomePage';
import { LoginPage } from './core/pages/LoginPage';
import { NotFoundPage } from './core/pages/NotFoundPage';
import { ProfilePage } from './core/pages/ProfilePage';

/*
 * Notes that the nested modules also have AuthGuards.
 * These will fully execute before other guards in those modules.
 */

const routes: Routes = [
  {
    path: '',
    children: [
      {
        path: '',
        pathMatch: 'full',
        component: HomePage,
        canActivate: [AuthGuard, UnselectInstanceGuard],
      },
      {
        path: 'monitor',
        loadChildren: 'src/app/monitor/MonitorModule#MonitorModule',
        canActivate: [AuthGuard],
      },
      {
        path: 'mdb',
        loadChildren: 'src/app/mdb/MdbModule#MdbModule',
        canActivate: [AuthGuard],
      },
      {
        path: 'system',
        loadChildren: 'src/app/system/SystemModule#SystemModule',
        canActivate: [AuthGuard],
      },
      {
        path: 'profile',
        component: ProfilePage,
        canActivate: [AuthGuard, UnselectInstanceGuard],
      },
      {
        path: 'login',
        component: LoginPage,
        canActivate: [UnselectInstanceGuard],
      },
      {
        path: '403',
        component: ForbiddenPage,
        canActivate: [UnselectInstanceGuard],
      },
      {
        path: '**',
        component: NotFoundPage,
        canActivate: [UnselectInstanceGuard],
      },
    ]
  },
];

@NgModule({
  imports: [
    RouterModule.forRoot(routes, {
      onSameUrlNavigation: 'reload',  // See MonitorPage.ts for documentation
      preloadingStrategy: PreloadAllModules,
    }),
  ],
  exports: [ RouterModule ],
})
export class AppRoutingModule { }
