import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../environments/environment';

export type Provider = 'TORBOX' | 'ALL_DEBRID' | 'REAL_DEBRID';
export type Category = 'MOVIE' | 'SERIES' | 'ANIME';
export interface Identity {
  sourceId: string;
  sourceRevision: number;
  resourceType: string;
  contentId: string;
  videoId?: string;
  mappedIds?: Record<string, string>;
}
export interface Video {
  id: string;
  season: number;
  episode: number;
  title?: string;
  thumbnail?: string;
  overview?: string;
  released?: string;
  /** Episode score; its source is never known. */
  rating?: string;
}
/** Where a title's rating comes from; `null` when the addon does not say. */
export type RatingSource = 'IMDB' | 'KITSU';
export interface Metadata {
  identity: Identity;
  title: string;
  year?: number;
  posterUrl?: string;
  description?: string;
  videos: Video[];
  background?: string;
  /** Presentation classification (Kitsu-verified for shows); `identity.resourceType` is only for addon calls. */
  category?: Category;
  /** Season number → poster URL, when the metadata addon provides them. */
  seasonPosters?: Record<string, string>;
  rating?: string;
  ratingSource?: RatingSource | null;
}
export interface Content {
  identity: Identity;
  category: Category;
  title: string;
  year?: number;
  season?: number;
  episode?: number;
  episodeTitle?: string;
  posterUrl?: string;
}
export interface StreamBadge {
  name: string;
  imageURL: string;
  tagColor: string;
  tagStyle: string;
  textColor: string;
  borderColor: string;
}
export interface Release {
  cacheStatus?: 'CACHED' | 'UNCACHED' | 'UNKNOWN';
  badges?: StreamBadge[];
  title: string;
  provenance: {
    addonId: string;
    addonRevision: number;
    addonName: string;
    sourcePosition: number;
    resultPosition: number;
    boundProvider?: Provider;
  };
  reference: { type: string; value: string };
}
export interface ReleaseGroup {
  addonId: string;
  addonName: string;
  position: number;
  outcome: string;
  releases: Release[];
  message?: string;
}
export interface ReleasePage {
  group: ReleaseGroup;
  page: number;
  pageSize: number;
  total: number;
  hasNext: boolean;
}
export interface CatalogExtra {
  name: string;
  isRequired: boolean;
  options?: string[] | null;
  default?: string | null;
}
export interface Catalog {
  id: string;
  type: string;
  name: string;
  searchable: boolean;
  browsable: boolean;
  pageable: boolean;
  extra?: CatalogExtra[] | null;
}
export interface Addon {
  id: string;
  name: string;
  manifestUrl: string;
  revision: number;
  manifest: {
    name: string;
    catalogs: Catalog[];
    resources?: { name: string; types?: string[]; idPrefixes?: string[] }[];
  };
  enabled: boolean;
  position: number;
}
export interface Addons {
  metadata?: Addon;
  search: Addon[];
}
export interface Organization {
  revision: number;
  categories: Record<Category, CategoryOrganization>;
}
export interface StorageDestination {
  id: string;
  name: string;
  path: string;
}
export interface CategoryOrganization {
  folderTemplate: string;
  destinations: StorageDestination[];
  defaultDestinationId: string | null;
}
export interface StorageDirectories {
  currentPath: string | null;
  parentPath: string | null;
  roots: string[];
  directories: { name: string; path: string }[];
}
export interface SetupSearch {
  manifestUrl: string;
  name?: string;
  enabled: boolean;
}
export interface SearchAddonInput extends SetupSearch {
  id?: string;
}
export interface OnboardingDraft {
  settings: {
    locale: string;
    streamBadgeJson?: string;
    maxConcurrentDownloads: number;
    organization: Organization;
    transfer: Transfer;
    jellyfin: { enabled: boolean; url?: string; token?: string };
  };
  metadataUrl: string;
  metadataName: string;
  search: SetupSearch[];
}
export interface SetupValidation {
  valid: boolean;
  errors: Record<string, string>;
}
export interface Transfer {
  split: number;
  maxConnectionPerServer: number;
  minSplitSizeMib: number;
  maxTries: number;
  retryWaitSeconds: number;
  fileAllocation: string;
  ipv6Enabled: boolean;
  lowestSpeedLimitEnabled?: boolean;
  lowestSpeedLimitKib?: number;
  diskCacheMib?: number;
  timeoutSeconds?: number;
  maxFileNotFound?: number;
  maxConcurrentPerDebrid?: number;
}
export interface Settings {
  username: string;
  locale: string;
  streamBadgeJson?: string;
  maxConcurrentDownloads: number;
  organization: Organization;
  transfer: Transfer;
  storageRoot: string;
  onboardingCompleted: boolean;
  configurationRevision: number;
  jellyfin: { enabled: boolean; url?: string | null; token?: string | null };
}
export interface Download {
  id: string;
  content: Content;
  provider?: Provider | null;
  state: string;
  organization: {
    storageRoot: string;
    destinationId: string;
    destinationName: string;
  };
  createdAt: string;
  startedAt?: string | null;
  completedAt?: string | null;
  error?: string;
  progress: {
    completedBytes: number;
    totalBytes?: number;
    speedBytesPerSecond: number;
    connections?: number | null;
    connectionSpeedsBytesPerSecond?: number[] | null;
    allocation?: {
      allocatedBytes: number;
      totalBytes: number;
      secondsRemaining?: number | null;
    } | null;
  };
  execution: {
    generation: number;
    availableFiles: { id: string; name: string; size: number }[];
    finalPath?: string;
    originalFileName?: string;
    cancellationCleanupCompleted?: boolean;
    fileAllocationOverride?: string;
  };
}
export interface DownloadStatus {
  request: Download;
  notification?: { state: string; error?: string; attempts: number };
}
export interface DownloadEvent {
  id: string;
  state: string;
  occurredAt: string;
  message?: string;
}
export interface SpeedSample {
  at: string;
  speedBytesPerSecond: number;
  connections: number;
}
export interface Setup {
  missing: string[];
  ready: boolean;
  completed: boolean;
}

@Injectable({ providedIn: 'root' })
export class DebridApi {
  private readonly http = inject(HttpClient);
  readonly base = environment.apiBaseUrl;
  get<T>(path: string): Promise<T> {
    return firstValueFrom(this.http.get<T>(this.base + path));
  }
  post<T>(path: string, body: unknown = {}): Promise<T> {
    return firstValueFrom(this.http.post<T>(this.base + path, body));
  }
  put<T>(path: string, body: unknown): Promise<T> {
    return firstValueFrom(this.http.put<T>(this.base + path, body));
  }
  delete(path: string): Promise<unknown> {
    return firstValueFrom(this.http.delete(this.base + path));
  }
}
