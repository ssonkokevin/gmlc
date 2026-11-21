import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '../api';
import { KpiCard, Panel } from '../components';
import { RANGE_LABEL, fmtAge, fmtInt, fmtMs, fmtShare, fmtTime } from '../format';
import type { RangeKey, Reports as ReportsData, Session } from '../types';

const RANGES: RangeKey[] = ['1h', '24h', '7d', '30d'];

function message(caught: unknown): string {
  if (caught instanceof ApiError) {
    return caught.message;
  }
  return 'The gateway did not answer. What is on screen is the last successful read.';
}

export function Reports(props: { session: Session; onExpired: () => void }) {
  const { session, onExpired } = props;
  const [range, setRange] = useState<RangeKey>('24h');
  const [data, setData] = useState<ReportsData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);

  const canRead = session.permissions.includes('VIEW_DASHBOARD');
  const canExport = session.permissions.includes('EXPORT_CDR');

  const load = useCallback(async () => {
    if (!canRead) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const next = await api.reports(range);
      setData(next);
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
  }, [canRead, range, onExpired]);

  useEffect(() => {
    void load();
  }, [load]);

  if (!canRead) {
    return (
      <div className="coming-soon">
        <p className="phase-tag">Not available to this account</p>
        <h1>Reports &amp; Analytics</h1>
        <p>
          This account ({session.role}) does not carry VIEW_DASHBOARD, so it cannot read
          tenant reports.
        </p>
      </div>
    );
  }

  const exportLink = (report: 'clients' | 'purposes' | 'rat' | 'method' | 'cell') =>
    canExport ? (
      <a className="button button-small" href={api.reportExportUrl(range, report)} download>
        Export CSV
      </a>
    ) : null;

  const totals = data?.totals;

  return (
    <div className="dashboard">
      <div className="page-head">
        <div>
          <h1>Reports &amp; Analytics</h1>
          <p>
            Every figure is grouped in the database from stored rows. A section with no rows
            says so; nothing is estimated or filled from a sample.
          </p>
        </div>
        <div className="page-actions">
          <div className="segmented" role="group" aria-label="Report window">
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

      <p className="data-note">
        {data ? (
          <>
            Source <strong>{data.environment}</strong> · {data.dataSource} · {RANGE_LABEL[range]} (
            {fmtTime(data.from)}–{fmtTime(data.to)} Africa/Kampala)
            {updatedAt ? ` · read at ${fmtTime(updatedAt.toISOString())}` : null}
            {canExport ? null : ' · CSV export requires EXPORT_CDR'}
          </>
        ) : loading ? 'Building reports from stored rows…' : 'No window could be read.'}
      </p>

      <div className="kpi-grid four">
        <KpiCard label="Requests" value={fmtInt(totals?.requests)}
          hint={`${fmtInt(totals?.finished)} finished · ${fmtInt(totals?.inFlight)} in flight`} />
        <KpiCard label="Success rate"
          value={totals && totals.finished > 0 && totals.successRate !== undefined
            ? fmtShare(totals.successRate) : 'No finished work'}
          hint={`${fmtInt(totals?.answered)} answered of ${fmtInt(totals?.finished)} finished`} />
        <KpiCard label="Response time" value={fmtMs(totals?.averageResponseMs)}
          hint={`p50 ${fmtMs(totals?.p50ResponseMs)} · p95 ${fmtMs(totals?.p95ResponseMs)}`} />
        <KpiCard label="Accuracy fulfilled"
          value={fmtShare(totals?.accuracyFulfilledRate)}
          hint="Share of answered results that met the requested accuracy" />
      </div>

      <div className="panel-grid">
        <Panel title="By client" className="span-2"
          subtitle="Grouped at request creation. A request with no CDR yet is in flight, not failed."
          actions={exportLink('clients')}>
          <div className="table-wrap">
            <table className="data compact">
              <caption className="visually-hidden">Requests by client</caption>
              <thead>
                <tr><th scope="col">Client</th><th scope="col">Requests</th>
                  <th scope="col">Answered</th><th scope="col">Not answered</th>
                  <th scope="col">In flight</th><th scope="col">Success</th>
                  <th scope="col">Average</th><th scope="col">p95</th></tr>
              </thead>
              <tbody>
                {(data?.byClient ?? []).length === 0 ? (
                  <tr><td colSpan={8} className="empty-cell">
                    No request was created in this window.
                  </td></tr>
                ) : (data?.byClient ?? []).map((row) => (
                  <tr key={row.name}>
                    <th scope="row">{row.name}</th>
                    <td>{fmtInt(row.requests)}</td>
                    <td>{fmtInt(row.answered)}</td>
                    <td>{fmtInt(row.failed)}</td>
                    <td>{fmtInt(row.inFlight)}</td>
                    <td>{fmtShare(row.successRate)}</td>
                    <td>{fmtMs(row.averageResponseMs)}</td>
                    <td>{fmtMs(row.p95ResponseMs)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>

        <Panel title="By purpose" subtitle="The purpose code stored on each request."
          actions={exportLink('purposes')}>
          <div className="table-wrap">
            <table className="data compact">
              <caption className="visually-hidden">Requests by purpose</caption>
              <thead>
                <tr><th scope="col">Purpose</th><th scope="col">Requests</th>
                  <th scope="col">Answered</th><th scope="col">Success</th></tr>
              </thead>
              <tbody>
                {(data?.byPurpose ?? []).length === 0 ? (
                  <tr><td colSpan={4} className="empty-cell">No request in this window.</td></tr>
                ) : (data?.byPurpose ?? []).map((row) => (
                  <tr key={row.name}>
                    <th scope="row">{row.name}</th>
                    <td>{fmtInt(row.requests)}</td>
                    <td>{fmtInt(row.answered)}</td>
                    <td>{fmtShare(row.successRate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      </div>

      <div className="panel-grid">
        <Panel title="By radio access technology"
          subtitle="Grouped at CDR write time. UNATTRIBUTED means the record named no RAT."
          actions={exportLink('rat')}>
          <div className="table-wrap">
            <table className="data compact">
              <caption className="visually-hidden">Exchanges by radio access technology</caption>
              <thead>
                <tr><th scope="col">RAT</th><th scope="col">Records</th>
                  <th scope="col">Answered</th><th scope="col">Not answered</th>
                  <th scope="col">Average</th><th scope="col">p95</th></tr>
              </thead>
              <tbody>
                {(data?.byRat ?? []).length === 0 ? (
                  <tr><td colSpan={6} className="empty-cell">No CDR was written in this window.</td></tr>
                ) : (data?.byRat ?? []).map((row) => (
                  <tr key={row.name}>
                    <th scope="row">{row.name}</th>
                    <td>{fmtInt(row.total)}</td>
                    <td>{fmtInt(row.answered)}</td>
                    <td>{fmtInt(row.failed)}</td>
                    <td>{fmtMs(row.averageResponseMs)}</td>
                    <td>{fmtMs(row.p95ResponseMs)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>

        <Panel title="By positioning method"
          subtitle="Answer-only averages of the uncertainty and age the gateway recorded."
          actions={exportLink('method')}>
          <div className="table-wrap">
            <table className="data compact">
              <caption className="visually-hidden">Exchanges by positioning method</caption>
              <thead>
                <tr><th scope="col">Method</th><th scope="col">Records</th>
                  <th scope="col">Answered</th><th scope="col">Avg uncertainty</th>
                  <th scope="col">Avg age</th></tr>
              </thead>
              <tbody>
                {(data?.byMethod ?? []).length === 0 ? (
                  <tr><td colSpan={5} className="empty-cell">No CDR was written in this window.</td></tr>
                ) : (data?.byMethod ?? []).map((row) => (
                  <tr key={row.name}>
                    <th scope="row">{row.name}</th>
                    <td>{fmtInt(row.count)}</td>
                    <td>{fmtInt(row.answered)}</td>
                    <td>{row.averageUncertaintyM === undefined
                      ? '—' : `${fmtInt(row.averageUncertaintyM)} m`}</td>
                    <td>{fmtAge(row.averageAgeSeconds === undefined
                      ? undefined : Math.round(row.averageAgeSeconds))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      </div>

      <Panel title="By serving cell"
        subtitle="Grouped from stored location results. A result that names no cell appears as UNATTRIBUTED."
        actions={exportLink('cell')}>
        <div className="table-wrap">
          <table className="data compact">
            <caption className="visually-hidden">Results by serving cell</caption>
            <thead>
              <tr><th scope="col">Serving cell</th><th scope="col">Results</th>
                <th scope="col">Avg uncertainty</th><th scope="col">p95 uncertainty</th>
                <th scope="col">Avg age</th><th scope="col">Accuracy fulfilled</th></tr>
            </thead>
            <tbody>
              {(data?.byCell ?? []).length === 0 ? (
                <tr><td colSpan={6} className="empty-cell">
                  No location result was stored in this window.
                </td></tr>
              ) : (data?.byCell ?? []).map((row) => (
                <tr key={row.name}>
                  <th scope="row">{row.name}</th>
                  <td>{fmtInt(row.results)}</td>
                  <td>{row.averageUncertaintyM === undefined
                    ? '—' : `${fmtInt(row.averageUncertaintyM)} m`}</td>
                  <td>{row.p95UncertaintyM === undefined
                    ? '—' : `${fmtInt(row.p95UncertaintyM)} m`}</td>
                  <td>{fmtAge(row.averageAgeSeconds === undefined
                    ? undefined : Math.round(row.averageAgeSeconds))}</td>
                  <td>{fmtShare(row.accuracyFulfilledRate)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="note">
          These are the cells that served stored results, not a coverage prediction. A cell
          with no result in the window does not appear.
        </p>
      </Panel>

      <Panel title="How these figures are grouped"
        subtitle="The same definitions the dashboard uses, stated so each figure can be checked.">
        <ul className="plain-list plain-list-stacked">
          {(data?.notes ?? [
            'Reports are read after signing in; the notes arrive with the data.',
          ]).map((note) => <li key={note}><span>{note}</span></li>)}
        </ul>
        <p className="note">
          Export writes the section to CSV, is audited with the section and window, and is
          refused to a role without EXPORT_CDR. Exports contain no subscriber identifier and
          no coordinate: the client, purpose and cell rows name aggregates only.
        </p>
      </Panel>
    </div>
  );
}
