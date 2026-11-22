import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, ApiError, loadDashboard } from '../api';
import {
  Donut,
  KpiCard,
  MethodSplit,
  Panel,
  StatusChip,
  ThroughputChart,
  stateTone,
} from '../components';
import type { Tone } from '../components';
import {
  RANGE_LABEL,
  fmtAge,
  fmtDateTime,
  fmtInt,
  fmtMs,
  fmtShare,
  fmtTime,
} from '../format';
import type { DashboardData, RangeKey, Session } from '../types';

const RANGES: RangeKey[] = ['1h', '24h', '7d', '30d'];

function message(caught: unknown): string {
  if (caught instanceof ApiError) {
    return caught.message;
  }
  return 'The gateway did not answer. Figures on screen are from the last successful read.';
}

function countDelta(current: number, previous: number): { text: string; tone: Tone } {
  const diff = current - previous;
  return {
    text: `${diff >= 0 ? '+' : ''}${fmtInt(diff)} vs previous window`,
    tone: diff > 0 ? 'good' : diff < 0 ? 'bad' : 'neutral',
  };
}

export function Dashboard(props: {
  session: Session;
  range: RangeKey;
  onRange: (range: RangeKey) => void;
  search: string;
  onOpenAlerts: (count: number) => void;
  onExpired: () => void;
}) {
  const { session, range, onRange, search, onOpenAlerts, onExpired } = props;
  const [data, setData] = useState<DashboardData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [pendingAck, setPendingAck] = useState<number | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const next = await loadDashboard(range);
      setData(next);
      setUpdatedAt(new Date());
      onOpenAlerts(next.summary.alerts.open ?? 0);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        onExpired();
        return;
      }
      setError(message(caught));
    } finally {
      setLoading(false);
    }
  }, [range, onOpenAlerts, onExpired]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        void load();
      }
    }, 30_000);
    return () => window.clearInterval(timer);
  }, [load]);

  const visibleRequests = useMemo(() => {
    const items = data?.requests.items ?? [];
    const needle = search.trim().toLowerCase();
    if (!needle) {
      return items;
    }
    return items.filter((item) =>
      [item.msisdn, item.imsi, item.clientName, item.state, item.protocol, item.operation,
        item.resultCode, item.purpose]
        .filter((value): value is string => Boolean(value))
        .some((value) => value.toLowerCase().includes(needle)));
  }, [data, search]);

  async function acknowledge(alertId: number) {
    setPendingAck(alertId);
    setError(null);
    try {
      await api.acknowledge(alertId);
      await load();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        onExpired();
        return;
      }
      setError(message(caught));
    } finally {
      setPendingAck(null);
    }
  }

  const summary = data?.summary;
  const deferred = data?.deferred;
  const canAcknowledge = session.permissions.includes('ACK_ALERTS');
  const slo = summary?.sloP95Ms;

  return (
    <div className="dashboard">
      <div className="page-head">
        <div>
          <h1>Operations Dashboard</h1>
          <p>Every figure is a stored row from this gateway, not an estimate or a sample.</p>
        </div>
        <div className="page-actions">
          <div className="segmented" role="group" aria-label="Time window">
            {RANGES.map((key) => (
              <button key={key} type="button" aria-pressed={key === range}
                className={key === range ? 'segment active' : 'segment'}
                onClick={() => onRange(key)}>
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

      {summary ? (
        <p className="data-note">
          Source <strong>{summary.environment}</strong> · {summary.dataSource} ·{' '}
          {RANGE_LABEL[range]} ({fmtTime(summary.from)}–{fmtTime(summary.to)} Africa/Kampala)
          {updatedAt ? ` · read at ${fmtTime(updatedAt.toISOString())}` : null}
        </p>
      ) : null}

      {!summary ? (
        <p className="empty" role="status">{loading ? 'Loading dashboard…' : 'No data could be read.'}</p>
      ) : (
        <>
          <div className="kpi-grid">
            <KpiCard label="Requests" value={fmtInt(summary.requests)}
              hint={`${fmtInt(summary.finished)} finished · ${fmtInt(summary.inFlight)} in flight`}
              delta={countDelta(summary.requests, summary.previousRequests)}
              spark={{ values: summary.series.map((point) => point.requests), label: 'Requests per bucket' }} />

            <KpiCard label="Success rate"
              value={summary.successRate === undefined ? 'No finished work' : fmtShare(summary.successRate)}
              hint={`${fmtInt(summary.answered)} answered of ${fmtInt(summary.finished)} finished`}
              delta={summary.successRate === undefined || summary.previousSuccessRate === undefined
                ? undefined
                : {
                  text: `${((summary.successRate - summary.previousSuccessRate) * 100).toFixed(1)} pp vs previous`,
                  tone: summary.successRate > summary.previousSuccessRate ? 'good'
                    : summary.successRate < summary.previousSuccessRate ? 'bad' : 'neutral',
                }}
              spark={{ values: summary.series.map((point) => point.successRate), label: 'Success rate per bucket' }} />

            <KpiCard label="Average response" value={fmtMs(summary.averageResponseMs)}
              hint={`p50 ${fmtMs(summary.p50ResponseMs)} · p95 ${fmtMs(summary.p95ResponseMs)}`}
              delta={summary.averageResponseMs === undefined || summary.previousAverageResponseMs === undefined
                ? undefined
                : {
                  text: `${summary.averageResponseMs - summary.previousAverageResponseMs >= 0 ? '+' : ''}${Math.round(summary.averageResponseMs - summary.previousAverageResponseMs)} ms vs previous`,
                  tone: summary.averageResponseMs < summary.previousAverageResponseMs ? 'good'
                    : summary.averageResponseMs > summary.previousAverageResponseMs ? 'bad' : 'neutral',
                }}
              spark={{ values: summary.series.map((point) => point.averageResponseMs), label: 'Average response time per bucket' }} />

            <KpiCard label="Active deferred"
              value={summary.deferred.available ? fmtInt(deferred?.open) : 'Not available'}
              unavailable={summary.deferred.available ? undefined : summary.deferred.reason}
              hint={deferred && summary.deferred.available
                ? `${fmtInt(deferred.open)} open · ${fmtInt(deferred.pastDeadline)} past deadline`
                : undefined} />

            <KpiCard label="p95 against objective"
              value={fmtMs(summary.p95ResponseMs)}
              hint={slo ? `Objective ${fmtMs(slo)}` : 'No objective configured for this deployment'}
              delta={slo && summary.p95ResponseMs !== undefined
                ? {
                  text: summary.p95ResponseMs <= slo ? 'Within objective' : 'Above objective',
                  tone: summary.p95ResponseMs <= slo ? 'good' : 'bad',
                }
                : undefined}
              spark={{ values: summary.series.map((point) => point.p95ResponseMs), label: 'p95 response time per bucket' }} />

            <KpiCard label="Accuracy fulfilled"
              value={summary.accuracyFulfilledRate === undefined ? 'Not measured' : fmtShare(summary.accuracyFulfilledRate)}
              hint="Share of answered results that met the requested accuracy" />
          </div>

          <div className="panel-grid">
            <Panel title="Throughput by signalling protocol" className="span-2"
              subtitle={`Buckets of ${data.throughput.bucketSeconds} s. The protocol is recorded when the request is created.`}>
              <dl className="totals">
                <div><dt>MAP</dt><dd>{fmtInt(data.throughput.totals.map)}</dd></div>
                <div><dt>Diameter</dt><dd>{fmtInt(data.throughput.totals.diameter)}</dd></div>
                <div><dt>Unattributed</dt><dd>{fmtInt(data.throughput.totals.unattributed)}</dd></div>
                <div><dt>Total</dt><dd>{fmtInt(data.throughput.totals.total)}</dd></div>
              </dl>
              <ThroughputChart points={data.throughput.points} range={range} />
            </Panel>

            <Panel title="Operation mix" subtitle="Completed records, grouped by what the gateway actually ran.">
              <Donut rows={data.mix.byOperation} title="Operation mix" />
              <h3 className="subhead">Positioning method</h3>
              <MethodSplit byMethod={data.mix.byMethod} />
              <h3 className="subhead">Result codes</h3>
              {data.mix.byResult.length === 0 ? (
                <p className="empty">No completed records in this window.</p>
              ) : (
                <ul className="plain-list">
                  {data.mix.byResult.map((row) => (
                    <li key={row.name}><span>{row.name}</span><span>{fmtInt(row.count)}</span></li>
                  ))}
                </ul>
              )}
              {data.mix.fallback.available ? null : (
                <p className="note">Fallback rate is not shown: {data.mix.fallback.reason}</p>
              )}
            </Panel>
          </div>

          <div className="panel-grid">
            <Panel title="Signalling and peer health"
              subtitle="Derived only from exchanges this gateway attempted. Silence is IDLE, never UP.">
              <div className="table-wrap">
                <table className="data compact">
                  <caption className="visually-hidden">Peer health</caption>
                  <thead>
                    <tr><th scope="col">Face</th><th scope="col">3GPP service</th><th scope="col">State</th><th scope="col">Endpoint</th>
                      <th scope="col">Attempts</th><th scope="col">Last exchange</th><th scope="col">Last code</th></tr>
                  </thead>
                  <tbody>
                    {data.peers.peers.map((peer) => (
                      <tr key={peer.face}>
                        <th scope="row">{peer.face}</th>
                        <td>{peer.service}</td>
                        <td><StatusChip label={peer.state} tone={stateTone(peer.state)} /></td>
                        <td>{peer.host ? `${peer.host}:${peer.port}` : 'not configured'}</td>
                        <td>{fmtInt(peer.attempts)} attempt(s), {fmtInt(peer.failures)} failed</td>
                        <td>{fmtDateTime(peer.lastAttemptAt)}</td>
                        <td>{peer.lastCode ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="note">
                Evaluated every {data.peers.settings.evalSeconds} s over{' '}
                {data.peers.settings.windowMinutes} min. Peer alert after{' '}
                {data.peers.settings.peerFailureThreshold} consecutive failures; error-rate alert at{' '}
                {fmtShare(data.peers.settings.errorRateThreshold)} after{' '}
                {fmtInt(data.peers.settings.minFinished)} finished; cell-plan alert at{' '}
                {data.peers.settings.cellGapThreshold} miss(es).
                {data.peers.settings.sloP95Ms ? ` p95 objective ${fmtMs(data.peers.settings.sloP95Ms)}.` : ' No p95 objective is configured.'}
              </p>
            </Panel>

            <Panel id="alerts" title="Active alerts"
              subtitle="One row per open condition. Acknowledging records the person; it does not close it.">
              {!data.alerts.evaluationEnabled ? (
                <p className="note">Alert evaluation is switched off in this deployment.</p>
              ) : null}
              {data.alerts.alerts.length === 0 ? (
                <p className="empty">No open alerts.</p>
              ) : (
                <ul className="alert-list">
                  {data.alerts.alerts.map((alert) => (
                    <li key={alert.alertId} className={`alert alert-${alert.severity.toLowerCase()}`}>
                      <div className="alert-main">
                        <StatusChip label={alert.severity} tone={stateTone(alert.severity)} />
                        <strong>{alert.kind.replaceAll('_', ' ')}</strong>
                        <p>{alert.summary}</p>
                        <p className="alert-meta">
                          Opened {fmtDateTime(alert.openedAt)} · last seen {fmtDateTime(alert.lastSeenAt)}
                          {alert.acknowledged ? ' · acknowledged' : ''}
                        </p>
                      </div>
                      {canAcknowledge && !alert.acknowledged ? (
                        <button type="button" className="button button-small"
                          onClick={() => void acknowledge(alert.alertId)}
                          disabled={pendingAck === alert.alertId}>
                          {pendingAck === alert.alertId ? 'Recording…' : 'Acknowledge'}
                        </button>
                      ) : null}
                    </li>
                  ))}
                </ul>
              )}
              <p className="note">
                {fmtInt(data.alerts.open)} open, {fmtInt(data.alerts.acknowledgedOpen)} acknowledged and still open.
              </p>
            </Panel>
          </div>

          <Panel title="Recent requests"
            subtitle={`Newest first. Identifiers are masked; revealing is a separate permission and an audit row.`}>
            <div className="table-wrap">
              <table className="data">
                <caption className="visually-hidden">Recent location requests</caption>
                <thead>
                  <tr>
                    <th scope="col">Time</th><th scope="col">State</th><th scope="col">Subject</th>
                    <th scope="col">Client</th><th scope="col">Signalling</th><th scope="col">Method</th>
                    <th scope="col">Uncertainty</th><th scope="col">Age</th><th scope="col">Response</th>
                    <th scope="col">Result</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleRequests.length === 0 ? (
                    <tr><td colSpan={10} className="empty-cell">
                      {search ? 'No request matches the search.' : 'No requests recorded in this window.'}
                    </td></tr>
                  ) : visibleRequests.map((item) => (
                    <tr key={item.requestId}>
                      <td>{fmtDateTime(item.createdAt)}</td>
                      <td><StatusChip label={item.recordStatus ?? item.state}
                        tone={stateTone(item.recordStatus ?? item.state)} /></td>
                      <td className="mono">{item.msisdn ?? item.imsi ?? '—'}</td>
                      <td>{item.clientName}</td>
                      <td>{item.protocol ?? '—'}{item.operation ? ` · ${item.operation}` : ''}</td>
                      <td>{item.method ?? '—'}</td>
                      <td>{item.uncertaintyM ? `${fmtInt(item.uncertaintyM)} m` : '—'}</td>
                      <td>{fmtAge(item.ageSeconds)}</td>
                      <td>{fmtMs(item.responseTimeMs)}</td>
                      <td>{item.resultCode ?? item.errorCode ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="note">
              Showing {fmtInt(visibleRequests.length)} of {fmtInt(data.requests.total)} requests in this window.
              Deferred location (LDR) is {deferred?.available ? 'available' : 'not available in this deployment'}.
            </p>
          </Panel>
        </>
      )}
    </div>
  );
}
