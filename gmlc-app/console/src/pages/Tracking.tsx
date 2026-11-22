import { useCallback, useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { api, ApiError } from '../api';
import { KpiCard, Panel, StatusChip } from '../components';
import { fmtAge, fmtDateTime, fmtInt } from '../format';
import type {
  ClientView,
  NotificationChannel,
  Session,
  TrackedSubject,
  TrackingFix,
  TrackingRun,
  TrackingSchedule,
  TrackingSummary,
} from '../types';

type TabKey = 'subjects' | 'trail' | 'schedules' | 'channels';
type MapMode = 'MARKERS' | 'TRAIL' | 'HEATMAP';

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'subjects', label: 'Subjects' },
  { key: 'trail', label: 'Trail & map' },
  { key: 'schedules', label: 'Schedules' },
  { key: 'channels', label: 'Notification channels' },
];

function problemText(caught: unknown): string {
  if (!(caught instanceof ApiError)) {
    return 'The gateway did not answer. What is on screen is the last successful read.';
  }
  return caught.message;
}

function fmtInterval(seconds: number | undefined): string {
  if (seconds === undefined) {
    return '—';
  }
  if (seconds % 3600 === 0) {
    return `${seconds / 3600} h`;
  }
  if (seconds % 60 === 0) {
    return `${seconds / 60} min`;
  }
  return `${seconds} s`;
}

function runTone(state: string | undefined) {
  switch (state) {
    case 'LOCATED':
      return 'good' as const;
    case 'NO_FIX':
      return 'warn' as const;
    case 'SKIPPED':
      return 'info' as const;
    case 'FAILED':
      return 'bad' as const;
    default:
      return 'neutral' as const;
  }
}

function notifyTone(state: string | undefined) {
  switch (state) {
    case 'SENT':
      return 'good' as const;
    case 'FAILED':
      return 'bad' as const;
    case 'NOT_CONFIGURED':
      return 'info' as const;
    default:
      return 'neutral' as const;
  }
}

function localToIso(value: string): string | undefined {
  if (!value) {
    return undefined;
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString();
}

export function Tracking(props: { session: Session; onExpired: () => void }) {
  const { session, onExpired } = props;
  const canLocate = session.permissions.includes('LOCATE');
  const canRead = session.permissions.includes('VIEW_REQUESTS');
  const canManageChannels = session.permissions.includes('MANAGE_NOTIFIERS');

  const [tab, setTab] = useState<TabKey>('subjects');
  const [summary, setSummary] = useState<TrackingSummary | null>(null);
  const [subjects, setSubjects] = useState<TrackedSubject[]>([]);
  const [schedules, setSchedules] = useState<TrackingSchedule[]>([]);
  const [channels, setChannels] = useState<NotificationChannel[]>([]);
  const [selected, setSelected] = useState<string>('');
  const [trail, setTrail] = useState<TrackingFix[]>([]);
  const [mapMode, setMapMode] = useState<MapMode>('MARKERS');
  const [clients, setClients] = useState<ClientView[]>([]);
  const [loading, setLoading] = useState(true);
  const [trailLoading, setTrailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [secret, setSecret] = useState<{ value: string; notice: string } | null>(null);

  const handle = useCallback((caught: unknown) => {
    if (caught instanceof ApiError && caught.status === 401) {
      onExpired();
      return;
    }
    setError(problemText(caught));
  }, [onExpired]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [nextSummary, nextSubjects, nextSchedules, nextChannels] = await Promise.all([
        api.trackingSummary(),
        api.trackedSubjects(),
        api.schedules(),
        api.channels(),
      ]);
      setSummary(nextSummary);
      setSubjects(nextSubjects);
      setSchedules(nextSchedules);
      setChannels(nextChannels);
      setSelected((current) => (
        current && nextSubjects.some((subject) => subject.trackedId === current)
          ? current
          : nextSubjects[0]?.trackedId ?? ''
      ));
    } catch (caught) {
      handle(caught);
    } finally {
      setLoading(false);
    }
  }, [handle]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!canLocate) {
      return;
    }
    let live = true;
    api
      .clients()
      .then((list) => {
        if (live) {
          setClients(list);
        }
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [canLocate]);

  const loadTrail = useCallback(async () => {
    if (!selected) {
      setTrail([]);
      return;
    }
    setTrailLoading(true);
    try {
      setTrail(await api.trackingTrail(selected));
    } catch (caught) {
      handle(caught);
    } finally {
      setTrailLoading(false);
    }
  }, [selected, handle]);

  useEffect(() => {
    if (tab === 'trail') {
      void loadTrail();
    }
  }, [tab, loadTrail]);

  const selectedSubject = subjects.find((subject) => subject.trackedId === selected);

  const act = async (work: () => Promise<unknown>, message: string) => {
    setError(null);
    try {
      await work();
      setNotice(message);
      await load();
      if (tab === 'trail') {
        await loadTrail();
      }
    } catch (caught) {
      handle(caught);
    }
  };

  const runNow = async (schedule: TrackingSchedule) => {
    setError(null);
    try {
      const run = await api.runSchedule(schedule.scheduleId);
      const detail = run.errorCode ? ` (${run.errorCode})` : '';
      const notified = run.notifyState === 'SENT' ? ', notification sent'
        : run.notifyState === 'NONE' ? '' : `, notification ${run.notifyState.toLowerCase()}`;
      setNotice(`Manual run for ${schedule.subjectLabel}: ${run.state}${detail}${notified}.`);
      await load();
      if (tab === 'trail') {
        await loadTrail();
      }
    } catch (caught) {
      handle(caught);
    }
  };

  return (
    <div className="dashboard">
      <div className="page-head">
        <div>
          <h1>Tracking</h1>
          <p>
            A tracked subject is a roster row added with the client&apos;s consent for one
            purpose. Its trail is made of location results this gateway already stored after
            governed requests — never a simulated track. A schedule places those requests on a
            timer; this deployment has no deferred or periodic network subscription.
          </p>
        </div>
        <div className="page-actions">
          <button type="button" className="button" onClick={() => void load()} disabled={loading}>
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

      {error ? <p className="form-error" role="alert">{error}</p> : null}
      {notice ? <p className="note" role="status">{notice}</p> : null}

      {secret ? (
        <Panel title="Signing secret — shown once" subtitle="Give this to the receiver over a channel you trust.">
          <p className="note">{secret.notice}</p>
          <p className="mono secret-value">{secret.value}</p>
          <div className="form-actions">
            <button type="button" className="button" onClick={() => setSecret(null)}>
              I have stored it
            </button>
          </div>
        </Panel>
      ) : null}

      <div className="kpi-grid">
        <KpiCard label="Tracked subjects" value={fmtInt(summary?.total)}
          hint={`${fmtInt(summary?.enabled)} enabled · ${fmtInt(summary?.withFix)} with a stored fix`} />
        <KpiCard label="Stored fixes" value={fmtInt(summary?.fixes)}
          hint="Each fix came from a governed request" />
        <KpiCard label="Schedules" value={fmtInt(summary?.schedules)}
          hint={`${fmtInt(summary?.schedulesEnabled)} enabled · ${fmtInt(summary?.runs)} run(s)`} />
        <KpiCard label="Notification channels" value={fmtInt(summary?.channels)}
          hint={summary ? (summary.notifierConfigured
            ? 'Signing key is configured'
            : 'Notifier key not set — channel creation refused') : undefined}
          unavailable={summary && !summary.notifierConfigured
            ? 'No sealing key, so a secret cannot be stored' : undefined} />
      </div>

      <div className="segmented tabs" role="tablist" aria-label="Tracking views">
        {TABS.map((item) => (
          <button key={item.key} type="button" role="tab" aria-selected={tab === item.key}
            className={tab === item.key ? 'segment active' : 'segment'}
            onClick={() => setTab(item.key)}>
            {item.label}
          </button>
        ))}
      </div>

      {tab === 'subjects' ? (
        <>
          <Panel title="Tracked subjects"
            subtitle="Masked labels only; the MSISDN and IMSI stay off every view.">
            {!canRead ? (
              <p className="note">
                This account does not carry VIEW_REQUESTS, so it cannot read the roster:
                the rows resolve to positions.
              </p>
            ) : subjects.length === 0 ? (
              <p className="empty">
                No subject is tracked for this tenant. Adding one needs the client to hold
                consent covering the subject and purpose.
              </p>
            ) : (
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th scope="col">Subject</th>
                      <th scope="col">Client</th>
                      <th scope="col">Purpose</th>
                      <th scope="col">Fixes</th>
                      <th scope="col">Last fix</th>
                      <th scope="col">State</th>
                      {canLocate ? <th scope="col">Action</th> : null}
                    </tr>
                  </thead>
                  <tbody>
                    {subjects.map((subject) => (
                      <tr key={subject.trackedId}>
                        <td className="mono">{subject.subjectLabel}</td>
                        <td>{subject.clientName}</td>
                        <td>{subject.purpose}</td>
                        <td>{fmtInt(subject.fixes)}</td>
                        <td>
                          {subject.lastFix ? (
                            <>
                              {fmtDateTime(subject.lastFix.producedAt)}
                              <br />
                              <span className="note">
                                {subject.lastFix.method} · ±{fmtInt(subject.lastFix.uncertaintyM)} m
                                {' · '}confidence {fmtInt(subject.lastFix.confidence)}
                                {' · '}age {fmtAge(subject.lastFix.ageSeconds)}
                              </span>
                            </>
                          ) : (
                            <span className="note">No stored fix yet</span>
                          )}
                        </td>
                        <td><StatusChip label={subject.enabled ? 'ENABLED' : 'PAUSED'}
                          tone={subject.enabled ? 'good' : 'neutral'} /></td>
                        {canLocate ? (
                          <td>
                            <div className="row-actions">
                              <button type="button" className="button button-small"
                                onClick={() => {
                                  setSelected(subject.trackedId);
                                  setTab('trail');
                                }}>
                                Trail
                              </button>
                              <button type="button" className="button button-small"
                                onClick={() => void act(
                                  () => api.patchTrackedSubject(subject.trackedId, { enabled: !subject.enabled }),
                                  `${subject.subjectLabel} ${subject.enabled ? 'paused' : 'enabled'}.`)}>
                                {subject.enabled ? 'Pause' : 'Enable'}
                              </button>
                              <button type="button" className="button button-small"
                                onClick={() => void act(
                                  () => api.deleteTrackedSubject(subject.trackedId),
                                  `${subject.subjectLabel} removed from the roster.`)}>
                                Remove
                              </button>
                            </div>
                          </td>
                        ) : null}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          {canLocate ? (
            <AddSubject clients={clients} onAdded={(subject) => {
              setNotice(`${subject.subjectLabel} added for ${subject.purpose}.`);
              void load();
            }} onError={handle} />
          ) : (
            <Panel title="Add a subject">
              <p className="note">This account does not carry LOCATE, so it cannot add a tracked subject.</p>
            </Panel>
          )}

          <ReportingPanel summary={summary} />
        </>
      ) : null}

      {tab === 'trail' ? (
        <>
          <Panel title="Stored trail"
            subtitle="Newest stored result first. Each point names its method, uncertainty, confidence and age.">
            {!canRead ? (
              <p className="note">This account does not carry VIEW_REQUESTS, so it cannot read positions.</p>
            ) : subjects.length === 0 ? (
              <p className="empty">Add a subject first; a trail appears once a governed request stored a result.</p>
            ) : (
              <>
                <div className="filters">
                  <label>
                    Subject
                    <select value={selected} onChange={(event) => setSelected(event.target.value)}>
                      {subjects.map((subject) => (
                        <option key={subject.trackedId} value={subject.trackedId}>
                          {subject.subjectLabel} · {subject.purpose}
                        </option>
                      ))}
                    </select>
                  </label>
                  <div className="segmented" role="group" aria-label="Map rendering">
                    {(['MARKERS', 'TRAIL', 'HEATMAP'] as MapMode[]).map((mode) => (
                      <button key={mode} type="button" aria-pressed={mode === mapMode}
                        className={mode === mapMode ? 'segment active' : 'segment'}
                        onClick={() => setMapMode(mode)}>
                        {mode === 'MARKERS' ? 'Markers' : mode === 'TRAIL' ? 'Trails' : 'Heatmap'}
                      </button>
                    ))}
                  </div>
                  <button type="button" className="button" onClick={() => void loadTrail()} disabled={trailLoading}>
                    {trailLoading ? 'Reading…' : 'Reload trail'}
                  </button>
                </div>

                <div className="map-block">
                  <TrailMap fixes={trail} mode={mapMode} />
                  <p className="note">
                    {selectedSubject ? `${selectedSubject.subjectLabel} · ${selectedSubject.purpose} · ` : ''}
                    {fmtInt(trail.length)} stored fix(es). North is up; the plot is a plain
                    equirectangular projection of the stored coordinates, not a map service.
                    Rings show the stored uncertainty, not a live accuracy claim.
                  </p>
                </div>

                {trail.length === 0 ? (
                  <p className="empty">
                    No stored result matches this subject and purpose yet. A schedule or a
                    client request will add one.
                  </p>
                ) : (
                  <div className="table-wrap">
                    <table className="data">
                      <thead>
                        <tr>
                          <th scope="col">Produced</th>
                          <th scope="col">Method</th>
                          <th scope="col">Uncertainty</th>
                          <th scope="col">Confidence</th>
                          <th scope="col">Age</th>
                          <th scope="col">Accuracy</th>
                          <th scope="col">Cell</th>
                          <th scope="col">Coordinates</th>
                          <th scope="col">Request</th>
                        </tr>
                      </thead>
                      <tbody>
                        {trail.map((fix) => (
                          <tr key={fix.resultId}>
                            <td>{fmtDateTime(fix.producedAt)}</td>
                            <td>{fix.method}</td>
                            <td>{fmtInt(fix.uncertaintyM)} m</td>
                            <td>{fmtInt(fix.confidence)}</td>
                            <td>{fmtAge(fix.ageSeconds)}</td>
                            <td>
                              {fix.accuracyRequestedM ? `${fmtInt(fix.accuracyRequestedM)} m requested` : '—'}
                              <br />
                              <span className="note">
                                {fix.accuracyFulfilled === undefined || fix.accuracyFulfilled === null
                                  ? 'not judged'
                                  : fix.accuracyFulfilled ? 'fulfilled' : 'not fulfilled'}
                              </span>
                            </td>
                            <td className="mono">{fix.servingCell ?? '—'}</td>
                            <td className="mono">
                              {fix.latitude !== undefined && fix.longitude !== undefined
                                ? `${fix.latitude}, ${fix.longitude}`
                                : '—'}
                            </td>
                            <td className="mono">{fix.requestId.slice(0, 8)}…</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </>
            )}
          </Panel>

          <ReportingPanel summary={summary} />
        </>
      ) : null}

      {tab === 'schedules' ? (
        <>
          <Panel title="Automated schedules"
            subtitle="A schedule re-runs a normal governed request at its interval. It is not an LDR or periodic subscription.">
            {schedules.length === 0 ? (
              <p className="empty">No schedule is configured for this tenant.</p>
            ) : (
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th scope="col">Subject</th>
                      <th scope="col">Interval</th>
                      <th scope="col">Window</th>
                      <th scope="col">Notification</th>
                      <th scope="col">Next run</th>
                      <th scope="col">Last run</th>
                      <th scope="col">Runs</th>
                      <th scope="col">State</th>
                      {canLocate ? <th scope="col">Action</th> : null}
                    </tr>
                  </thead>
                  <tbody>
                    {schedules.map((schedule) => (
                      <ScheduleRows key={schedule.scheduleId} schedule={schedule}
                        canAct={canLocate} canReadRuns={canRead}
                        onAct={act} onRun={runNow} onError={handle} />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          <div className="panel-grid">
            {canLocate ? (
              <CreateSchedule subjects={subjects} channels={channels}
                onCreated={(schedule) => {
                  setNotice(`Schedule for ${schedule.subjectLabel} created, first run ${fmtDateTime(schedule.nextRunAt)}.`);
                  void load();
                }} onError={handle} />
            ) : (
              <Panel title="Create a schedule">
                <p className="note">This account does not carry LOCATE, so it cannot schedule location work.</p>
              </Panel>
            )}
            <Panel title="How a run behaves"
              subtitle="What the gateway does, and what it refuses to claim.">
              <ul className="plain-list">
                <li>Every run passes the same consent, accuracy and quota checks as a console locate.</li>
                <li>The interval floor is 300 s ({fmtInterval(300)}). Licence metering is not wired, so the gateway cannot know what the network would bill.</li>
                <li>A run records LOCATED, NO_FIX, SKIPPED or FAILED, and every attempt advances the next run a full interval.</li>
                <li>A notification is sent once per fix. A failed delivery is recorded and waits for the next run instead of retrying in a loop.</li>
                <li>Deleting a channel that a schedule uses is refused until the schedule is changed or removed.</li>
              </ul>
            </Panel>
          </div>

          <ReportingPanel summary={summary} />
        </>
      ) : null}

      {tab === 'channels' ? (
        <>
          <Panel title="Notification channels"
            subtitle="Secrets are sealed with the deployment key, shown once and never read back.">
            {channels.length === 0 ? (
              <p className="empty">No channel is configured for this tenant.</p>
            ) : (
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th scope="col">Name</th>
                      <th scope="col">Type</th>
                      <th scope="col">Target</th>
                      <th scope="col">Schedules</th>
                      <th scope="col">State</th>
                      <th scope="col">Created</th>
                      {canManageChannels ? <th scope="col">Action</th> : null}
                    </tr>
                  </thead>
                  <tbody>
                    {channels.map((channel) => (
                      <tr key={channel.channelId}>
                        <td><strong>{channel.name}</strong></td>
                        <td>{channel.type}</td>
                        <td className="mono">{channel.url}</td>
                        <td>{fmtInt(channel.schedules)}</td>
                        <td><StatusChip label={channel.enabled ? 'ENABLED' : 'DISABLED'}
                          tone={channel.enabled ? 'good' : 'neutral'} /></td>
                        <td>{fmtDateTime(channel.createdAt)}</td>
                        {canManageChannels ? (
                          <td>
                            <div className="row-actions">
                              <button type="button" className="button button-small"
                                onClick={() => void act(
                                  () => api.patchChannel(channel.channelId, { enabled: !channel.enabled }),
                                  `Channel “${channel.name}” ${channel.enabled ? 'disabled' : 'enabled'}.`)}>
                                {channel.enabled ? 'Disable' : 'Enable'}
                              </button>
                              <button type="button" className="button button-small"
                                onClick={() => void act(
                                  () => api.deleteChannel(channel.channelId),
                                  `Channel “${channel.name}” deleted.`)}>
                                Delete
                              </button>
                            </div>
                          </td>
                        ) : null}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          <div className="panel-grid">
            {canManageChannels ? (
              <CreateChannel notifierConfigured={summary?.notifierConfigured ?? false}
                onCreated={(name, value, noticeText) => {
                  setNotice(`Channel “${name}” created.`);
                  setSecret({ value, notice: noticeText });
                  void load();
                }} onError={handle} />
            ) : (
              <Panel title="Create a channel">
                <p className="note">This account does not carry MANAGE_NOTIFIERS, so it cannot store credentials.</p>
              </Panel>
            )}
            <Panel title="What a notification carries"
              subtitle="The same signing convention as a geofence webhook delivery.">
              <ul className="plain-list">
                <li>A tracked.fix event with the masked label and pseudonymous reference, never the MSISDN.</li>
                <li>HMAC-SHA256 over <span className="mono">timestamp.body</span>, sent as <span className="mono">X-GMLC-Signature</span>.</li>
                <li><span className="mono">X-GMLC-Delivery</span> lets the receiver deduplicate.</li>
                <li>Email, Telegram and Slack are listed but not built; the form refuses them instead of pretending to deliver.</li>
              </ul>
            </Panel>
          </div>

          <ReportingPanel summary={summary} />
        </>
      ) : null}
    </div>
  );
}

function TrailMap({ fixes, mode }: { fixes: TrackingFix[]; mode: MapMode }) {
  const points = useMemo(() => fixes.filter((fix) => (
    typeof fix.latitude === 'number' && Number.isFinite(fix.latitude)
    && typeof fix.longitude === 'number' && Number.isFinite(fix.longitude)
  )).slice().reverse(), [fixes]);

  if (points.length === 0) {
    return (
      <div className="map-frame">
        <p className="empty">Nothing to plot: no stored fix carries coordinates.</p>
      </div>
    );
  }

  const width = 680;
  const height = 340;
  const pad = 34;
  const plotW = width - pad * 2;
  const plotH = height - pad * 2;

  const lats = points.map((fix) => fix.latitude as number);
  const lons = points.map((fix) => fix.longitude as number);
  const minLat = Math.min(...lats);
  const maxLat = Math.max(...lats);
  const minLon = Math.min(...lons);
  const maxLon = Math.max(...lons);
  const spanLat = Math.max(maxLat - minLat, 0.002);
  const spanLon = Math.max(maxLon - minLon, 0.002);
  const centreLat = (minLat + maxLat) / 2;
  const latPad = (spanLat - (maxLat - minLat)) / 2;
  const lonPad = (spanLon - (maxLon - minLon)) / 2;
  const south = minLat - latPad;
  const west = minLon - lonPad;

  const xOf = (lon: number) => pad + ((lon - west) / spanLon) * plotW;
  const yOf = (lat: number) => pad + plotH - ((lat - south) / spanLat) * plotH;
  const pixelsPerMetre = plotH / (spanLat * 111_320);
  const lonMetresPerDegree = 111_320 * Math.cos((centreLat * Math.PI) / 180);

  const heat: Array<{ x: number; y: number; count: number }> = [];
  if (mode === 'HEATMAP') {
    const cols = 17;
    const rows = 9;
    const counts = new Map<string, number>();
    points.forEach((fix) => {
      const col = Math.min(cols - 1, Math.max(0, Math.floor(((fix.longitude as number) - west) / spanLon * cols)));
      const row = Math.min(rows - 1, Math.max(0, Math.floor(((fix.latitude as number) - south) / spanLat * rows)));
      const key = `${col}:${row}`;
      counts.set(key, (counts.get(key) ?? 0) + 1);
    });
    const peak = Math.max(1, ...counts.values());
    counts.forEach((count, key) => {
      const [col, row] = key.split(':').map((part) => Number(part));
      heat.push({
        x: pad + (col / cols) * plotW,
        y: pad + plotH - ((row + 1) / rows) * plotH,
        count: count / peak,
      });
    });
  }

  return (
    <div className="map-frame">
      <svg className="trail-map" viewBox={`0 0 ${width} ${height}`} role="img"
        aria-label={`${points.length} stored fix(es) between latitude ${south.toFixed(4)} and ${(south + spanLat).toFixed(4)}, longitude ${west.toFixed(4)} and ${(west + spanLon).toFixed(4)}`}>
        <rect className="map-plot" x={pad} y={pad} width={plotW} height={plotH} />
        {[0, 0.25, 0.5, 0.75, 1].map((fraction) => (
          <g key={fraction}>
            <line className="map-grid" x1={pad} x2={pad + plotW}
              y1={pad + plotH * fraction} y2={pad + plotH * fraction} />
            <line className="map-grid" x1={pad + plotW * fraction} x2={pad + plotW * fraction}
              y1={pad} y2={pad + plotH} />
            <text className="axis-text" x={pad - 6} y={pad + plotH * fraction + 4} textAnchor="end">
              {(south + spanLat * (1 - fraction)).toFixed(4)}
            </text>
            <text className="axis-text" x={pad + plotW * fraction} y={height - 8} textAnchor="middle">
              {(west + spanLon * fraction).toFixed(4)}
            </text>
          </g>
        ))}

        {mode === 'HEATMAP' ? heat.map((cell) => (
          <rect key={`${cell.x}:${cell.y}`} className="heat-cell"
            x={cell.x} y={cell.y} width={plotW / 17} height={plotH / 9}
            opacity={0.18 + cell.count * 0.72} />
        )) : null}

        {mode === 'TRAIL' ? (
          <polyline className="trail-line"
            points={points.map((fix) => `${xOf(fix.longitude as number).toFixed(1)},${yOf(fix.latitude as number).toFixed(1)}`).join(' ')} />
        ) : null}

        {mode !== 'HEATMAP' ? points.map((fix, index) => {
          const radius = fix.uncertaintyM
            ? Math.max(4, Math.min(plotW / 3, fix.uncertaintyM * pixelsPerMetre))
            : 4;
          return (
            <g key={fix.resultId}>
              <title>
                {`${fmtDateTime(fix.producedAt)} · ${fix.method} · ±${fmtInt(fix.uncertaintyM)} m · age ${fmtAge(fix.ageSeconds)}`}
              </title>
              {fix.uncertaintyM ? (
                <circle className="trail-uncertainty" cx={xOf(fix.longitude as number)}
                  cy={yOf(fix.latitude as number)} r={radius} />
              ) : null}
              <circle className={index === points.length - 1 ? 'trail-point latest' : 'trail-point'}
                cx={xOf(fix.longitude as number)} cy={yOf(fix.latitude as number)} r={5} />
            </g>
          );
        }) : null}
      </svg>
      <div className="legend">
        <span className="legend-item">
          <i className="swatch swatch-map" />{mode === 'HEATMAP' ? 'Stored-fix density' : 'Stored fix'}
        </span>
        {mode === 'MARKERS' || mode === 'TRAIL' ? (
          <span className="legend-item"><i className="swatch swatch-ring" />Stored uncertainty</span>
        ) : null}
        <span className="legend-item note">
          {spanLat.toFixed(4)}° of latitude ≈ {fmtInt(Math.round(spanLat * 111_320))} m;
          {' '}{spanLon.toFixed(4)}° of longitude ≈ {fmtInt(Math.round(spanLon * lonMetresPerDegree))} m
        </span>
      </div>
    </div>
  );
}

function AddSubject(props: {
  clients: ClientView[];
  onAdded: (subject: TrackedSubject) => void;
  onError: (caught: unknown) => void;
}) {
  const [clientId, setClientId] = useState('');
  const [purpose, setPurpose] = useState('');
  const [kind, setKind] = useState<'MSISDN' | 'IMSI'>('MSISDN');
  const [subject, setSubject] = useState('');
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const selectedClient = props.clients.find((client) => client.clientId === clientId);

  useEffect(() => {
    if (!selectedClient) {
      return;
    }
    if (!selectedClient.purposes.includes(purpose)) {
      setPurpose(selectedClient.purposes[0] ?? '');
    }
  }, [selectedClient, purpose]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    if (!clientId || !subject) {
      setFormError('Choose a client and enter the subscriber.');
      return;
    }
    setBusy(true);
    try {
      const created = await api.addTrackedSubject({
        clientId,
        msisdn: kind === 'MSISDN' ? subject : undefined,
        imsi: kind === 'IMSI' ? subject : undefined,
        purpose,
      });
      setSubject('');
      props.onAdded(created);
    } catch (caught) {
      setFormError(problemText(caught));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title="Add a tracked subject"
      subtitle="Consent for the subject, purpose and client is checked; a refusal is audited.">
      <form className="stack" onSubmit={(event) => void submit(event)}>
        <div className="field-row">
          <div className="field">
            <label htmlFor="track-client">Client</label>
            <select id="track-client" value={clientId} required
              onChange={(event) => setClientId(event.target.value)}>
              <option value="">Select a client</option>
              {props.clients.map((client) => (
                <option key={client.clientId} value={client.clientId}>{client.name}</option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="track-purpose">Purpose</label>
            <select id="track-purpose" value={purpose} required
              onChange={(event) => setPurpose(event.target.value)}>
              {(selectedClient?.purposes ?? []).map((code) => (
                <option key={code} value={code}>{code}</option>
              ))}
            </select>
          </div>
        </div>
        <div className="field">
          <span className="field-label">Subscriber</span>
          <div className="segmented" role="group" aria-label="Subscriber identifier">
            <button type="button" aria-pressed={kind === 'MSISDN'}
              className={kind === 'MSISDN' ? 'segment active' : 'segment'}
              onClick={() => setKind('MSISDN')}>MSISDN</button>
            <button type="button" aria-pressed={kind === 'IMSI'}
              className={kind === 'IMSI' ? 'segment active' : 'segment'}
              onClick={() => setKind('IMSI')}>IMSI</button>
          </div>
          <input aria-label="Subscriber identifier" value={subject} inputMode="numeric"
            onChange={(event) => setSubject(event.target.value)} />
          <p className="field-hint">Only the pseudonymous reference and a masked label are kept for reading.</p>
        </div>
        {formError ? <p className="form-error" role="alert">{formError}</p> : null}
        <div className="form-actions">
          <button type="submit" className="button button-primary" disabled={busy}>
            {busy ? 'Adding…' : 'Add subject'}
          </button>
        </div>
      </form>
    </Panel>
  );
}

function ScheduleRows(props: {
  schedule: TrackingSchedule;
  canAct: boolean;
  canReadRuns: boolean;
  onAct: (work: () => Promise<unknown>, message: string) => Promise<void>;
  onRun: (schedule: TrackingSchedule) => Promise<void>;
  onError: (caught: unknown) => void;
}) {
  const { schedule } = props;
  const [runs, setRuns] = useState<TrackingRun[] | null>(null);
  const [busy, setBusy] = useState(false);

  const readRuns = async () => {
    setBusy(true);
    try {
      setRuns(await api.scheduleRuns(schedule.scheduleId));
    } catch (caught) {
      props.onError(caught);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <tr>
        <td>
          <strong className="mono">{schedule.subjectLabel}</strong>
          <br /><span className="note">{schedule.clientName} · {schedule.purpose}</span>
        </td>
        <td>{fmtInterval(schedule.intervalSeconds)}
          {schedule.accuracyM ? <><br /><span className="note">{fmtInt(schedule.accuracyM)} m requested</span></> : null}
        </td>
        <td>{fmtDateTime(schedule.startsAt)}
          <br /><span className="note">to {schedule.endsAt ? fmtDateTime(schedule.endsAt) : 'no end'}</span>
        </td>
        <td>
          {schedule.notifyOnFix ? (
            <>
              On fix<br /><span className="note">{schedule.channelName ?? 'channel missing'}</span>
            </>
          ) : (
            <span className="note">None</span>
          )}
        </td>
        <td>{fmtDateTime(schedule.nextRunAt)}</td>
        <td>
          {schedule.lastRunAt ? fmtDateTime(schedule.lastRunAt) : '—'}
          {schedule.lastState ? (
            <><br /><StatusChip label={schedule.lastState} tone={runTone(schedule.lastState)} /></>
          ) : null}
          {schedule.lastError ? <><br /><span className="note">{schedule.lastError}</span></> : null}
        </td>
        <td>{fmtInt(schedule.runs)}</td>
        <td><StatusChip label={schedule.enabled ? 'ENABLED' : 'PAUSED'}
          tone={schedule.enabled ? 'good' : 'neutral'} /></td>
        {props.canAct ? (
          <td>
            <div className="row-actions">
              <button type="button" className="button button-small"
                onClick={() => void props.onRun(schedule)}>
                Run now
              </button>
              <button type="button" className="button button-small"
                onClick={() => void props.onAct(
                  () => api.patchSchedule(schedule.scheduleId, { enabled: !schedule.enabled }),
                  `Schedule for ${schedule.subjectLabel} ${schedule.enabled ? 'paused' : 'enabled'}.`)}>
                {schedule.enabled ? 'Pause' : 'Enable'}
              </button>
              {props.canReadRuns ? (
                <button type="button" className="button button-small"
                  onClick={() => void readRuns()} disabled={busy}>History</button>
              ) : null}
              <button type="button" className="button button-small"
                onClick={() => void props.onAct(
                  () => api.deleteSchedule(schedule.scheduleId),
                  `Schedule for ${schedule.subjectLabel} deleted.`)}>
                Delete
              </button>
            </div>
          </td>
        ) : null}
      </tr>
      {runs ? (
        <tr>
          <td colSpan={props.canAct ? 9 : 8}>
            <table className="data compact">
              <caption>Last {runs.length} run(s), newest first</caption>
              <thead>
                <tr>
                  <th scope="col">When</th>
                  <th scope="col">Trigger</th>
                  <th scope="col">Outcome</th>
                  <th scope="col">Code</th>
                  <th scope="col">Request</th>
                  <th scope="col">Notification</th>
                  <th scope="col">Detail</th>
                </tr>
              </thead>
              <tbody>
                {runs.length === 0 ? (
                  <tr><td colSpan={7}>This schedule has not run yet.</td></tr>
                ) : runs.map((run) => (
                  <tr key={run.runId}>
                    <td>{fmtDateTime(run.occurredAt)}</td>
                    <td>{run.trigger}</td>
                    <td><StatusChip label={run.state} tone={runTone(run.state)} /></td>
                    <td>{run.errorCode ?? '—'}</td>
                    <td className="mono">{run.requestId ? `${run.requestId.slice(0, 8)}…` : '—'}</td>
                    <td>{run.notifyState === 'NONE' ? '—' : (
                      <StatusChip label={run.notifyState} tone={notifyTone(run.notifyState)} />
                    )}</td>
                    <td>{run.notifyDetail ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </td>
        </tr>
      ) : null}
    </>
  );
}

function CreateSchedule(props: {
  subjects: TrackedSubject[];
  channels: NotificationChannel[];
  onCreated: (schedule: TrackingSchedule) => void;
  onError: (caught: unknown) => void;
}) {
  const [trackedId, setTrackedId] = useState('');
  const [minutes, setMinutes] = useState('15');
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const [accuracy, setAccuracy] = useState('');
  const [notify, setNotify] = useState(false);
  const [channelId, setChannelId] = useState('');
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const usable = props.channels.filter((channel) => channel.enabled);

  useEffect(() => {
    if (!notify) {
      setChannelId('');
    } else if (!channelId && usable.length > 0) {
      setChannelId(usable[0].channelId);
    }
  }, [notify, channelId, usable]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    const intervalSeconds = Math.round(Number(minutes) * 60);
    if (!trackedId) {
      setFormError('Choose the subject to track.');
      return;
    }
    if (!Number.isFinite(intervalSeconds) || intervalSeconds < 300) {
      setFormError('The interval must be at least 5 minutes (300 s), the floor this deployment enforces.');
      return;
    }
    if (notify && !channelId) {
      setFormError('Choose a channel, or turn notification off.');
      return;
    }
    setBusy(true);
    try {
      const created = await api.createSchedule({
        trackedId,
        intervalSeconds,
        startsAt: localToIso(startsAt),
        endsAt: localToIso(endsAt),
        accuracyMeters: accuracy ? Number(accuracy) : undefined,
        notifyOnFix: notify,
        channelId: notify ? channelId : undefined,
      });
      setTrackedId('');
      props.onCreated(created);
    } catch (caught) {
      setFormError(problemText(caught));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title="Create a schedule"
      subtitle="One schedule per subject. Each run is a governed request, counted against the client's quota.">
      <form className="stack" onSubmit={(event) => void submit(event)}>
        <div className="field">
          <label htmlFor="schedule-subject">Subject</label>
          <select id="schedule-subject" value={trackedId} required
            onChange={(event) => setTrackedId(event.target.value)}>
            <option value="">Select a tracked subject</option>
            {props.subjects.filter((subject) => subject.enabled).map((subject) => (
              <option key={subject.trackedId} value={subject.trackedId}>
                {subject.subjectLabel} · {subject.purpose}
              </option>
            ))}
          </select>
        </div>
        <div className="field-row">
          <div className="field">
            <label htmlFor="schedule-interval">Interval (minutes, minimum 5)</label>
            <input id="schedule-interval" type="number" min={5} step={1} value={minutes}
              onChange={(event) => setMinutes(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="schedule-accuracy">Accuracy (m, optional)</label>
            <input id="schedule-accuracy" type="number" min={1} step={1} value={accuracy}
              onChange={(event) => setAccuracy(event.target.value)} />
          </div>
        </div>
        <div className="field-row">
          <div className="field">
            <label htmlFor="schedule-start">Starts</label>
            <input id="schedule-start" type="datetime-local" value={startsAt}
              onChange={(event) => setStartsAt(event.target.value)} />
            <p className="field-hint">Empty starts now.</p>
          </div>
          <div className="field">
            <label htmlFor="schedule-end">Ends (optional)</label>
            <input id="schedule-end" type="datetime-local" value={endsAt}
              onChange={(event) => setEndsAt(event.target.value)} />
          </div>
        </div>
        <label className="check">
          <input type="checkbox" checked={notify}
            onChange={(event) => setNotify(event.target.checked)} />
          Notify a channel when a run stores a fix
        </label>
        {notify ? (
          <div className="field">
            <label htmlFor="schedule-channel">Channel</label>
            <select id="schedule-channel" value={channelId} required
              onChange={(event) => setChannelId(event.target.value)}>
              <option value="">Select an enabled channel</option>
              {usable.map((channel) => (
                <option key={channel.channelId} value={channel.channelId}>{channel.name}</option>
              ))}
            </select>
            {usable.length === 0 ? (
              <p className="field-hint">No enabled channel exists yet. Create one on the channels tab first.</p>
            ) : null}
          </div>
        ) : null}
        {formError ? <p className="form-error" role="alert">{formError}</p> : null}
        <div className="form-actions">
          <button type="submit" className="button button-primary" disabled={busy}>
            {busy ? 'Creating…' : 'Create schedule'}
          </button>
        </div>
      </form>
    </Panel>
  );
}

function CreateChannel(props: {
  notifierConfigured: boolean;
  onCreated: (name: string, secret: string, notice: string) => void;
  onError: (caught: unknown) => void;
}) {
  const [name, setName] = useState('');
  const [type, setType] = useState('WEBHOOK');
  const [url, setUrl] = useState('');
  const [enabled, setEnabled] = useState(true);
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    setBusy(true);
    try {
      const created = await api.createChannel({ name, type, url, enabled });
      setName('');
      setUrl('');
      props.onCreated(created.channel.name, created.secret, created.secretNotice);
    } catch (caught) {
      setFormError(problemText(caught));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title="Create a channel"
      subtitle="The signing secret is generated here and sealed; it is returned once.">
      {!props.notifierConfigured ? (
        <p className="note">
          This deployment has no sealing key (<span className="mono">GMLC_TRACKING_NOTIFIER_KEY</span>),
          so it refuses to store a signing secret. Set the key and reload.
        </p>
      ) : null}
      <form className="stack" onSubmit={(event) => void submit(event)}>
        <div className="field">
          <label htmlFor="channel-name">Name</label>
          <input id="channel-name" value={name} maxLength={128} required
            onChange={(event) => setName(event.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="channel-type">Type</label>
          <select id="channel-type" value={type}
            onChange={(event) => setType(event.target.value)}>
            <option value="WEBHOOK">Webhook (signed HTTP POST)</option>
            <option value="EMAIL" disabled>Email — not built in this deployment</option>
            <option value="TELEGRAM" disabled>Telegram — not built in this deployment</option>
            <option value="SLACK" disabled>Slack — not built in this deployment</option>
          </select>
        </div>
        <div className="field">
          <label htmlFor="channel-url">Delivery URL</label>
          <input id="channel-url" type="url" value={url} maxLength={1024} required
            placeholder="https://example.test/gmlc/tracking"
            onChange={(event) => setUrl(event.target.value)} />
          <p className="field-hint">Absolute http or https address. Credentials in the URL are refused.</p>
        </div>
        <label className="check">
          <input type="checkbox" checked={enabled}
            onChange={(event) => setEnabled(event.target.checked)} />
          Enabled immediately
        </label>
        {formError ? <p className="form-error" role="alert">{formError}</p> : null}
        <div className="form-actions">
          <button type="submit" className="button button-primary"
            disabled={busy || !props.notifierConfigured}>
            {busy ? 'Creating…' : 'Create channel'}
          </button>
        </div>
      </form>
    </Panel>
  );
}

function ReportingPanel({ summary }: { summary: TrackingSummary | null }) {
  const entries = summary ? Object.entries(summary.reporting) : [];
  return (
    <Panel title="What this deployment can report"
      subtitle="A capability that is not built says so; it never shows an invented zero.">
      {entries.length === 0 ? (
        <p className="empty">The gateway did not return a reporting capability list.</p>
      ) : (
        <ul className="feature-list">
          {entries.map(([key, feature]) => (
            <li key={key}>
              <div>
                <strong>{featureLabel(key)}</strong>
                <p className="note">{feature.reason ?? 'No reason was supplied.'}</p>
              </div>
              <StatusChip label={feature.available ? 'AVAILABLE' : 'NOT AVAILABLE'}
                tone={feature.available ? 'good' : 'info'} />
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function featureLabel(key: string): string {
  switch (key) {
    case 'trailsFromStoredResults':
      return 'Trails from stored results';
    case 'liveNetworkTracking':
      return 'Live network tracking (LDR / periodic)';
    case 'notifyOnNewFix':
      return 'Notification on a stored fix';
    case 'heatmapRendering':
      return 'Heatmap of stored fixes';
    case 'webhookNotifier':
      return 'Signed webhook notifier';
    case 'emailNotifier':
      return 'Email notifier';
    case 'telegramNotifier':
      return 'Telegram notifier';
    case 'slackNotifier':
      return 'Slack notifier';
    default:
      return key;
  }
}
