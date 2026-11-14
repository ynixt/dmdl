import { Location } from '@angular/common';
import { Component, inject, input } from '@angular/core';
import { Params, Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { NavigationHistory } from './navigation-history.service';

@Component({
  selector: 'app-back-link',
  imports: [TranslatePipe],
  template: `<a [href]="href()" (click)="back($event)">
    <i class="pi pi-arrow-left" aria-hidden="true"></i> {{ 'KZ.BACK' | translate }}
  </a>`,
  styles: ['a { display: inline-flex; align-items: center; gap: .5rem; margin-bottom: 1rem; }'],
})
export class BackLinkComponent {
  readonly fallback = input.required<string[]>();
  readonly queryParams = input<Params>({});
  private readonly history = inject(NavigationHistory);
  private readonly location = inject(Location);
  private readonly router = inject(Router);

  href(): string {
    return this.router.serializeUrl(
      this.router.createUrlTree(this.fallback(), { queryParams: this.queryParams() }),
    );
  }

  back(event: MouseEvent): void {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey)
      return;
    event.preventDefault();
    if (this.history.hasPrevious()) this.location.back();
    else void this.router.navigate(this.fallback(), { queryParams: this.queryParams() });
  }
}
