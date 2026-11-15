import { HighlightStyle } from '@codemirror/language';
import { RangeSetBuilder } from '@codemirror/state';
import { Decoration, DecorationSet, EditorView, ViewPlugin, ViewUpdate } from '@codemirror/view';
import { tags } from '@lezer/highlight';

/**
 * Triple and double mustaches (variables, sections, inverted sections, closers and comments). Tag
 * content cannot contain braces, so a tag missing a closing brace never swallows the next one. An
 * opening `{{` that does not form a tag is matched last and marked as an error up to its content.
 */
const MUSTACHE_TAG = /\{\{\{[^{}]*\}\}\}|\{\{[^{}]*\}\}|\{\{\{?[^{}]*/g;

const marks = {
  variable: Decoration.mark({ class: 'cm-mustache cm-mustache-variable' }),
  section: Decoration.mark({ class: 'cm-mustache cm-mustache-section' }),
  comment: Decoration.mark({ class: 'cm-mustache cm-mustache-comment' }),
  unclosed: Decoration.mark({ class: 'cm-mustache-unclosed' }),
};

function markFor(tag: string): Decoration {
  if (!tag.endsWith('}}')) {
    return marks.unclosed;
  }
  const sigil = tag.replace(/^\{\{\{?\s*/, '').charAt(0);
  if (sigil === '!') {
    return marks.comment;
  }
  return sigil !== '' && '#^/'.includes(sigil) ? marks.section : marks.variable;
}

function decorate(view: EditorView): DecorationSet {
  const builder = new RangeSetBuilder<Decoration>();
  for (const { from, to } of view.visibleRanges) {
    const text = view.state.doc.sliceString(from, to);
    for (const match of text.matchAll(MUSTACHE_TAG)) {
      const start = from + match.index;
      builder.add(start, start + match[0].length, markFor(match[0]));
    }
  }
  return builder.finish();
}

/**
 * Highlights Mustache tags on top of whatever language the body uses. Tags are found in the visible
 * text only, which is plenty for templates of a few lines.
 */
export const mustacheTags = ViewPlugin.fromClass(
  class {
    decorations: DecorationSet;

    constructor(view: EditorView) {
      this.decorations = decorate(view);
    }

    update(update: ViewUpdate): void {
      if (update.docChanged || update.viewportChanged) {
        this.decorations = decorate(update.view);
      }
    }
  },
  { decorations: (plugin) => plugin.decorations },
);

/** Token colours come from CSS variables so the editor follows the light and dark themes. */
export const codeHighlightStyle = HighlightStyle.define([
  { tag: tags.propertyName, color: 'var(--code-property)' },
  { tag: tags.string, color: 'var(--code-string)' },
  { tag: [tags.number, tags.bool, tags.null], color: 'var(--code-literal)' },
  {
    tag: [tags.punctuation, tags.separator, tags.brace, tags.squareBracket],
    color: 'var(--code-punctuation)',
  },
]);

export const codeEditorTheme = EditorView.theme({
  '&': {
    border: '1px solid var(--p-inputtext-border-color)',
    borderRadius: 'var(--p-inputtext-border-radius)',
    background: 'var(--p-inputtext-background)',
    color: 'var(--p-inputtext-color)',
    fontSize: '0.8rem',
  },
  '&.cm-focused': {
    outline: '1px solid var(--p-inputtext-focus-border-color)',
    borderColor: 'var(--p-inputtext-focus-border-color)',
  },
  '.cm-scroller': {
    fontFamily: 'monospace',
    lineHeight: '1.5',
    maxHeight: '24rem',
    overflow: 'auto',
  },
  '.cm-content': {
    minHeight: '10rem',
    padding: '0.75rem 0',
    caretColor: 'var(--p-inputtext-color)',
  },
  '.cm-line': { padding: '0 0.75rem' },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--p-inputtext-color)' },
  '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
    background: 'var(--code-selection)',
  },
  '.cm-mustache': {
    borderRadius: '3px',
    background: 'var(--code-mustache-background)',
    color: 'var(--code-mustache)',
  },
  '.cm-mustache *': { color: 'inherit' },
  '.cm-mustache-section': { color: 'var(--code-mustache-section)', fontWeight: '600' },
  '.cm-mustache-unclosed': {
    textDecoration: 'underline wavy var(--p-red-500)',
    textUnderlineOffset: '3px',
  },
  '.cm-mustache-comment': {
    color: 'var(--code-punctuation)',
    fontStyle: 'italic',
    background: 'none',
  },
});
