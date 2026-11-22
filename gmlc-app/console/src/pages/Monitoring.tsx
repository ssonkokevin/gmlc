import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '../api';
import { KpiCard, Panel, StatusChip, stateTone } from '../components';
import {
  RANGE_LABEL,
  axisLabel,
  fmtAge,
  fmtDateTime,
  fmtInt,
  fmtMs,
  fmtShare,
  fmtTime,
} from '../format';
import type {
  ExchangeBucket,
  ExchangeItem,
  ExchangePage,
  ExchangeSeries,
  PeerPage,
  RangeKey,
  Session,
} from '../types';

const RANGES: RangeKey[] = ['1h', '24h', '7d', '30d'];
const PAGE = 25;
const PROTOCOLS = [
  { value: '', label: 'All faces' },
  { value: 'MAP', label: 'MAP' },
  { value: 'DIAMETER', label: 'Diameter' },
];
const STATUSES = [
  { value: '', label: 'Every outcome' },
  { value: 'ANSWERED', label: 'Answered' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'TIMEOUT', label: 'Timeout' },
  { value: 'ABORTED', label: 'Aborted' },
];

function message(caught: unknown): string {
  if (caught instanceof ApiError) {
    return caught.message;
  }
  return 'The gateway did not answer. What is on screen is the last successful read.';
}

export function Monitoring(props: { session: Session; onExpired: () => void }) {
  const { session, onExpired } = props;
  const [range, setRange] = useState<RangeKey>('24h');
  const [protocol, setProtocol] = useState('');
  const [recordStatus, setRecordStatus] = useState('');
  const [offset, setOffset] = useState(0);
  const [series, setSeries] = useState<ExchangeSeries | null>(null);
  const [page, setPage] = useState<ExchangePage | null>(null);
  const [peers, setPeers] = useState<PeerPage | null>(null);
  const [selected, setSelected] = useState<ExchangeItem | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);

  const canRead = session.permissions.includes('VIEW_REQUESTS');
  const canExport = session.permissions.includes('EXPORT_CDR');

  const load = useCallback(async () => {
    if (!canRead) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const [nextSeries, nextPage, nextPeers] = await Promise.all([
        api.exchangeSeries(range, protocol || undefined),
        api.exchanges(range, {
          protocol: protocol || undefined,
          recordStatus: recordStatus || undefined,
          limit: PAGE,
          offset,
        }),
        api.peers(),
      ]);
      setSeries(nextSeries);
      setPage(nextPage);
      setPeers(nextPeers);
      setUpdatedAt(new Date());
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        onExpired();
        return;
      }
      setError(message(caught));
    } finally {
      setLoading(false);
    }
  }, [canRead, range, protocol, recordStatus, offset, onExpired]);

  useEffect(() => {
    void load();
  }, [load]);

  if (!canRead) {
    return (
      <div className="coming-soon">
        <p className="phase-tag">Not available to this account</p>
        <h1>Monitoring &amp; Tracing</h1>
        <p>
          This account ({session.role}) does not carry VIEW_REQUESTS, so it cannot read the
          stored exchange history. Nothing is hidden behind a placeholder: the screen is
          closed because the role is closed.
        </p>
      </div>
    );
  }

  const items = page?.items ?? [];
  const firstRow = items.length === 0 ? 0 : offset + 1;
  const lastRow = offset + items.length;
  const total = page?.total ?? 0;

  const setRangeAndReset = (next: RangeKey) => {
    setRange(next);
    setOffset(0);
  };
  const setProtocolAndReset = (next: string) => {
    setProtocol(next);
    setOffset(0);
  };
  const setStatusAndReset = (next: string) => {
    setRecordStatus(next);
    setOffset(0);
  };

  return (
    <div className="dashboard">
      <div className="page-head">
        <div>
          <h1>Monitoring &amp; Tracing</h1>
          <p>
            One row per signalling attempt the gateway stored. A row is the CDR, not a
            reconstruction: if the gateway did not write it, it is not here.
          </p>
        </div>
        <div className="page-actions">
          <div className="segmented" role="group" aria-label="Time window">
            {RANGES.map((key) => (
              <button key={key} type="button" aria-pressed={key === range}
                className={key === range ? 'segment active' : 'segment'}
                onClick={() => setRangeAndReset(key)}>
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

      <p className="data-note">
        {series ? (
          <>
            Source <strong>{series.dataSource}</strong> · {RANGE_LABEL[range]} (
            {fmtTime(series.from)}–{fmtTime(series.to)} Africa/Kampala) · buckets of{' '}
            {series.bucketSeconds} s
            {updatedAt ? ` · read at ${fmtTime(updatedAt.toISOString())}` : null}
          </>
        ) : loading ? 'Reading stored exchanges…' : 'No window could be read.'}
      </p>

      <div className="kpi-grid">
        <KpiCard label="Stored exchanges" value={fmtInt(series?.totals.finished)}
          hint={`${fmtInt(series?.totals.answered)} answered · ${fmtInt(series?.totals.failed)} not answered`}
          spark={{ values: (series?.points ?? []).map((point) => point.answered + point.failed),
            label: 'Exchanges per bucket' }} />

        <KpiCard label="Link success"
          value={series && series.totals.finished > 0
            ? fmtShare(series.totals.answered / series.totals.finished) : 'No exchanges'}
          hint="Successful exchanges, not successful locations" />

        <KpiCard label="p95 exchange time" value={fmtMs(series?.totals.p95ResponseMs)}
          hint={`p50 ${fmtMs(series?.totals.p50ResponseMs)} · answered exchanges only`} />
      </div>

      <p className="note">
        {page?.rawPayload.available
          ? 'Raw payload capture is enabled in this deployment.'
          : `Raw payloads: ${page?.rawPayload.reason ?? 'capture state is not known yet.'}`}
      </p>

      <Panel title="Exchange history over time" className="span-2"
        subtitle="Answered and not-answered CDRs per bucket, from the same rows the list below shows.">
        <ExchangeChart points={series?.points ?? []} range={range} />
        {series && series.byProtocol.length > 0 ? (
          <p className="note">
            Faces in this window:{' '}
            {series.byProtocol.map((row) => `${row.name} ${fmtInt(row.count)}`).join(' · ')}.
          </p>
        ) : null}
      </Panel>

      <div className="panel-grid">
        <Panel title="Live peer state"
          subtitle="Counters since this gateway process started. Silence is IDLE, never UP.">
          {peers ? (
            <div className="table-wrap">
              <table className="data compact">
                <caption className="visually-hidden">Live peer state</caption>
                <thead>
                  <tr><th scope="col">Face</th><th scope="col">3GPP service</th><th scope="col">State</th><th scope="col">Endpoint</th>
                    <th scope="col">Attempts</th><th scope="col">Last exchange</th>
                    <th scope="col">Last code</th></tr>
                </thead>
                <tbody>
                  {peers.peers.map((peer) => (
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
          ) : <p className="empty">Peer state could not be read.</p>}
          <p className="note">
            The chart and list cover stored history; this table covers the running process.
            They can differ after a restart, and that difference is itself information.
          </p>
        </Panel>

        <Panel title="Faces in this window"
          subtitle="Grouped by the protocol the gateway recorded on each CDR.">
          {series && series.byProtocol.length > 0 ? (
            <ul className="plain-list">
              {series.byProtocol.map((row) => (
                <li key={row.name}><span>{row.name}</span><span>{fmtInt(row.count)}</span></li>
              ))}
            </ul>
          ) : <p className="empty">No CDR was written in this window.</p>}
          <p className="note">
            Reports group completed records at write time; the request window above is the
            same closed-open window the dashboard uses.
          </p>
        </Panel>
      </div>

      <Panel title="Stored exchanges"
        subtitle="Newest first. Filtering happens in the database; the total is the filtered count."
        actions={canExport ? (
          <a className="button button-small"
            href={api.exchangeExportUrl(range, {
              protocol: protocol || undefined,
              recordStatus: recordStatus || undefined,
            })}
            download>
            Export CSV
          </a>
        ) : (
          <span className="note">Export needs EXPORT_CDR</span>
        )}>
        <div className="filters">
          <label>
            <span>Face</span>
            <select value={protocol} onChange={(event) => setProtocolAndReset(event.target.value)}>
              {PROTOCOLS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </label>
          <label>
            <span>Outcome</span>
            <select value={recordStatus}
              onChange={(event) => setStatusAndReset(event.target.value)}>
              {STATUSES.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </label>
        </div>

        <div className="table-wrap">
          <table className="data compact">
            <caption className="visually-hidden">Stored signalling exchanges</caption>
            <thead>
              <tr>
                <th scope="col">Time</th><th scope="col">Outcome</th><th scope="col">Face</th>
                <th scope="col">Nodes</th><th scope="col">Method</th>
                <th scope="col">Uncertainty</th><th scope="col">Age</th>
                <th scope="col">Response</th><th scope="col">Result</th><th scope="col" />
              </tr>
            </thead>
            <tbody>
              {items.length === 0 ? (
                <tr><td colSpan={10} className="empty-cell">
                  {loading ? 'Reading stored exchanges…'
                    : 'No CDR matches these filters in this window.'}
                </td></tr>
              ) : items.map((item) => (
                <tr key={item.cdrId}>
                  <td>{fmtDateTime(item.createdAt)}</td>
                  <td><StatusChip label={item.recordStatus} tone={stateTone(item.recordStatus)} /></td>
                  <td>{item.protocol ?? '—'}{item.operation ? ` · ${item.operation}` : ''}</td>
                  <td>{item.servingNode ?? '—'}{item.positioningNode ? ` → ${item.positioningNode}` : ''}</td>
                  <td>{item.method ?? '—'}</td>
                  <td>{item.uncertaintyM ? `${fmtInt(item.uncertaintyM)} m` : '—'}</td>
                  <td>{fmtAge(item.ageSeconds)}</td>
                  <td>{fmtMs(item.responseTimeMs)}</td>
                  <td>{item.resultCode ?? '—'}</td>
                  <td>
                    <button type="button" className="link-button"
                      onClick={() => setSelected(item)}>
                      Inspect
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="pager">
          <span className="note">
            {total === 0 ? 'No rows' : `Rows ${fmtInt(firstRow)}–${fmtInt(lastRow)} of ${fmtInt(total)}`}
          </span>
          <div>
            <button type="button" className="button button-small"
              disabled={offset === 0 || loading}
              onClick={() => setOffset(Math.max(0, offset - PAGE))}>
              Previous
            </button>
            <button type="button" className="button button-small"
              disabled={offset + PAGE >= total || loading}
              onClick={() => setOffset(offset + PAGE)}>
              Next
            </button>
          </div>
        </div>
        <p className="note">
          Export writes the filtered rows to CSV and is audited with the row count. It never
          contains a subscriber identifier: the request id is the join key, and revealing the
          identifiers is a separate permission with its own audit row.
        </p>
      </Panel>

      {selected ? (
        <Panel title="Exchange record" subtitle={`CDR ${selected.cdrId} · ${fmtDateTime(selected.createdAt)}`}
          actions={<button type="button" className="button button-small"
            onClick={() => setSelected(null)}>Close</button>}>
          <dl className="kv">
            <div><dt>Request</dt><dd className="mono">{selected.requestId}</dd></div>
            <div><dt>Client</dt><dd>{selected.clientName ?? selected.clientId}</dd></div>
            <div><dt>Face</dt><dd>{selected.protocol ?? '—'}{selected.operation ? ` · ${selected.operation}` : ''}</dd></div>
            <div><dt>Outcome</dt><dd><StatusChip label={selected.recordStatus}
              tone={stateTone(selected.recordStatus)} /> {selected.resultCode ?? ''}</dd></div>
            <div><dt>RAT</dt><dd>{selected.rat ?? '—'}</dd></div>
            <div><dt>Method</dt><dd>{selected.method ?? '—'}</dd></div>
            <div><dt>Methods attempted</dt><dd>{selected.methodsAttempted.length
              ? selected.methodsAttempted.join(' → ') : 'none recorded'}</dd></div>
            <div><dt>Serving node</dt><dd>{selected.servingNode ?? '—'}</dd></div>
            <div><dt>Positioning node</dt><dd>{selected.positioningNode ?? '—'}</dd></div>
            <div><dt>Paging</dt><dd>{selected.pagingPerformed ? 'performed' : 'not performed'}</dd></div>
            <div><dt>Response time</dt><dd>{fmtMs(selected.responseTimeMs)}</dd></div>
            <div><dt>Accuracy</dt><dd>{selected.accuracyRequestedM
              ? `asked ${fmtInt(selected.accuracyRequestedM)} m`
              : 'not requested'}
              {selected.accuracyAchievedM ? ` · achieved ${fmtInt(selected.accuracyAchievedM)} m` : ''}
              {selected.accuracyFulfilled === undefined ? '' : selected.accuracyFulfilled
                ? ' · fulfilled' : ' · not fulfilled'}</dd></div>
            <div><dt>Uncertainty</dt><dd>{selected.uncertaintyM
              ? `${fmtInt(selected.uncertaintyM)} m` : '—'}</dd></div>
            <div><dt>Confidence</dt><dd>{selected.confidence === undefined
              ? '—' : `${selected.confidence}%`}</dd></div>
            <div><dt>Age of fix</dt><dd>{fmtAge(selected.ageSeconds)}</dd></div>
            <div><dt>Shape</dt><dd>{selected.shape ?? '—'}</dd></div>
            <div className="span-all"><dt>Position</dt><dd>{selected.latitude !== undefined
              && selected.longitude !== undefined
              ? `${selected.latitude}, ${selected.longitude}`
              : 'No coordinate was stored on this record.'}</dd></div>
          </dl>
          <p className="note">
            {page?.rawPayload.available
              ? 'Raw payload capture is enabled in this deployment.'
              : page?.rawPayload.reason ?? 'Raw payload capture is not available in this deployment.'}
          </p>
        </Panel>
      ) : null}
    </div>
  );
}

function ExchangeChart({ points, range }: { points: ExchangeBucket[]; range: RangeKey }) {
  if (points.length === 0) {
    return <p className="empty">No CDR was written in this window. The chart is empty on purpose.</p>;
  }
  const width = 640;
  const height = 200;
  const padLeft = 46;
  const padRight = 8;
  const padTop = 10;
  const padBottom = 26;
  const plotW = width - padLeft - padRight;
  const plotH = height - padTop - padBottom;
  const max = Math.max(1, ...points.map((point) => point.answered + point.failed));
  const slot = plotW / points.length;
  const barW = Math.max(2, Math.min(26, slot * 0.62));
  const stride = Math.max(1, Math.ceil(points.length / 8));
  const yOf = (value: number) => padTop + plotH - (value / max) * plotH;

  return (
    <>
      <svg className="chart" viewBox={`0 0 ${width} ${height}`} role="img"
        aria-label={`Exchanges per bucket: ${points.length} buckets, busiest ${fmtInt(max)} exchanges`}>
        {[0, 0.5, 1].map((fraction) => (
          <g key={fraction}>
            <line className="gridline" x1={padLeft} x2={width - padRight}
              y1={yOf(max * fraction)} y2={yOf(max * fraction)} />
            <text className="axis-text" x={padLeft - 6} y={yOf(max * fraction) + 4} textAnchor="end">
              {fmtInt(Math.round(max * fraction))}
            </text>
          </g>
        ))}
        {points.map((point, index) => {
          const x = padLeft + index * slot + (slot - barW) / 2;
          const answeredY = yOf(point.answered);
          const totalY = yOf(point.answered + point.failed);
          return (
            <g key={point.bucket}>
              <title>{`${axisLabel(point.bucket, range)}: ${point.answered} answered, ${point.failed} not answered`}</title>
              <rect className="bar bar-answered" x={x} y={answeredY} width={barW}
                height={Math.max(0, padTop + plotH - answeredY)} rx={1.5} />
              <rect className="bar bar-failed" x={x} y={totalY} width={barW}
                height={Math.max(0, answeredY - totalY)} rx={1.5} />
              {index % stride === 0 ? (
                <text className="axis-text" x={x + barW / 2} y={height - 8} textAnchor="middle">
                  {axisLabel(point.bucket, range)}
                </text>
              ) : null}
            </g>
          );
        })}
      </svg>
      <div className="legend">
        <span className="legend-item"><i className="swatch swatch-answered" />Answered</span>
        <span className="legend-item"><i className="swatch swatch-failed" />Not answered</span>
      </div>
      <details className="data-table">
        <summary>View data</summary>
        <table>
          <caption>Exchanges per bucket</caption>
          <thead>
            <tr><th scope="col">Bucket</th><th scope="col">Answered</th>
              <th scope="col">Not answered</th><th scope="col">Total</th>
              <th scope="col">Average response</th><th scope="col">p95</th></tr>
          </thead>
          <tbody>
            {points.map((point) => (
              <tr key={point.bucket}>
                <th scope="row">{axisLabel(point.bucket, range)}</th>
                <td>{fmtInt(point.answered)}</td>
                <td>{fmtInt(point.failed)}</td>
                <td>{fmtInt(point.answered + point.failed)}</td>
                <td>{fmtMs(point.averageResponseMs)}</td>
                <td>{fmtMs(point.p95ResponseMs)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </>
  );
}
