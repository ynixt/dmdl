import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { TooltipModule } from 'primeng/tooltip';

let nextId = 0;

/**
 * An "i" button that explains a setting. The tooltip opens on hover and on focus (a tap focuses the button on touch
 * screens), and the text is also linked with aria-describedby so assistive technology reads it without the tooltip.
 */
@Component({
  selector: 'app-info-tip',
  imports: [TranslatePipe, TooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      type="button"
      class="info-tip"
      [attr.aria-label]="'KZ.INFO_ABOUT' | translate: { field: field() | translate }"
      [attr.aria-describedby]="descriptionId"
      [pTooltip]="text() | translate"
      tooltipEvent="both"
      tooltipPosition="top"
    >
      <i class="pi pi-info-circle" aria-hidden="true"></i>
    </button>
    <span [id]="descriptionId" class="info-tip-description">{{ text() | translate }}</span>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
    }
    .info-tip {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0.15rem;
      border: 0;
      border-radius: 50%;
      background: transparent;
      color: var(--p-text-muted-color);
      cursor: help;
      line-height: 1;
    }
    .info-tip:hover,
    .info-tip:focus-visible {
      color: var(--p-primary-color);
    }
    .info-tip:focus-visible {
      outline: 2px solid var(--p-primary-color);
      outline-offset: 1px;
    }
    .info-tip-description {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip-path: inset(50%);
      white-space: nowrap;
    }
  `,
})
export class InfoTipComponent {
  /** Translation key of the explanation. */
  readonly text = input.required<string>();
  /** Translation key of the field name, used in the button's accessible name. */
  readonly field = input.required<string>();
  readonly descriptionId = `info-tip-${nextId++}`;
}
