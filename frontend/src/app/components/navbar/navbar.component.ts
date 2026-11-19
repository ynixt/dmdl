import { Component, inject, ChangeDetectionStrategy, effect } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from '@openng/optimus-ui/button';
import { ToolbarModule } from '@openng/optimus-ui/toolbar';
import { AuthService } from '../../core/auth/auth.service';
import { REPOSITORY_URL, VersionService } from '../../core/version/version.service';

@Component({
  selector: 'app-navbar',
  imports: [RouterLink, RouterLinkActive, TranslatePipe, ButtonModule, ToolbarModule],
  changeDetection: ChangeDetectionStrategy.Default,
  templateUrl: './navbar.component.html',
})
export class NavbarComponent {
  readonly auth = inject(AuthService);
  readonly version = inject(VersionService);
  readonly repositoryUrl = REPOSITORY_URL;
  private readonly router = inject(Router);
  readonly offline = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects.split('?')[0] === '/offline'),
    ),
    { initialValue: this.router.url.split('?')[0] === '/offline' },
  );

  constructor() {
    // The version is only shown to signed-in users, so visitors never request it.
    effect(() => {
      if (this.auth.isAuthenticated()) this.version.load();
    });
  }

  async logout(): Promise<void> {
    await this.auth.logout();
    await this.router.navigate(['/login']);
  }
}
