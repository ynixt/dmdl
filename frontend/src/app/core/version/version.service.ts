import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';

export const REPOSITORY_URL = 'https://github.com/ynixt/dmdl';

/** What the label in the navbar says about the running build, and where it links (if anywhere). */
export interface BuildInfo {
  label: string;
  url: string | null;
}

const DEV: BuildInfo = { label: 'dev', url: null };
const LOCAL: BuildInfo = { label: 'local', url: null };
const IDENTIFIER = /^[0-9A-Za-z._-]+$/;

/**
 * Maps the content of /version.json (written into the image at build time) to the navbar label.
 * Anything that is not the expected shape is treated as "no identity file", i.e. a development build.
 */
export function toBuildInfo(body: unknown): BuildInfo {
  if (typeof body !== 'object' || body === null) return DEV;
  const { version, commit } = body as Record<string, unknown>;
  if (!isIdentifierOrNull(version) || !isIdentifierOrNull(commit)) return DEV;

  if (version) {
    return { label: version, url: `${REPOSITORY_URL}/releases/tag/${version}` };
  }
  if (commit) {
    return { label: commit.slice(0, 7), url: `${REPOSITORY_URL}/commit/${commit}` };
  }
  return LOCAL;
}

function isIdentifierOrNull(value: unknown): value is string | null {
  return value === null || (typeof value === 'string' && IDENTIFIER.test(value));
}

/** Reads the identity of the running build once, on first {@link load}. */
@Injectable({ providedIn: 'root' })
export class VersionService {
  private readonly http = inject(HttpClient);
  private readonly state = signal<BuildInfo | null>(null);
  private requested = false;

  /** The running build, or null until /version.json was read. */
  readonly buildInfo = this.state.asReadonly();

  load(): void {
    if (this.requested) return;
    this.requested = true;
    this.http.get<unknown>('/version.json').subscribe({
      next: (body) => this.state.set(toBuildInfo(body)),
      error: () => this.state.set(DEV),
    });
  }
}
