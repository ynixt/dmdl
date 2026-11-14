import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { ApiAvailabilityService } from '../../core/api-availability.service';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-offline-page',
  imports: [TranslatePipe, ButtonModule, CardModule, ProgressSpinnerModule],
  templateUrl: './offline.page.html',
  styleUrl: './offline.page.scss',
  changeDetection: ChangeDetectionStrategy.Default,
})
export class OfflinePageComponent implements OnInit {
  private readonly availability = inject(ApiAvailabilityService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly route = inject(ActivatedRoute);
  private countdown?: ReturnType<typeof setInterval>;
  private nextDelay = 1;
  readonly checking = signal(false);
  readonly remaining = signal(0);
  readonly messageKey = computed(() => {
    switch (this.availability.reason()) {
      case 'starting':
        return 'PAGES.OFFLINE.STARTING';
      case 'not-responding':
        return 'PAGES.OFFLINE.NOT_RESPONDING';
      default:
        return this.availability.reached()
          ? 'PAGES.OFFLINE.CONNECTION_LOST'
          : 'PAGES.OFFLINE.UNREACHABLE';
    }
  });

  constructor() {
    this.destroy.onDestroy(() => clearInterval(this.countdown));
  }

  ngOnInit(): void {
    this.check();
  }

  retry(): void {
    this.nextDelay = 1;
    this.check();
  }

  private check(): void {
    if (this.checking()) return;
    clearInterval(this.countdown);
    this.remaining.set(0);
    this.checking.set(true);
    this.availability
      .probe()
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: () => {
          this.checking.set(false);
          this.auth.invalidateSession();
          void this.router.navigateByUrl(this.returnUrl());
        },
        error: (error: unknown) => {
          this.checking.set(false);
          // An HTTP application error still means the API answered. Let the normal guards handle it.
          if (!this.availability.classify(error)) {
            this.auth.invalidateSession();
            void this.router.navigateByUrl(this.returnUrl());
            return;
          }
          this.schedule();
        },
      });
  }

  private schedule(): void {
    this.remaining.set(this.nextDelay);
    this.nextDelay = Math.min(this.nextDelay * 2, 10);
    this.countdown = setInterval(() => {
      this.remaining.update((seconds) => seconds - 1);
      if (this.remaining() === 0) this.check();
    }, 1000);
  }

  private returnUrl(): string {
    const url = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/';
    if (!/^\/(?![/\\])/.test(url) || /[\\\r\n\t]/.test(url) || url.split(/[?#]/)[0] === '/offline')
      return '/';
    return url;
  }
}
