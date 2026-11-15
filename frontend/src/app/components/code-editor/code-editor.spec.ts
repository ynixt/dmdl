import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { EditorView } from '@codemirror/view';
import { CodeEditorComponent, CodeEditorLanguage } from './code-editor.component';

@Component({
  imports: [FormsModule, CodeEditorComponent],
  template: `
    <span id="body-label">Body</span>
    <app-code-editor
      inputId="body"
      labelledBy="body-label"
      describedBy="body-hint"
      [language]="language()"
      [invalid]="invalid()"
      [ngModel]="value()"
      (ngModelChange)="value.set($event)"
    />
  `,
})
class ModelHost {
  readonly value = signal('{"title": "{{title}}"}');
  readonly language = signal<CodeEditorLanguage>('json');
  readonly invalid = signal(false);
}

@Component({
  imports: [ReactiveFormsModule, CodeEditorComponent],
  template: `<app-code-editor inputId="reactive" [formControl]="control" />`,
})
class ReactiveHost {
  readonly control = new FormControl('first', { nonNullable: true });
}

describe('Code editor', () => {
  async function render<T>(host: new () => T): Promise<ComponentFixture<T>> {
    const fixture = TestBed.createComponent(host);
    for (let pass = 0; pass < 2; pass++) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    return fixture;
  }

  const content = (fixture: ComponentFixture<unknown>, id: string) =>
    fixture.nativeElement.querySelector(`#${id}`) as HTMLElement;
  const view = (fixture: ComponentFixture<unknown>, id: string) =>
    EditorView.findFromDOM(content(fixture, id).closest('.cm-editor') as HTMLElement)!;

  it('shows the model value and highlights Mustache tags and sections', async () => {
    const fixture = await render(ModelHost);
    fixture.componentInstance.value.set('{{#error}}{{error}}{{/error}} {{! note }} {{{raw}}}');
    for (let pass = 0; pass < 2; pass++) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    const editor = content(fixture, 'body');
    expect(editor.textContent).toBe('{{#error}}{{error}}{{/error}} {{! note }} {{{raw}}}');
    const tags = [...editor.querySelectorAll('.cm-mustache')].map((tag) => [
      tag.textContent,
      tag.classList.contains('cm-mustache-section')
        ? 'section'
        : tag.classList.contains('cm-mustache-comment')
          ? 'comment'
          : 'variable',
    ]);
    expect(tags).toEqual([
      ['{{#error}}', 'section'],
      ['{{error}}', 'variable'],
      ['{{/error}}', 'section'],
      ['{{! note }}', 'comment'],
      ['{{{raw}}}', 'variable'],
    ]);
  });

  it('marks a tag missing its closing braces without swallowing the next tag', async () => {
    const fixture = await render(ModelHost);
    fixture.componentInstance.value.set('{"state": "{{download.state}", "title": "{{title}}"}');
    for (let pass = 0; pass < 2; pass++) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    const editor = content(fixture, 'body');
    expect(
      [...editor.querySelectorAll('.cm-mustache-unclosed')].map((tag) => tag.textContent),
    ).toEqual(['{{download.state']);
    expect([...editor.querySelectorAll('.cm-mustache')].map((tag) => tag.textContent)).toEqual([
      '{{title}}',
    ]);
  });

  it('writes edits back to the model and names the editable element', async () => {
    const fixture = await render(ModelHost);
    const editor = content(fixture, 'body');
    expect(editor.getAttribute('contenteditable')).toBe('true');
    expect(editor.getAttribute('role')).toBe('textbox');
    expect(editor.getAttribute('aria-multiline')).toBe('true');
    expect(editor.getAttribute('aria-labelledby')).toBe('body-label');
    expect(editor.getAttribute('aria-describedby')).toBe('body-hint');
    expect(editor.hasAttribute('aria-invalid')).toBe(false);
    expect(editor.getAttribute('spellcheck')).toBe('false');

    const cm = view(fixture, 'body');
    cm.dispatch({ changes: { from: cm.state.doc.length, insert: '\n' } });
    expect(fixture.componentInstance.value()).toBe('{"title": "{{title}}"}\n');

    fixture.componentInstance.invalid.set(true);
    fixture.detectChanges();
    expect(content(fixture, 'body').getAttribute('aria-invalid')).toBe('true');
  });

  it('switches between JSON and plain text highlighting', async () => {
    const fixture = await render(ModelHost);
    const highlighted = () =>
      [...content(fixture, 'body').querySelectorAll('span')].some(
        (span) => !span.classList.contains('cm-mustache') && span.className.startsWith('ͼ'),
      );
    expect(highlighted()).toBe(true);
    fixture.componentInstance.language.set('text');
    fixture.detectChanges();
    expect(highlighted()).toBe(false);
  });

  it('works with a reactive form control, marks touched on blur and follows disabled', async () => {
    const fixture = await render(ReactiveHost);
    const control = fixture.componentInstance.control;
    expect(content(fixture, 'reactive').textContent).toBe('first');

    control.setValue('second');
    fixture.detectChanges();
    expect(content(fixture, 'reactive').textContent).toBe('second');
    expect(control.dirty).toBe(false);

    const cm = view(fixture, 'reactive');
    cm.dispatch({ changes: { from: 0, insert: 'typed ' } });
    expect(control.value).toBe('typed second');
    expect(control.dirty).toBe(true);

    expect(control.touched).toBe(false);
    cm.contentDOM.dispatchEvent(new FocusEvent('blur'));
    expect(control.touched).toBe(true);

    control.disable();
    fixture.detectChanges();
    expect(content(fixture, 'reactive').getAttribute('contenteditable')).toBe('false');
  });
});
