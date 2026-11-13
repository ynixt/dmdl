import { inject, Injectable } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';

@Injectable()
export class AppTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly translate = inject(TranslateService);
  private latestState?: RouterStateSnapshot;

  constructor() {
    super();

    this.translate.onLangChange.pipe(takeUntilDestroyed()).subscribe(() => {
      if (this.latestState) {
        this.updateTitle(this.latestState);
      }
    });
  }

  override updateTitle(routerState: RouterStateSnapshot): void {
    this.latestState = routerState;

    const routeTitleKey = this.buildTitle(routerState);
    const appName = this.translate.instant('APP.NAME');

    if (!routeTitleKey) {
      this.title.setTitle(appName);
      return;
    }

    const routeTitle = this.translate.instant(routeTitleKey);
    this.title.setTitle(`${routeTitle} | ${appName}`);
  }
}
