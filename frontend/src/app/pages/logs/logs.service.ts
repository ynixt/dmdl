import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { environment } from '../../../environments/environment';

export const logLevels = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'UNKNOWN'] as const;
export type LogLevel = (typeof logLevels)[number];
export type LogSource = 'backend' | 'nginx' | 'aria2';

export interface LogRecord {
  id: string;
  timestamp: string;
  source: LogSource;
  severity: LogLevel;
  message: string;
  truncated: boolean;
}

export interface LogFilters {
  source: LogSource | 'all';
  text: string;
  levels: LogLevel[];
}

export interface LogPage {
  records: LogRecord[];
  cursor: string;
  olderCursor: string | null;
  hasMore: boolean;
  gap: 'reset' | 'retention' | null;
  sources: { source: LogSource; state: string; oldestTimestamp: string | null }[];
}

@Injectable({ providedIn: 'root' })
export class LogsService {
  private readonly http = inject(HttpClient);

  history(filters: LogFilters, before?: string) {
    let params = this.params(filters);
    if (before) params = params.set('before', before);
    return this.http.get<LogPage>(`${environment.apiBaseUrl}/logs`, { params });
  }

  events(filters: LogFilters, cursor: string) {
    const params = this.params(filters).set('cursor', cursor);
    return new EventSource(`${environment.apiBaseUrl}/logs/events?${params}`, {
      withCredentials: true,
    });
  }

  private params(filters: LogFilters) {
    let params = new HttpParams().set('source', filters.source).set('text', filters.text);
    for (const level of filters.levels) params = params.append('levels', level);
    return params;
  }
}
