import { Webhook, WebhookEvent } from '../../services/debrid-api.service';

export const WEBHOOK_EVENTS: readonly WebhookEvent[] = [
  'download.requested',
  'download.transferring',
  'download.needs_action',
  'download.paused',
  'download.failed',
  'download.cancelled',
  'download.completed',
];
export const WEBHOOK_METHODS = ['POST', 'PUT', 'PATCH', 'GET', 'DELETE'];

export type WebhookPresetId = 'blank' | 'jellyfin' | 'homeAssistant' | 'ntfy';

/** Translation key of an event; dotted event names cannot be used inside translation keys. */
export function webhookEventLabel(event: WebhookEvent | string): string {
  return 'KZ.WEBHOOK_EVENT_' + event.replace(/^download\./, '').toUpperCase();
}

/** Variables every template receives; event data is empty when it does not apply. */
export const WEBHOOK_VARIABLES = [
  'event',
  'trigger',
  'manual',
  'timestamp',
  'download.id',
  'download.state',
  'title',
  'category',
  'year',
  'season',
  'episode',
  'episodeTitle',
  'posterUrl',
  'provider',
  'path',
  'size',
  'error',
] as const;

const HOME_ASSISTANT_BODY = `{
  "event": "{{event}}",
  "trigger": "{{trigger}}",
  "id": "{{download.id}}",
  "state": "{{download.state}}",
  "title": "{{title}}",
  "category": "{{category}}",
  "season": "{{season}}",
  "episode": "{{episode}}",
  "episodeTitle": "{{episodeTitle}}",
  "posterUrl": "{{posterUrl}}",
  "path": "{{path}}",
  "error": "{{error}}"
}`;

/**
 * Presets only fill the form. The saved webhook keeps no link to them. Placeholders in capitals are
 * values the user must replace.
 */
export function webhookPreset(preset: WebhookPresetId): Webhook {
  const id = crypto.randomUUID();
  switch (preset) {
    case 'jellyfin':
      return {
        id,
        name: 'Jellyfin',
        method: 'POST',
        url: 'http://JELLYFIN_HOST:8096/Library/Refresh',
        headers: [{ name: 'Authorization', value: 'MediaBrowser Token="JELLYFIN_API_TOKEN"' }],
        bodyMode: 'NONE',
        body: '',
        events: ['download.completed'],
        manual: true,
        enabled: true,
      };
    case 'homeAssistant':
      return {
        id,
        name: 'Home Assistant',
        method: 'POST',
        url: 'http://homeassistant.local:8123/api/webhook/WEBHOOK_ID',
        headers: [{ name: 'Content-Type', value: 'application/json' }],
        bodyMode: 'TEMPLATE',
        body: HOME_ASSISTANT_BODY,
        events: ['download.completed', 'download.failed', 'download.needs_action'],
        manual: true,
        enabled: true,
      };
    case 'ntfy':
      return {
        id,
        name: 'ntfy',
        method: 'POST',
        // The title goes in the query so non-ASCII titles survive URL encoding.
        url: 'https://ntfy.sh/TOPIC?title={{title}}',
        headers: [{ name: 'Content-Type', value: 'text/plain' }],
        bodyMode: 'TEMPLATE',
        body: '{{download.state}}{{#error}}: {{error}}{{/error}}{{#path}}\n{{path}}{{/path}}',
        events: ['download.completed', 'download.failed'],
        manual: true,
        enabled: true,
      };
    default:
      return {
        id,
        name: '',
        method: 'POST',
        url: '',
        headers: [],
        bodyMode: 'NONE',
        body: '',
        events: [],
        manual: true,
        enabled: true,
      };
  }
}
