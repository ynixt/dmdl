import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  ElementRef,
  forwardRef,
  inject,
  input,
  OnInit,
  signal,
  untracked,
  ViewChild,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { json } from '@codemirror/lang-json';
import { syntaxHighlighting } from '@codemirror/language';
import { Annotation, Compartment, EditorState, Extension } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { codeEditorTheme, codeHighlightStyle, mustacheTags } from './mustache-highlighting';

export type CodeEditorLanguage = 'json' | 'text';

/** Marks changes written from the form model so they are not echoed back as user input. */
const external = Annotation.define<boolean>();

/**
 * Multi-line code field built on CodeMirror that highlights Mustache tags, plus JSON when
 * `language` is `json`. Works with `ngModel` and `formControlName`.
 *
 * The editable element is a `contenteditable`, so `<label for>` cannot target it. Give the label an
 * id and pass it as `labelledBy`; `inputId` becomes the id of the editable element. Tab moves focus
 * out of the field as in a textarea, so keyboard users are never trapped.
 */
@Component({
  selector: 'app-code-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => CodeEditorComponent),
      multi: true,
    },
  ],
  template: '<div #host class="code-editor-host" [class.invalid]="invalid()"></div>',
  styleUrl: './code-editor.component.scss',
})
export class CodeEditorComponent implements ControlValueAccessor, OnInit {
  readonly inputId = input.required<string>();
  readonly labelledBy = input<string | null>(null);
  readonly describedBy = input<string | null>(null);
  readonly invalid = input(false);
  readonly disabled = input(false);
  readonly language = input<CodeEditorLanguage>('text');

  @ViewChild('host', { static: true }) private readonly host!: ElementRef<HTMLElement>;

  private view?: EditorView;
  private pending = '';
  private readonly formDisabled = signal(false);
  private readonly languageSlot = new Compartment();
  private readonly attributesSlot = new Compartment();
  private readonly editableSlot = new Compartment();

  private onChange: (value: string) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.view?.destroy());
    effect(() => {
      const effects = [
        this.languageSlot.reconfigure(this.languageExtension(this.language())),
        this.attributesSlot.reconfigure(this.attributes()),
        this.editableSlot.reconfigure(this.editable()),
      ];
      untracked(() => this.view?.dispatch({ effects }));
    });
  }

  ngOnInit(): void {
    this.view = new EditorView({
      parent: this.host.nativeElement,
      state: EditorState.create({
        doc: this.pending,
        extensions: [
          history(),
          keymap.of([...defaultKeymap, ...historyKeymap]),
          EditorView.lineWrapping,
          syntaxHighlighting(codeHighlightStyle),
          mustacheTags,
          codeEditorTheme,
          this.languageSlot.of(this.languageExtension(this.language())),
          this.attributesSlot.of(this.attributes()),
          this.editableSlot.of(this.editable()),
          EditorView.updateListener.of((update) => {
            if (update.docChanged && !update.transactions.some((tr) => tr.annotation(external))) {
              this.onChange(update.state.doc.toString());
            }
          }),
          EditorView.domEventHandlers({ blur: () => this.onTouched() }),
        ],
      }),
    });
  }

  writeValue(value: string | null | undefined): void {
    const text = value ?? '';
    if (!this.view) {
      this.pending = text;
      return;
    }
    const current = this.view.state.doc.toString();
    if (current !== text) {
      this.view.dispatch({
        changes: { from: 0, to: current.length, insert: text },
        annotations: external.of(true),
      });
    }
  }

  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.formDisabled.set(isDisabled);
  }

  private languageExtension(language: CodeEditorLanguage): Extension {
    return language === 'json' ? json() : [];
  }

  private attributes(): Extension {
    const values: Record<string, string> = {
      id: this.inputId(),
      role: 'textbox',
      'aria-multiline': 'true',
      spellcheck: 'false',
      autocapitalize: 'off',
      autocorrect: 'off',
      translate: 'no',
    };
    const optional = {
      'aria-labelledby': this.labelledBy(),
      'aria-describedby': this.describedBy(),
      'aria-invalid': this.invalid() ? 'true' : null,
    };
    for (const [name, value] of Object.entries(optional)) {
      if (value) {
        values[name] = value;
      }
    }
    return EditorView.contentAttributes.of(values);
  }

  private editable(): Extension {
    const disabled = this.disabled() || this.formDisabled();
    return [EditorView.editable.of(!disabled), EditorState.readOnly.of(disabled)];
  }
}
