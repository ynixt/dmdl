/**
 * Reviewed semantic mapping used while migrating Material Symbols to PrimeIcons.
 *
 * Icon-only interactive controls must expose their translated action through
 * `aria-label`. Icons next to visible text, status copy, or headings are
 * decorative and must use `aria-hidden="true"`.
 */
export const MATERIAL_TO_PRIME_ICON = {
  add: 'pi-plus',
  arrow_back: 'pi-arrow-left',
  autorenew: 'pi-spinner',
  check: 'pi-check',
  check_circle: 'pi-check-circle',
  clear: 'pi-times',
  close: 'pi-times',
  cloud_upload: 'pi-cloud-upload',
  content_copy: 'pi-copy',
  delete: 'pi-trash',
  download: 'pi-download',
  download_done: 'pi-check-circle',
  drag_indicator: 'pi-bars',
  edit: 'pi-pencil',
  error: 'pi-times-circle',
  expand_less: 'pi-chevron-up',
  expand_more: 'pi-chevron-down',
  extension: 'pi-box',
  folder: 'pi-folder',
  help_outline: 'pi-question-circle',
  hourglass_top: 'pi-hourglass',
  image: 'pi-image',
  info: 'pi-info-circle',
  live_tv: 'pi-desktop',
  more_vert: 'pi-ellipsis-v',
  pause: 'pi-pause',
  person: 'pi-user',
  play_arrow: 'pi-play',
  play_circle: 'pi-play-circle',
  save: 'pi-save',
  search: 'pi-search',
  settings: 'pi-cog',
  storage: 'pi-database',
  swap_vert: 'pi-sort-alt',
  sync: 'pi-sync',
  video_library: 'pi-video',
  visibility: 'pi-eye',
  visibility_off: 'pi-eye-slash',
} as const;

export type MaterialIconName = keyof typeof MATERIAL_TO_PRIME_ICON;

export function primeIcon(materialIcon: MaterialIconName): string {
  return `pi ${MATERIAL_TO_PRIME_ICON[materialIcon]}`;
}
