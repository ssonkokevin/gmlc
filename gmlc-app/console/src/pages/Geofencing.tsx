import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { api, ApiError } from '../api';
import { KpiCard, Panel, StatusChip, stateTone } from '../components';
import { fmtDateTime, fmtInt } from '../format';
import type {
  ArmView,
  ClientView,
  DeliveryView,
  GeofenceEventPage,
  GeofenceGeometry,
  GeofenceSummary,
  GeofenceView,
  GeoShape,
  RangeKey,
  Session,
  SubscriptionView,
} from '../types';

const RANGES: RangeKey[] = ['1h', '24h', '7d', '30d'];
const SHAPES: Array<{ value: GeoShape; label: string }> = [
  { value: 'CIRCLE', label: 'Circle' },
  { value: 'POLYGON', label: 'Polygon' },
  { value: 'ELLIPSE', label: 'Ellipse' },
  { value: 'ARC', label: 'Ellipsoid arc' },
];

type TabKey = 'fences' | 'entering' | 'leaving' | 'inside' | 'webhooks';

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'fences', label: 'Geofences' },
  { key: 'entering', label: 'Entering' },
  { key: 'leaving', label: 'Leaving' },
  { key: 'inside', label: 'Inside' },
  { key: 'webhooks', label: 'Webhooks' },
];

const EVENT_TYPE: Partial<Record<TabKey, string>> = {
  entering: 'ENTER',
  leaving: 'LEAVE',
  inside: 'INSIDE',
};

function problemText(caught: unknown): string {
  if (!(caught instanceof ApiError)) {
    return 'The gateway did not answer. What is on screen is the last successful read.';
  }
  return caught.message;
}

function shapeSummary(geometry: GeofenceGeometry): string {
  switch (geometry.shape) {
    case 'CIRCLE':
      return `centre ${geometry.latitude}, ${geometry.longitude} · ${fmtInt(geometry.radiusM)} m`;
    case 'POLYGON':
      return `${geometry.points?.length ?? 0} vertices`;
    case 'ELLIPSE':
      return `centre ${geometry.latitude}, ${geometry.longitude} · ${fmtInt(geometry.semiMajorM)} × ${fmtInt(geometry.semiMinorM)} m · ${fmtInt(geometry.orientationDeg)}°`;
    case 'ARC':
      return `centre ${geometry.latitude}, ${geometry.longitude} · ${fmtInt(geometry.innerRadiusM)}–${fmtInt(geometry.outerRadiusM)} m · ${fmtInt(geometry.startAngleDeg)}° + ${fmtInt(geometry.includedAngleDeg)}°`;
    default:
      return '—';
  }
}

export function Geofencing(props: { session: Session; onExpired: () => void }) {
  const { session, onExpired } = props;
  const canManage = session.permissions.includes('MANAGE_GEOFENCES');
  const canReadEvents = session.permissions.includes('VIEW_REQUESTS');

  const [tab, setTab] = useState<TabKey>('fences');
  const [range, setRange] = useState<RangeKey>('24h');
  const [summary, setSummary] = useState<GeofenceSummary | null>(null);
  const [fences, setFences] = useState<GeofenceView[]>([]);
  const [arms, setArms] = useState<ArmView[]>([]);
  const [events, setEvents] = useState<GeofenceEventPage | null>(null);
  const [subscriptions, setSubscriptions] = useState<SubscriptionView[]>([]);
  const [clients, setClients] = useState<ClientView[]>([]);
  const [loading, setLoading] = useState(true);
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
      const [nextSummary, nextFences] = await Promise.all([
        api.geofenceSummary(range),
        api.geofences(),
      ]);
      setSummary(nextSummary);
      setFences(nextFences);

      const [inside, outside] = await Promise.all([api.arms('INSIDE'), api.arms('OUTSIDE')]);
      setArms([...inside, ...outside]);

      const type = EVENT_TYPE[tab];
      if (canReadEvents && type) {
        setEvents(await api.geofenceEvents(range, type));
      }
      if (tab === 'webhooks') {
        setSubscriptions(await api.subscriptions());
      }
    } catch (caught) {
      handle(caught);
    } finally {
      setLoading(false);
    }
  }, [range, tab, canReadEvents, handle]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!canManage) {
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
  }, [canManage]);

  const counts = summary?.counts;

  const createdFence = (fence: GeofenceView) => {
    setNotice(`Geofence “${fence.name}” created with a ${fence.shape.toLowerCase()} shape.`);
    setSecret(null);
    void load();
  };

  const createdSubscription = (name: string, value: string, noticeText: string) => {
    setNotice(`Subscription “${name}” created.`);
    setSecret({ value, notice: noticeText });
    void load();
  };

  return (
    <div className="dashboard">
      <div className="page-head">
        <div>
          <h1>Geofencing</h1>
          <p>
            Areas are stored with the TS 23.032 parameters the operator entered. Entry and exit
            events are computed from location results this gateway actually stored after a
            governed request — never from a simulated track.
          </p>
        </div>
        <div className="page-actions">
          <div className="segmented" role="group" aria-label="Time window">
            {RANGES.map((key) => (
              <button key={key} type="button" aria-pressed={key === range}
                className={key === range ? 'segment active' : 'segment'}
                onClick={() => setRange(key)}>
                {key}
              </button>
            ))}
          </div>
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
        <KpiCard label="Geofences" value={fmtInt(counts?.total)} hint={`${fmtInt(counts?.enabled)} enabled`} />
        <KpiCard label="Armed subjects" value={fmtInt(counts?.armed)} hint="One row per fence and subject" />
        <KpiCard label="Currently inside" value={fmtInt(counts?.inside)} hint="Last stored result was inside" />
        <KpiCard label="Events in window" value={fmtInt(counts?.events)}
          hint={`${fmtInt(counts?.entering)} entering · ${fmtInt(counts?.leaving)} leaving · ${fmtInt(counts?.insideEvents)} inside`} />
      </div>

      <div className="segmented tabs" role="tablist" aria-label="Geofence views">
        {TABS.map((item) => (
          <button key={item.key} type="button" role="tab" aria-selected={tab === item.key}
            className={tab === item.key ? 'segment active' : 'segment'}
            onClick={() => setTab(item.key)}>
            {item.label}
          </button>
        ))}
      </div>

      {tab === 'fences' ? (
        <>
          <Panel title="Geofences" subtitle="Definitions, the shape entered and the current arm counts.">
            {fences.length === 0 ? (
              <p className="empty">No geofence is defined for this tenant.</p>
            ) : (
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th scope="col">Name</th>
                      <th scope="col">Shape</th>
                      <th scope="col">Group</th>
                      <th scope="col">Report inside</th>
                      <th scope="col">Armed</th>
                      <th scope="col">Inside</th>
                      <th scope="col">State</th>
                      <th scope="col">Updated</th>
                      {canManage ? <th scope="col">Action</th> : null}
                    </tr>
                  </thead>
                  <tbody>
                    {fences.map((fence) => (
                      <tr key={fence.geofenceId}>
                        <td><strong>{fence.name}</strong></td>
                        <td>{fence.shape}<br /><span className="note">{shapeSummary(fence.geometry)}</span></td>
                        <td>{fence.group ?? '—'}</td>
                        <td>{fence.reportInside ? 'Yes' : 'No'}</td>
                        <td>{fmtInt(fence.armed)}</td>
                        <td>{fmtInt(fence.inside)}</td>
                        <td><StatusChip label={fence.enabled ? 'ENABLED' : 'DISABLED'}
                          tone={fence.enabled ? 'good' : 'neutral'} /></td>
                        <td>{fmtDateTime(fence.updatedAt)}</td>
                        {canManage ? (
                          <td>
                            <button type="button" className="button button-small"
                              onClick={() => {
                                api.patchGeofence(fence.geofenceId, { enabled: !fence.enabled })
                                  .then(() => {
                                    setNotice(`Geofence “${fence.name}” ${fence.enabled ? 'disabled' : 'enabled'}.`);
                                    void load();
                                  })
                                  .catch(handle);
                              }}>
                              {fence.enabled ? 'Disable' : 'Enable'}
                            </button>
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
            {canManage ? (
              <CreateFence onCreated={createdFence} onError={handle} />
            ) : (
              <Panel title="Create a geofence">
                <p className="note">This account does not carry MANAGE_GEOFENCES, so it cannot change areas.</p>
              </Panel>
            )}
            {canManage ? (
              <ArmSubject fences={fences} clients={clients} onArmed={(message) => {
                setNotice(message);
                void load();
              }} onError={handle} />
            ) : null}
          </div>

          <Panel title="Armed subjects" subtitle="Masked labels only; the MSISDN and IMSI are not stored here.">
            {arms.length === 0 ? (
              <p className="empty">No subject is armed on any fence.</p>
            ) : (
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th scope="col">Fence</th>
                      <th scope="col">Subject</th>
                      <th scope="col">State</th>
                      <th scope="col">Armed</th>
                      <th scope="col">Last evaluated</th>
                      {canManage ? <th scope="col">Action</th> : null}
                    </tr>
                  </thead>
                  <tbody>
                    {arms.map((arm) => (
                      <tr key={`${arm.geofenceId}-${arm.subjectRef}`}>
                        <td>{arm.geofenceName}</td>
                        <td className="mono">{arm.subjectLabel}</td>
                        <td>{arm.state ? <StatusChip label={arm.state}
                          tone={arm.state === 'INSIDE' ? 'good' : 'neutral'} /> : 'not evaluated'}</td>
                        <td>{fmtDateTime(arm.armedAt)}</td>
                        <td>{arm.lastEvaluatedAt ? fmtDateTime(arm.lastEvaluatedAt) : '—'}</td>
                        {canManage ? (
                          <td>
                            <button type="button" className="button button-small"
                              onClick={() => {
                                api.disarmGeofence(arm.geofenceId, arm.subjectRef)
                                  .then(() => {
                                    setNotice(`Disarmed ${arm.subjectLabel} from ${arm.geofenceName}.`);
                                    void load();
                                  })
                                  .catch(handle);
                              }}>
                              Disarm
                            </button>
                          </td>
                        ) : null}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        </>
      ) : null}

      {EVENT_TYPE[tab] ? (
        <Panel title={`${TABS.find((item) => item.key === tab)?.label} events`}
          subtitle="Each row names the result that produced it, its method and its uncertainty.">
          {!canReadEvents ? (
            <p className="note">
              This account does not carry VIEW_REQUESTS, so it cannot read the event feed:
              the feed contains positions.
            </p>
          ) : events && events.items.length > 0 ? (
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th scope="col">When</th>
                    <th scope="col">Fence</th>
                    <th scope="col">Subject</th>
                    <th scope="col">Method</th>
                    <th scope="col">Uncertainty</th>
                    <th scope="col">Coordinates</th>
                    <th scope="col">Request</th>
                  </tr>
                </thead>
                <tbody>
                  {events.items.map((event) => (
                    <tr key={event.eventId}>
                      <td>{fmtDateTime(event.occurredAt)}</td>
                      <td>{event.geofenceName}</td>
                      <td className="mono">{event.subjectLabel}</td>
                      <td>{event.method}</td>
                      <td>{fmtInt(event.uncertaintyM)} m</td>
                      <td className="mono">{event.latitude}, {event.longitude}</td>
                      <td className="mono">{event.requestId.slice(0, 8)}…</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="empty">
              No {tab} event was recorded in this window. Events exist only where a stored
              result crossed the fence.
            </p>
          )}
        </Panel>
      ) : null}

      {tab === 'webhooks' ? (
        <>
          <Panel title="Webhook subscriptions"
            subtitle="Deliveries carry the masked label and pseudonymous reference, never the MSISDN.">
            {subscriptions.length === 0 ? (
              <p className="empty">No subscription is configured for this tenant.</p>
            ) : (
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th scope="col">Name</th>
                      <th scope="col">Target</th>
                      <th scope="col">Events</th>
                      <th scope="col">Scope</th>
                      <th scope="col">Deliveries</th>
                      <th scope="col">Last delivered</th>
                      <th scope="col">State</th>
                      {canManage ? <th scope="col">Action</th> : null}
                    </tr>
                  </thead>
                  <tbody>
                    {subscriptions.map((subscription) => (
                      <SubscriptionRows key={subscription.subscriptionId}
                        subscription={subscription} canManage={canManage}
                        canReadDeliveries={canReadEvents}
                        onChanged={() => void load()} onError={handle} onNotice={setNotice} />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          <div className="panel-grid">
            {canManage ? (
              <WebhookForm fences={fences} onCreated={createdSubscription} onError={handle} />
            ) : (
              <Panel title="Create a subscription">
                <p className="note">This account does not carry MANAGE_GEOFENCES, so it cannot direct deliveries.</p>
              </Panel>
            )}
            <ReportingPanel summary={summary} />
          </div>
        </>
      ) : null}

      {tab === 'fences' || EVENT_TYPE[tab] ? (
        <ReportingPanel summary={summary} />
      ) : null}
    </div>
  );
}

function ReportingPanel({ summary }: { summary: GeofenceSummary | null }) {
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
    case 'areaEventsFromStoredResults':
      return 'Area events from stored results';
    case 'networkAreaEvents':
      return 'Network area-event subscription';
    case 'periodicLocation':
      return 'Deferred and periodic location';
    case 'motionEvents':
      return 'Motion events';
    case 'ueAvailable':
      return 'UE availability';
    case 'eventWebhooks':
      return 'Signed webhook delivery';
    default:
      return key;
  }
}

function CreateFence(props: {
  onCreated: (fence: GeofenceView) => void;
  onError: (caught: unknown) => void;
}) {
  const [name, setName] = useState('');
  const [group, setGroup] = useState('');
  const [description, setDescription] = useState('');
  const [reportInside, setReportInside] = useState(false);
  const [shape, setShape] = useState<GeoShape>('CIRCLE');
  const [latitude, setLatitude] = useState('0.3476');
  const [longitude, setLongitude] = useState('32.5825');
  const [radiusM, setRadiusM] = useState('1000');
  const [semiMajorM, setSemiMajorM] = useState('1000');
  const [semiMinorM, setSemiMinorM] = useState('500');
  const [orientationDeg, setOrientationDeg] = useState('0');
  const [outerRadiusM, setOuterRadiusM] = useState('2000');
  const [innerRadiusM, setInnerRadiusM] = useState('500');
  const [startAngleDeg, setStartAngleDeg] = useState('0');
  const [includedAngleDeg, setIncludedAngleDeg] = useState('120');
  const [points, setPoints] = useState('0.34,32.57\n0.34,32.60\n0.37,32.60\n0.37,32.57');
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    let geometry: GeofenceGeometry;
    const lat = Number(latitude);
    const lon = Number(longitude);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
      setFormError('Latitude and longitude must be numbers.');
      return;
    }
    if (shape === 'POLYGON') {
      const vertices = points
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean)
        .map((line) => {
          const [a, b] = line.split(',').map((part) => Number(part.trim()));
          return { latitude: a, longitude: b };
        });
      if (vertices.some((vertex) => !Number.isFinite(vertex.latitude) || !Number.isFinite(vertex.longitude))) {
        setFormError('Each polygon line must read latitude,longitude.');
        return;
      }
      geometry = { shape, points: vertices };
    } else if (shape === 'CIRCLE') {
      geometry = { shape, latitude: lat, longitude: lon, radiusM: Number(radiusM) };
    } else if (shape === 'ELLIPSE') {
      geometry = {
        shape, latitude: lat, longitude: lon,
        semiMajorM: Number(semiMajorM), semiMinorM: Number(semiMinorM),
        orientationDeg: Number(orientationDeg),
      };
    } else {
      geometry = {
        shape, latitude: lat, longitude: lon,
        outerRadiusM: Number(outerRadiusM), innerRadiusM: Number(innerRadiusM),
        startAngleDeg: Number(startAngleDeg), includedAngleDeg: Number(includedAngleDeg),
      };
    }

    setBusy(true);
    try {
      const fence = await api.createGeofence({
        name,
        description: description || undefined,
        group: group || undefined,
        reportInside,
        geometry,
      });
      setName('');
      setDescription('');
      props.onCreated(fence);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        props.onError(caught);
        return;
      }
      setFormError(problemText(caught));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title="Create a geofence" subtitle="Coordinates are decimal degrees; radii are metres.">
      <form className="stack" onSubmit={(event) => void submit(event)}>
        <div className="field-row">
          <div className="field">
            <label htmlFor="fence-name">Name</label>
            <input id="fence-name" value={name} maxLength={128} required
              onChange={(event) => setName(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="fence-group">Group</label>
            <input id="fence-group" value={group} maxLength={64}
              onChange={(event) => setGroup(event.target.value)} />
          </div>
        </div>
        <div className="field">
          <label htmlFor="fence-description">Description</label>
          <input id="fence-description" value={description} maxLength={512}
            onChange={(event) => setDescription(event.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="fence-shape">Shape</label>
          <select id="fence-shape" value={shape}
            onChange={(event) => setShape(event.target.value as GeoShape)}>
            {SHAPES.map((item) => (
              <option key={item.value} value={item.value}>{item.label}</option>
            ))}
          </select>
        </div>

        {shape === 'POLYGON' ? (
          <div className="field">
            <label htmlFor="fence-points">Polygon vertices (one latitude,longitude per line)</label>
            <textarea id="fence-points" rows={5} value={points}
              onChange={(event) => setPoints(event.target.value)} />
            <p className="field-hint">3 to 15 vertices. A repeated closing point is ignored.</p>
          </div>
        ) : (
          <div className="field-row">
            <div className="field">
              <label htmlFor="fence-lat">Centre latitude</label>
              <input id="fence-lat" type="number" step="any" value={latitude}
                onChange={(event) => setLatitude(event.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="fence-lon">Centre longitude</label>
              <input id="fence-lon" type="number" step="any" value={longitude}
                onChange={(event) => setLongitude(event.target.value)} />
            </div>
          </div>
        )}

        {shape === 'CIRCLE' ? (
          <div className="field">
            <label htmlFor="fence-radius">Radius (m)</label>
            <input id="fence-radius" type="number" step="any" value={radiusM}
              onChange={(event) => setRadiusM(event.target.value)} />
          </div>
        ) : null}

        {shape === 'ELLIPSE' ? (
          <div className="field-row">
            <div className="field">
              <label htmlFor="fence-major">Semi-major (m)</label>
              <input id="fence-major" type="number" step="any" value={semiMajorM}
                onChange={(event) => setSemiMajorM(event.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="fence-minor">Semi-minor (m)</label>
              <input id="fence-minor" type="number" step="any" value={semiMinorM}
                onChange={(event) => setSemiMinorM(event.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="fence-orientation">Orientation (°)</label>
              <input id="fence-orientation" type="number" step="any" value={orientationDeg}
                onChange={(event) => setOrientationDeg(event.target.value)} />
            </div>
          </div>
        ) : null}

        {shape === 'ARC' ? (
          <div className="field-row">
            <div className="field">
              <label htmlFor="fence-outer">Outer radius (m)</label>
              <input id="fence-outer" type="number" step="any" value={outerRadiusM}
                onChange={(event) => setOuterRadiusM(event.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="fence-inner">Inner radius (m)</label>
              <input id="fence-inner" type="number" step="any" value={innerRadiusM}
                onChange={(event) => setInnerRadiusM(event.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="fence-bearing">Start bearing (°)</label>
              <input id="fence-bearing" type="number" step="any" value={startAngleDeg}
                onChange={(event) => setStartAngleDeg(event.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="fence-angle">Included angle (°)</label>
              <input id="fence-angle" type="number" step="any" value={includedAngleDeg}
                onChange={(event) => setIncludedAngleDeg(event.target.value)} />
            </div>
          </div>
        ) : null}

        <label className="check">
          <input type="checkbox" checked={reportInside}
            onChange={(event) => setReportInside(event.target.checked)} />
          Write an INSIDE event while the subject stays inside
        </label>

        {formError ? <p className="form-error" role="alert">{formError}</p> : null}

        <div className="form-actions">
          <button type="submit" className="button button-primary" disabled={busy}>
            {busy ? 'Creating…' : 'Create geofence'}
          </button>
        </div>
      </form>
    </Panel>
  );
}

function ArmSubject(props: {
  fences: GeofenceView[];
  clients: ClientView[];
  onArmed: (message: string) => void;
  onError: (caught: unknown) => void;
}) {
  const [geofenceId, setGeofenceId] = useState('');
  const [clientId, setClientId] = useState('');
  const [subjectType, setSubjectType] = useState<'MSISDN' | 'IMSI'>('MSISDN');
  const [subject, setSubject] = useState('');
  const [purpose, setPurpose] = useState('');
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
    if (!geofenceId || !clientId || !subject) {
      setFormError('Choose a fence, a client and enter the subscriber.');
      return;
    }
    setBusy(true);
    try {
      const result = await api.armGeofence(geofenceId, {
        clientId,
        msisdn: subjectType === 'MSISDN' ? subject : undefined,
        imsi: subjectType === 'IMSI' ? subject : undefined,
        purpose,
      });
      setSubject('');
      props.onArmed(`${result.created ? 'Armed' : 'Already armed'} ${result.arm.subjectLabel} on ${result.arm.geofenceName}.`);
    } catch (caught) {
      setFormError(problemText(caught));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title="Arm a subject"
      subtitle="Arming uses the same consent check as a locate; a refusal is audited.">
      <form className="stack" onSubmit={(event) => void submit(event)}>
        <div className="field">
          <label htmlFor="arm-fence">Fence</label>
          <select id="arm-fence" value={geofenceId} required
            onChange={(event) => setGeofenceId(event.target.value)}>
            <option value="">Select a geofence</option>
            {props.fences.filter((fence) => fence.enabled).map((fence) => (
              <option key={fence.geofenceId} value={fence.geofenceId}>{fence.name}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="arm-client">Client and purpose</label>
          <select id="arm-client" value={clientId} required
            onChange={(event) => setClientId(event.target.value)}>
            <option value="">Select a client</option>
            {props.clients.map((client) => (
              <option key={client.clientId} value={client.clientId}>{client.name}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="arm-purpose">Purpose</label>
          <select id="arm-purpose" value={purpose} required
            onChange={(event) => setPurpose(event.target.value)}>
            {(selectedClient?.purposes ?? []).map((code) => (
              <option key={code} value={code}>{code}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <span className="field-label">Subscriber</span>
          <div className="segmented" role="group" aria-label="Subscriber identifier">
            <button type="button" aria-pressed={subjectType === 'MSISDN'}
              className={subjectType === 'MSISDN' ? 'segment active' : 'segment'}
              onClick={() => setSubjectType('MSISDN')}>MSISDN</button>
            <button type="button" aria-pressed={subjectType === 'IMSI'}
              className={subjectType === 'IMSI' ? 'segment active' : 'segment'}
              onClick={() => setSubjectType('IMSI')}>IMSI</button>
          </div>
          <input aria-label="Subscriber identifier" value={subject} inputMode="numeric"
            onChange={(event) => setSubject(event.target.value)} />
        </div>
        {formError ? <p className="form-error" role="alert">{formError}</p> : null}
        <div className="form-actions">
          <button type="submit" className="button button-primary" disabled={busy}>
            {busy ? 'Arming…' : 'Arm subject'}
          </button>
        </div>
      </form>
    </Panel>
  );
}

function SubscriptionRows(props: {
  subscription: SubscriptionView;
  canManage: boolean;
  canReadDeliveries: boolean;
  onChanged: () => void;
  onError: (caught: unknown) => void;
  onNotice: (message: string) => void;
}) {
  const { subscription } = props;
  const [deliveries, setDeliveries] = useState<DeliveryView[] | null>(null);
  const [busy, setBusy] = useState(false);

  const readDeliveries = async () => {
    setBusy(true);
    try {
      setDeliveries(await api.deliveries(subscription.subscriptionId));
    } catch (caught) {
      props.onError(caught);
    } finally {
      setBusy(false);
    }
  };

  const toggle = async () => {
    setBusy(true);
    try {
      await api.patchSubscription(subscription.subscriptionId, { enabled: !subscription.enabled });
      props.onNotice(`Subscription “${subscription.name}” ${subscription.enabled ? 'disabled' : 'enabled'}.`);
      props.onChanged();
    } catch (caught) {
      props.onError(caught);
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      await api.deleteSubscription(subscription.subscriptionId);
      props.onNotice(`Subscription “${subscription.name}” deleted.`);
      props.onChanged();
    } catch (caught) {
      props.onError(caught);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <tr>
        <td><strong>{subscription.name}</strong></td>
        <td className="mono">{subscription.url}</td>
        <td>{subscription.eventTypes.join(', ')}</td>
        <td>{subscription.geofenceName ?? 'All fences'}</td>
        <td>
          {fmtInt(subscription.pending)} pending · {fmtInt(subscription.delivered)} delivered · {fmtInt(subscription.failed)} failed
        </td>
        <td>{subscription.lastDeliveredAt ? fmtDateTime(subscription.lastDeliveredAt) : '—'}</td>
        <td><StatusChip label={subscription.enabled ? 'ENABLED' : 'DISABLED'}
          tone={subscription.enabled ? 'good' : 'neutral'} /></td>
        {props.canManage ? (
          <td>
            <div className="row-actions">
              <button type="button" className="button button-small" onClick={() => void toggle()}
                disabled={busy}>{subscription.enabled ? 'Disable' : 'Enable'}</button>
              {props.canReadDeliveries ? (
                <button type="button" className="button button-small"
                  onClick={() => void readDeliveries()} disabled={busy}>Log</button>
              ) : null}
              <button type="button" className="button button-small" onClick={() => void remove()}
                disabled={busy}>Delete</button>
            </div>
          </td>
        ) : null}
      </tr>
      {deliveries ? (
        <tr>
          <td colSpan={props.canManage ? 8 : 7}>
            <table className="data compact">
              <caption>Last {deliveries.length} delivery attempt(s)</caption>
              <thead>
                <tr>
                  <th scope="col">Delivery</th>
                  <th scope="col">Event</th>
                  <th scope="col">Status</th>
                  <th scope="col">Attempts</th>
                  <th scope="col">Code</th>
                  <th scope="col">Error</th>
                  <th scope="col">Next attempt</th>
                  <th scope="col">Delivered</th>
                </tr>
              </thead>
              <tbody>
                {deliveries.length === 0 ? (
                  <tr><td colSpan={8}>No delivery has been attempted for this subscription.</td></tr>
                ) : deliveries.map((delivery) => (
                  <tr key={delivery.deliveryId}>
                    <td>{delivery.deliveryId}</td>
                    <td>{delivery.eventId}</td>
                    <td><StatusChip label={delivery.status} tone={stateTone(delivery.status)} /></td>
                    <td>{delivery.attempts}</td>
                    <td>{delivery.lastStatusCode ?? '—'}</td>
                    <td>{delivery.lastError ?? '—'}</td>
                    <td>{fmtDateTime(delivery.nextAttemptAt)}</td>
                    <td>{delivery.deliveredAt ? fmtDateTime(delivery.deliveredAt) : '—'}</td>
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

function WebhookForm(props: {
  fences: GeofenceView[];
  onCreated: (name: string, secret: string, notice: string) => void;
  onError: (caught: unknown) => void;
}) {
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [eventTypes, setEventTypes] = useState<string[]>(['ENTER', 'LEAVE']);
  const [geofenceId, setGeofenceId] = useState('');
  const [enabled, setEnabled] = useState(true);
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const toggleType = (type: string) => {
    setEventTypes((current) => current.includes(type)
      ? current.filter((value) => value !== type)
      : [...current, type]);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    if (eventTypes.length === 0) {
      setFormError('Choose at least one event type.');
      return;
    }
    setBusy(true);
    try {
      const created = await api.createSubscription({
        name,
        url,
        eventTypes,
        geofenceId: geofenceId || undefined,
        enabled,
      });
      setName('');
      setUrl('');
      props.onCreated(created.subscription.name, created.secret, created.secretNotice);
    } catch (caught) {
      setFormError(problemText(caught));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title="Create a subscription"
      subtitle="The receiver gets a signed POST and must deduplicate on the delivery id.">
      <form className="stack" onSubmit={(event) => void submit(event)}>
        <div className="field">
          <label htmlFor="hook-name">Name</label>
          <input id="hook-name" value={name} maxLength={128} required
            onChange={(event) => setName(event.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="hook-url">Delivery URL</label>
          <input id="hook-url" type="url" value={url} maxLength={1024} required
            placeholder="https://example.test/gmlc/events"
            onChange={(event) => setUrl(event.target.value)} />
          <p className="field-hint">http or https only; credentials in the URL are refused.</p>
        </div>
        <fieldset className="field">
          <legend>Event types</legend>
          {['ENTER', 'LEAVE', 'INSIDE'].map((type) => (
            <label className="check" key={type}>
              <input type="checkbox" checked={eventTypes.includes(type)}
                onChange={() => toggleType(type)} />
              {type}
            </label>
          ))}
        </fieldset>
        <div className="field">
          <label htmlFor="hook-fence">Scope</label>
          <select id="hook-fence" value={geofenceId}
            onChange={(event) => setGeofenceId(event.target.value)}>
            <option value="">All geofences</option>
            {props.fences.map((fence) => (
              <option key={fence.geofenceId} value={fence.geofenceId}>{fence.name}</option>
            ))}
          </select>
        </div>
        <label className="check">
          <input type="checkbox" checked={enabled}
            onChange={(event) => setEnabled(event.target.checked)} />
          Enabled immediately
        </label>
        {formError ? <p className="form-error" role="alert">{formError}</p> : null}
        <div className="form-actions">
          <button type="submit" className="button button-primary" disabled={busy}>
            {busy ? 'Creating…' : 'Create subscription'}
          </button>
        </div>
      </form>
    </Panel>
  );
}
