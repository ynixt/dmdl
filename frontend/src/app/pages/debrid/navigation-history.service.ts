import { Location } from '@angular/common';
import { inject, Injectable } from '@angular/core';
import { NavigationEnd, NavigationStart, Router } from '@angular/router';

@Injectable({ providedIn: 'root' })
export class NavigationHistory {
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly entries: number[] = [];
  private index = -1;
  private restored: number | undefined;

  constructor() {
    this.router.events.subscribe((event) => {
      if (event instanceof NavigationStart) this.restored = event.restoredState?.navigationId;
      if (!(event instanceof NavigationEnd)) return;
      const state = this.location.getState() as { navigationId?: number };
      const navigation = this.router.currentNavigation();
      const restored = this.restored;
      const previous = restored == null ? -1 : this.entries.indexOf(restored);
      if (previous >= 0) {
        this.index = previous;
        this.entries[previous] = state.navigationId ?? event.id;
      } else if (navigation?.extras.replaceUrl && this.index >= 0) {
        this.entries[this.index] = state.navigationId ?? event.id;
      } else if (!navigation?.extras.skipLocationChange) {
        this.entries.splice(this.index + 1);
        this.entries.push(state.navigationId ?? event.id);
        this.index = this.entries.length - 1;
      }
    });
  }

  hasPrevious(): boolean {
    return this.index > 0;
  }
}
