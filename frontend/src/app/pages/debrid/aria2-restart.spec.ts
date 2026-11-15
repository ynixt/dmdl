import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { AppConfirmationService } from '../../core/ui/confirmation.service';
import { Aria2Status, DebridApi, DownloadStatus } from '../../services/debrid-api.service';
import { Aria2RestartComponent, RESTART_POLL_MS } from './aria2-restart.component';

describe('aria2 restart', () => {
  let fixture: ComponentFixture<Aria2RestartComponent>;
  let status: Aria2Status;
  let downloads: Pick<DownloadStatus['request'], 'state'>[];
  const api = { base: '/api', get: vi.fn(), post: vi.fn() };
  const confirmations = { confirm: vi.fn() };

  const idle = (overrides: Partial<Aria2Status> = {}): Aria2Status => ({
    restartState: 'IDLE',
    runningAutoSaveIntervalSeconds: 60,
    savedAutoSaveIntervalSeconds: 60,
    restartPending: false,
    ...overrides,
  });

  async function render(): Promise<HTMLElement> {
    fixture = TestBed.createComponent(Aria2RestartComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const button = () => fixture.nativeElement.querySelector('button') as HTMLButtonElement;

  beforeEach(() => {
    vi.resetAllMocks();
    vi.useFakeTimers();
    status = idle();
    downloads = [];
    api.get.mockImplementation(async (path: string) =>
      path === '/aria2/status'
        ? structuredClone(status)
        : downloads.map((request) => ({ request })),
    );
    api.post.mockImplementation(async () => {
      status = { ...status, restartState: 'RESTARTING' };
      return structuredClone(status);
    });
    TestBed.configureTestingModule({
      providers: [
        provideTranslateService(),
        { provide: DebridApi, useValue: api },
        { provide: AppConfirmationService, useValue: confirmations },
      ],
    });
  });

  afterEach(() => {
    fixture?.destroy();
    vi.useRealTimers();
  });

  it('reads Restarting… and stays disabled until the backend reports aria2 is back', async () => {
    await render();
    expect(button().textContent).toContain('KZ.ARIA2_RESTART');
    expect(button().disabled).toBe(false);
    await fixture.componentInstance.restart();
    fixture.detectChanges();
    expect(confirmations.confirm).not.toHaveBeenCalled();
    expect(api.post).toHaveBeenCalledWith('/aria2/restart');
    expect(button().textContent).toContain('KZ.ARIA2_RESTARTING');
    expect(button().disabled).toBe(true);

    await vi.advanceTimersByTimeAsync(RESTART_POLL_MS);
    fixture.detectChanges();
    expect(button().disabled).toBe(true);

    status = idle();
    await vi.advanceTimersByTimeAsync(RESTART_POLL_MS);
    fixture.detectChanges();
    expect(button().textContent).toContain('KZ.ARIA2_RESTART');
    expect(button().textContent).not.toContain('KZ.ARIA2_RESTARTING');
    expect(button().disabled).toBe(false);
  });

  it('shows Restarting… after a reload while a restart is running', async () => {
    status = idle({ restartState: 'RESTARTING', runningAutoSaveIntervalSeconds: null });
    await render();
    expect(button().textContent).toContain('KZ.ARIA2_RESTARTING');
    expect(button().disabled).toBe(true);
  });

  it('asks for confirmation only while a download is transferring', async () => {
    downloads = [{ state: 'TRANSFERRING' }];
    confirmations.confirm.mockResolvedValue(false);
    await render();
    await fixture.componentInstance.restart();
    expect(confirmations.confirm).toHaveBeenCalledOnce();
    expect(api.post).not.toHaveBeenCalled();

    confirmations.confirm.mockResolvedValue(true);
    await fixture.componentInstance.restart();
    expect(api.post).toHaveBeenCalledOnce();
  });

  it('warns when aria2 runs with a save interval different from the saved one', async () => {
    status = idle({
      runningAutoSaveIntervalSeconds: 60,
      savedAutoSaveIntervalSeconds: 300,
      restartPending: true,
    });
    const element = await render();
    expect(element.querySelector('.restart-pending')?.textContent).toContain(
      'KZ.ARIA2_RESTART_PENDING',
    );
    status = idle({ runningAutoSaveIntervalSeconds: 300, savedAutoSaveIntervalSeconds: 300 });
    await fixture.componentInstance.refresh();
    fixture.detectChanges();
    expect(element.querySelector('.restart-pending')).toBeNull();
  });

  it('reports a failed restart and allows a new attempt', async () => {
    status = idle({ restartState: 'FAILED' });
    const element = await render();
    expect(element.querySelector('[role="alert"]')?.textContent).toContain(
      'KZ.ARIA2_RESTART_FAILED',
    );
    expect(button().disabled).toBe(false);
  });
});
