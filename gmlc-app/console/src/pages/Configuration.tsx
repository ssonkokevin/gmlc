import { useCallback, useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { api, ApiError } from '../api';
import { KpiCard, Panel, StatusChip } from '../components';
import { fmtDateTime, fmtInt } from '../format';
import type {
  CellPlan,
  CellPlanEntry,
  ConfigChange,
  ConfigItem,
  ConfigSnapshot,
  PeerPage,
} from '../types';
import type { Session } from '../ui';

type TabKey = 'thresholds' | 'changes' | 'network' | 'reference' | 'limits';

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'thresholds', label: 'Thresholds' },
  { key: 'changes', label: 'Change history' },
  { key: 'network', label: 'Network & cells' },
  { key: 'reference', label: 'Reference methods' },
  { key: 'limits', label: 'Privacy & limits' },
];

const RATS = ['GSM', 'UMTS', 'LTE', 'NR'];

interface ReferenceMethod {
  method: string;
  rats: string;
  reference: string;
  needs: string;
  built: string;
}

const REFERENCE_METHODS: ReferenceMethod[] = [
  {
    method: 'Basic Cell-ID',
    rats: 'GSM, UMTS, LTE, NR',
    reference: 'Cell-plan radius: roughly 100 m in dense urban to 3 km rural',
    needs: 'The cell identity from the serving node and a plan row that carries a position and radius',
    built: 'Yes: answers from the stored cell plan, with the plan radius as uncertainty',
  },
  {
    method: 'E-CID',
    rats: 'LTE, NR',
    reference: '50 to 200 m, vendor reference',
    needs: 'Timing advance or Rx-Tx measurements from the serving node',
    built: 'No: this build does not request or consume timing measurements',
  },
  {
    method: 'OTDOA',
    rats: 'LTE',
    reference: '50 to 150 m, vendor reference',
    needs: 'Positioning reference signals, assistance data and an E-SMLC',
    built: 'No: no E-SMLC is present, and one is not being built',
  },
  {
    method: 'A-GNSS',
    rats: 'GSM, UMTS, LTE, NR',
    reference: '5 to 50 m outdoors, vendor reference',
    needs: 'Assistance data and a satellite-capable handset',
    built: 'No: not implemented in this deployment',
  },
  {
    method: 'U-TDOA',
    rats: 'GSM, UMTS',
    reference: '50 to 150 m, vendor reference',
    needs: 'LMUs at the sites and uplink timing collection',
    built: 'No: no LMU infrastructure is present',
  },
  {
    method: 'E-OTD',
    rats: 'GSM',
    reference: '50 to 200 m, vendor reference',
    needs: 'LMUs and handset observed time differences',
    built: 'No: legacy GSM method, not implemented',
  },
  {
    method: 'TDOA (DL/UL, NR)',
    rats: 'NR',
    reference: '10 to 50 m in favourable sites, vendor reference',
    needs: 'Multi-RTT or TDOA measurements across several transmission points',
    built: 'No: not implemented; this gateway holds at Cell-ID',
  },
];

function problemText(caught: unknown): string {
  if (!(caught instanceof ApiError)) {
    return 'The gateway did not answer. What is on screen is the last successful read.';
  }
  return caught.message;
}

function valueText(value: unknown, type: string): string {
  if (value === null || value === undefined) {
    return '—';
  }
  if (type === 'DECIMAL') {
    return String(value);
  }
  return typeof value === 'number' ? String(value) : String(value);
}

function rangeText(item: ConfigItem): string {
  if (item.type === 'TEXT') {
    return `1 to ${fmtInt(item.maxLength)} characters`;
  }
  return `${fmtInt(item.min)} to ${fmtInt(item.max)}`;
}

function changeTone(status: string) {
  switch (status) {
    case 'APPLIED':
      return 'good' as const;
    case 'REJECTED':
      return 'bad' as const;
    case 'PENDING':
      return 'warn' as const;
    default:
      return 'neutral' as const;
  }
}

export function Configuration(props: { session: Session; onExpired: () => void }) {
  const { session, onExpired } = props;
  const canManage = session.permissions.includes('MANAGE_CONFIG');
  const canViewPeers = session.permissions.includes('VIEW_DASHBOARD');

  const [tab, setTab] = useState<TabKey>(canManage ? 'thresholds' : 'network');
  const [snapshot, setSnapshot] = useState<ConfigSnapshot | null>(null);
  const [changes, setChanges] = useState<ConfigChange[]>([]);
  const [changeStatus, setChangeStatus] = useState('ALL');
  const [peers, setPeers] = useState<PeerPage | null>(null);
  const [plan, setPlan] = useState<CellPlan | null>(null);
  const [planRat, setPlanRat] = useState('');
  const [loading, setLoading] = useState(canManage);
  const [planLoading, setPlanLoading] = useState(canManage);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState<ConfigItem | null>(null);
  const [draft, setDraft] = useState('');
  const [reason, setReason] = useState('');
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [deciding, setDeciding] = useState<{ change: ConfigChange; mode: 'APPROVE' | 'REJECT'; note: string } | null>(null);
  const [decisionError, setDecisionError] = useState<string | null>(null);

  const handle = useCallback((caught: unknown) => {
    if (caught instanceof ApiError && caught.status === 401) {
      onExpired();
      return;
    }
    setError(problemText(caught));
  }, [onExpired]);

  const loadPlan = useCallback(async (rat: string) => {
    if (!canManage) {
      return;
    }
    setPlanLoading(true);
    try {
      setPlan(await api.cellPlan(rat || undefined));
    } catch (caught) {
      handle(caught);
    } finally {
      setPlanLoading(false);
    }
  }, [canManage, handle]);

  const load = useCallback(async (status = changeStatus) => {
    if (!canManage) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const [nextSnapshot, nextChanges] = await Promise.all([
        api.configuration(),
        api.configChanges(status),
      ]);
      setSnapshot(nextSnapshot);
      setChanges(nextChanges);
    } catch (caught) {
      handle(caught);
    } finally {
      setLoading(false);
    }
  }, [canManage, changeStatus, handle]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadPlan(planRat);
  }, [loadPlan, planRat]);

  useEffect(() => {
    if (!canViewPeers) {
      return;
    }
    let live = true;
    api
      .peers()
      .then((next) => {
        if (live) {
          setPeers(next);
        }
      })
      .catch((caught) => {
        if (live) {
          handle(caught);
        }
      });
    return () => {
      live = false;
    };
  }, [canViewPeers, handle]);

  const pending = useMemo(() => changes.filter((change) => change.status === 'PENDING'), [changes]);

  const startEdit = (item: ConfigItem) => {
    setEditing(item);
    setDraft(valueText(item.value, item.type));
    setReason('');
    setFormError(null);
  };

  const submitChange = async (event: FormEvent) => {
    event.preventDefault();
    if (!editing) {
      return;
    }
    setFormError(null);
    if (reason.trim().length < 10) {
      setFormError('Give a reason of at least 10 characters; it is stored with the change.');
      return;
    }
    let value: string | number = draft;
    if (editing.type !== 'TEXT') {
      const parsed = Number(draft);
      if (!Number.isFinite(parsed)) {
        setFormError('Enter a number.');
        return;
      }
      value = parsed;
    }
    setSaving(true);
    try {
      const change = await api.requestConfigChange(editing.key, value, reason.trim());
      setNotice(change.status === 'APPLIED'
        ? `${editing.label} changed to ${valueText(change.requestedValue, editing.type)}.`
        : `${editing.label} is waiting for a second approver.`);
      setEditing(null);
      await load();
      await loadPlan(planRat);
    } catch (caught) {
      setFormError(problemText(caught));
    } finally {
      setSaving(false);
    }
  };

  const decide = async (event: FormEvent) => {
    event.preventDefault();
    if (!deciding) {
      return;
    }
    setDecisionError(null);
    if (deciding.mode === 'REJECT' && deciding.note.trim().length < 3) {
      setDecisionError('A rejection needs a note explaining why.');
      return;
    }
    setSaving(true);
    try {
      const result = deciding.mode === 'APPROVE'
        ? await api.approveConfigChange(deciding.change.changeId, deciding.note.trim() || undefined)
        : await api.rejectConfigChange(deciding.change.changeId, deciding.note.trim());
      setNotice(`${result.itemKey} ${result.status.toLowerCase()}.`);
      setDeciding(null);
      await load();
    } catch (caught) {
      setDecisionError(problemText(caught));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="dashboard">
      <div className="page-head">
        <div>
          <h1>Configuration</h1>
          <p>
            Every change on this screen writes a row with the value before and after, the person
            who asked, the reason and the decision. Thresholds alter what raises an alert; the LCS
            client name is carried on the signalling. Nothing here probes a peer, and no figure is
            estimated.
          </p>
        </div>
        <div className="page-actions">
          <button type="button" className="button" onClick={() => void load()} disabled={loading || !canManage}>
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

      {error ? <p className="form-error" role="alert">{error}</p> : null}
      {notice ? <p className="note" role="status">{notice}</p> : null}

      {!canManage ? (
        <p className="note">
          This account does not carry MANAGE_CONFIG, so it cannot read or change configuration.
          Peer health and the reference tables below remain visible where VIEW_DASHBOARD is held.
        </p>
      ) : null}

      <div className="kpi-grid">
        <KpiCard label="Editable items" value={fmtInt(snapshot?.items.length)}
          hint={snapshot ? `${snapshot.items.filter((item) => item.source === 'DATABASE').length} stored · the rest use defaults` : undefined} />
        <KpiCard label="Waiting for a decision" value={fmtInt(canManage ? pending.length : undefined)}
          hint={snapshot?.secondApproverRequired
            ? 'Production: a signalling change needs a second operator'
            : 'Lab: changes apply immediately, without a second approver'} />
        <KpiCard label="Cell-plan rows" value={fmtInt(plan?.total)} hint="Positions stored for Cell-ID answers" />
        <KpiCard label="Signalling peers" value={fmtInt(peers?.peers.length)}
          hint={peers ? `${peers.peers.filter((peer) => peer.configured).length} configured` : undefined} />
      </div>

      <div className="segmented tabs" role="tablist" aria-label="Configuration views">
        {TABS.map((item) => (
          <button key={item.key} type="button" role="tab" aria-selected={tab === item.key}
            className={tab === item.key ? 'segment active' : 'segment'}
            onClick={() => setTab(item.key)}>
            {item.label}
          </button>
        ))}
      </div>

      {tab === 'thresholds' ? (
        <>
          <Panel title="Editable configuration"
            subtitle="A whole number, decimal or piece of text; the server checks the range and the type again.">
            {!canManage ? (
              <p className="note">MANAGE_CONFIG is required to read these items.</p>
            ) : snapshot === null ? (
              <p className="empty">{loading ? 'Reading configuration…' : 'No configuration was returned.'}</p>
            ) : (
              <div className="table-wrap">
                <table className="data compact">
                  <thead>
                    <tr>
                      <th scope="col">Item</th>
                      <th scope="col">Value</th>
                      <th scope="col">Range</th>
                      <th scope="col">Version</th>
                      <th scope="col">State</th>
                      <th scope="col">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {snapshot.items.map((item) => (
                      <tr key={item.key}>
                        <td>
                          <strong>{item.label}</strong>
                          {item.signalling ? <> <StatusChip label="SIGNALLING" tone="warn" /></> : null}
                          <br /><span className="note">{item.description}</span>
                          <br /><span className="mono note">{item.key}</span>
                        </td>
                        <td className="mono">
                          {valueText(item.value, item.type)}
                          <br /><span className="note">
                            {item.source === 'DATABASE'
                              ? `stored · default ${valueText(item.defaultValue, item.type)}`
                              : `default · ${valueText(item.defaultValue, item.type)}`}
                          </span>
                          {item.updatedAt ? (
                            <><br /><span className="note">{fmtDateTime(item.updatedAt)}{item.updatedByName ? ` · ${item.updatedByName}` : ''}</span></>
                          ) : null}
                        </td>
                        <td>{rangeText(item)}</td>
                        <td>{item.version === 0 ? '—' : item.version}</td>
                        <td>
                          {item.pending ? (
                            <>
                              <StatusChip label="PENDING" tone="warn" />
                              <br /><span className="note">
                                {valueText(item.pending.requestedValue, item.type)} by {item.pending.requestedByName}
                              </span>
                            </>
                          ) : (
                            <StatusChip label="IN FORCE" tone="good" />
                          )}
                        </td>
                        <td>
                          <button type="button" className="button button-small"
                            onClick={() => startEdit(item)} disabled={Boolean(item.pending)}>
                            Edit
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          {canManage && editing ? (
            <Panel title={`Change ${editing.label}`}
              subtitle="The reason is stored with the change and cannot be blank.">
              <form className="stack" onSubmit={(event) => void submitChange(event)}>
                <div className="field">
                  <label htmlFor="config-value">
                    New value ({editing.type === 'TEXT' ? 'text' : editing.type === 'INTEGER' ? 'whole number' : 'decimal'})
                  </label>
                  <input id="config-value" value={draft}
                    inputMode={editing.type === 'TEXT' ? 'text' : 'decimal'}
                    onChange={(event) => setDraft(event.target.value)} required />
                  <p className="field-hint">
                    Default {valueText(editing.defaultValue, editing.type)}. Range {rangeText(editing)}.
                  </p>
                </div>
                <div className="field">
                  <label htmlFor="config-reason">Reason (at least 10 characters)</label>
                  <textarea id="config-reason" value={reason} maxLength={500} required
                    onChange={(event) => setReason(event.target.value)} />
                </div>
                <p className="note">
                  {snapshot?.secondApproverRequired
                    ? 'This deployment requires a second operator for a signalling change. The request stays PENDING until someone else approves it.'
                    : 'This deployment applies the change immediately. In production a signalling change waits for a second operator.'}
                </p>
                {formError ? <p className="form-error" role="alert">{formError}</p> : null}
                <div className="form-actions">
                  <button type="submit" className="button button-primary" disabled={saving}>
                    {saving ? 'Saving…' : 'Raise change'}
                  </button>
                  <button type="button" className="button" onClick={() => setEditing(null)} disabled={saving}>
                    Cancel
                  </button>
                </div>
              </form>
            </Panel>
          ) : null}

          <Panel title="How this behaves"
            subtitle="What the gateway does with a change, and what it refuses.">
            <ul className="plain-list">
              <li>A request is a new row; the value in force is only replaced when the change is applied.</li>
              <li>Applying checks the base version, so a change raised against an old value fails instead of overwriting a newer one.</li>
              <li>One item can have only one pending change at a time.</li>
              <li>The operator who raised a change can never approve it, even in production.</li>
              <li>Peers, retention and the licence are environment-managed. They have no row here because the running process would not honour an edit.</li>
            </ul>
          </Panel>
        </>
      ) : null}

      {tab === 'changes' ? (
        <>
          <Panel title="Change history"
            subtitle="Newest first. Rejected and applied rows stay; nothing is deleted."
            actions={canManage ? (
              <label className="note">
                Status{' '}
                <select value={changeStatus}
                  onChange={(event) => setChangeStatus(event.target.value)}>
                  <option value="ALL">All</option>
                  <option value="PENDING">Pending</option>
                  <option value="APPLIED">Applied</option>
                  <option value="REJECTED">Rejected</option>
                </select>
              </label>
            ) : undefined}>
            {!canManage ? (
              <p className="note">MANAGE_CONFIG is required to read the history.</p>
            ) : changes.length === 0 ? (
              <p className="empty">No change has been raised for this tenant.</p>
            ) : (
              <div className="table-wrap">
                <table className="data compact">
                  <thead>
                    <tr>
                      <th scope="col">When</th>
                      <th scope="col">Item</th>
                      <th scope="col">Change</th>
                      <th scope="col">Reason</th>
                      <th scope="col">State</th>
                      <th scope="col">Decision</th>
                      <th scope="col">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {changes.map((change) => (
                      <tr key={change.changeId}>
                        <td>{fmtDateTime(change.requestedAt)}<br /><span className="note">{change.requestedByName}</span></td>
                        <td className="mono">{change.itemKey}<br /><span className="note">base v{change.baseVersion}</span></td>
                        <td className="mono">
                          {change.previousValue === null || change.previousValue === undefined
                            ? '(unset)' : JSON.stringify(change.previousValue)}
                          {' → '}
                          {JSON.stringify(change.requestedValue)}
                        </td>
                        <td>{change.reason}</td>
                        <td>
                          <StatusChip label={change.status} tone={changeTone(change.status)} />
                          {change.requiresApproval ? <><br /><span className="note">second approver required</span></> : null}
                        </td>
                        <td>
                          {change.decidedAt ? fmtDateTime(change.decidedAt) : '—'}
                          {change.decidedByName ? <><br /><span className="note">{change.decidedByName}</span></> : null}
                          {change.decisionNote ? <><br /><span className="note">{change.decisionNote}</span></> : null}
                        </td>
                        <td>
                          {canManage && change.status === 'PENDING' ? (
                            <div className="row-actions">
                              <button type="button" className="button button-small"
                                onClick={() => {
                                  setDeciding({ change, mode: 'APPROVE', note: '' });
                                  setDecisionError(null);
                                }}
                                disabled={change.requestedBy === session.userId || !change.requiresApproval}>
                                Approve
                              </button>
                              <button type="button" className="button button-small"
                                onClick={() => {
                                  setDeciding({ change, mode: 'REJECT', note: '' });
                                  setDecisionError(null);
                                }}>
                                Reject
                              </button>
                            </div>
                          ) : '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          {canManage && deciding ? (
            <Panel title={`${deciding.mode === 'APPROVE' ? 'Approve' : 'Reject'} ${deciding.change.itemKey}`}
              subtitle={deciding.mode === 'APPROVE'
                ? 'Approving applies the requested value under your name.'
                : 'Rejecting keeps the value in force and records why.'}>
              <form className="stack" onSubmit={(event) => void decide(event)}>
                <div className="field">
                  <label htmlFor="decision-note">
                    {deciding.mode === 'REJECT' ? 'Reason for rejection (required, at least 3 characters)' : 'Note (optional)'}
                  </label>
                  <textarea id="decision-note" value={deciding.note} maxLength={500}
                    onChange={(event) => setDeciding({ ...deciding, note: event.target.value })} />
                </div>
                {decisionError ? <p className="form-error" role="alert">{decisionError}</p> : null}
                <div className="form-actions">
                  <button type="submit" className="button button-primary" disabled={saving}>
                    {saving ? 'Saving…' : deciding.mode === 'APPROVE' ? 'Approve and apply' : 'Reject'}
                  </button>
                  <button type="button" className="button" onClick={() => setDeciding(null)} disabled={saving}>
                    Cancel
                  </button>
                </div>
              </form>
            </Panel>
          ) : null}
        </>
      ) : null}

      {tab === 'network' ? (
        <>
          <Panel title="Signalling peers"
            subtitle="State comes from exchanges the gateway attempted. A silent face is IDLE, never healthy.">
            {!canViewPeers ? (
              <p className="note">VIEW_DASHBOARD is required to read peer state.</p>
            ) : peers === null ? (
              <p className="empty">No peer state was returned.</p>
            ) : (
              <>
                <div className="table-wrap">
                  <table className="data compact">
                    <thead>
                      <tr>
                        <th scope="col">Face</th>
                        <th scope="col">3GPP service</th>
                        <th scope="col">Bearer</th>
                        <th scope="col">Endpoint</th>
                        <th scope="col">Configured</th>
                        <th scope="col">State</th>
                        <th scope="col">Attempts</th>
                        <th scope="col">Success / fail</th>
                        <th scope="col">Last attempt</th>
                        <th scope="col">Last success</th>
                        <th scope="col">Last failure</th>
                      </tr>
                    </thead>
                    <tbody>
                      {peers.peers.map((peer) => (
                        <tr key={peer.face}>
                          <td><strong>{peer.face}</strong></td>
                          <td>{peer.service}</td>
                          <td>{peer.bearer}</td>
                          <td className="mono">
                            {peer.host ? `${peer.host}${peer.port ? `:${peer.port}` : ''}` : '—'}
                          </td>
                          <td>{peer.configured ? 'Yes' : 'No'}</td>
                          <td><StatusChip label={peer.state}
                            tone={peer.state === 'UP' ? 'good' : peer.state === 'DOWN' ? 'bad' : peer.state === 'IDLE' ? 'warn' : 'info'} /></td>
                          <td>{fmtInt(peer.attempts)}</td>
                          <td>{fmtInt(peer.successes)} / {fmtInt(peer.failures)}</td>
                          <td>{fmtDateTime(peer.lastAttemptAt)}</td>
                          <td>{fmtDateTime(peer.lastSuccessAt)}</td>
                          <td>
                            {fmtDateTime(peer.lastFailureAt)}
                            {peer.lastCode ? <><br /><span className="mono note">{peer.lastCode}</span></> : null}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <p className="note">
                  Environment {peers.environment} · data {peers.dataSource} · evaluation every{' '}
                  {peers.settings.evalSeconds} s over {peers.settings.windowMinutes} min, after{' '}
                  {peers.settings.minFinished} finished request(s). Error rate {peers.settings.errorRateThreshold},
                  {' '}{peers.settings.peerFailureThreshold} consecutive transport failure(s) for PEER_DOWN,
                  {' '}{peers.settings.cellGapThreshold} cell-plan miss(es) for CELL_DB_GAP
                  {peers.settings.sloP95Ms ? `, p95 objective ${peers.settings.sloP95Ms} ms` : ', no p95 objective agreed'}.
                </p>
              </>
            )}
          </Panel>

          <Panel title="5G location service interfaces"
            subtitle="Named next to the 3GPP service they carry. Only the Le/MLP northbound is built.">
            {!canViewPeers ? (
              <p className="note">VIEW_DASHBOARD is required to read the interface list.</p>
            ) : peers === null ? (
              <p className="empty">No interface list was returned.</p>
            ) : (
              <div className="table-wrap">
                <table className="data compact">
                  <thead>
                    <tr>
                      <th scope="col">Interface</th>
                      <th scope="col">3GPP service</th>
                      <th scope="col">Bearer</th>
                      <th scope="col">State</th>
                      <th scope="col">What that means</th>
                    </tr>
                  </thead>
                  <tbody>
                    {peers.interfaces.map((row) => (
                      <tr key={row.label}>
                        <td><strong>{row.label}</strong></td>
                        <td>{row.service}</td>
                        <td>{row.bearer}</td>
                        <td><StatusChip label={row.state === 'BUILT' ? 'BUILT' : row.state === 'NOT_APPLICABLE' ? 'N/A' : 'NOT BUILT'}
                          tone={row.state === 'BUILT' ? 'good' : row.state === 'NOT_APPLICABLE' ? 'neutral' : 'warn'} /></td>
                        <td>{row.note}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          <div className="panel-grid">
            <Panel title="Routing and realms"
              subtitle="Environment-managed. This console does not hold a route editor, and nothing here claims otherwise.">
              <ul className="plain-list">
                <li>M3UA association: the host and port come from deployment settings; the ASP state above is observed from traffic, not by probing.</li>
                <li>SCCP global title routes: loaded from deployment settings at start-up. A route change is a restart, not a console edit.</li>
                <li>Diameter peers and realms: loaded from deployment settings. This build has no realm-to-route table to edit.</li>
                <li>SBI endpoints: service discovery is environment-managed; this console does not register or remove a producer.</li>
                <li>No vendor point code, GT or address is committed to this repository or printed by this screen.</li>
              </ul>
            </Panel>
            <Panel title="Cell-to-coordinate overrides"
              subtitle="Not built.">
              <p className="note">
                A Cell-ID answer uses the plan row for that cell. There is no override table in
                this build, so a single cell cannot be pointed somewhere else without changing the
                plan. Adding overrides would need its own schema, precedence rule and tests; this
                screen will not pretend a missing feature exists.
              </p>
            </Panel>
          </div>

          <Panel title="Cell plan"
            subtitle="What the gateway can answer Cell-ID from. Quality is a completeness score, not an accuracy measurement."
            actions={canManage ? (
              <label className="note">
                RAT{' '}
                <select value={planRat} onChange={(event) => setPlanRat(event.target.value)}>
                  <option value="">All</option>
                  {RATS.map((rat) => <option key={rat} value={rat}>{rat}</option>)}
                </select>
              </label>
            ) : undefined}>
            {!canManage ? (
              <p className="note">MANAGE_CONFIG is required to read the cell plan from this console.</p>
            ) : plan === null ? (
              <p className="empty">{planLoading ? 'Reading the plan…' : 'No cell plan was returned.'}</p>
            ) : plan.total === 0 ? (
              <p className="empty">
                No cell row matches this filter. An import writes the plan through the deployment
                admin key, not through this screen.
              </p>
            ) : (
              <>
                <p className="note">
                  {fmtInt(plan.total)} row(s){plan.rat ? ` for ${plan.rat}` : ''} ·{' '}
                  {plan.byRat.map((row) => `${row.name} ${fmtInt(row.count)}`).join(' · ')} ·{' '}
                  quality {plan.byQuality.map((row) => `${row.name} ${fmtInt(row.count)}`).join(', ')}
                </p>
                <CellPlanMap cells={plan.cells} />
                <div className="table-wrap">
                  <table className="data compact">
                    <caption>
                      Showing {plan.cells.length} of {fmtInt(plan.total)} row(s), ordered by RAT and identity
                    </caption>
                    <thead>
                      <tr>
                        <th scope="col">RAT</th>
                        <th scope="col">Cell identity</th>
                        <th scope="col">Site</th>
                        <th scope="col">Area</th>
                        <th scope="col">Shape</th>
                        <th scope="col">Radius</th>
                        <th scope="col">Quality</th>
                        <th scope="col">Updated</th>
                      </tr>
                    </thead>
                    <tbody>
                      {plan.cells.map((cell) => (
                        <tr key={`${cell.rat}:${cell.cellIdentity}`}>
                          <td>{cell.rat}</td>
                          <td className="mono">{cell.cellIdentity}</td>
                          <td>{cell.siteName ?? '—'}</td>
                          <td className="mono">{cell.areaCode}</td>
                          <td>{cell.shape}</td>
                          <td>{cell.radiusM ? `${fmtInt(cell.radiusM)} m` : '—'}</td>
                          <td>{cell.dataQuality === undefined ? '—' : cell.dataQuality}</td>
                          <td>{fmtDateTime(cell.updatedAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <p className="note">
                  The map plots stored coordinates only; it has no basemap and no live feed. A
                  dry-run import is the product until an operator commits; a bad row refuses the
                  whole file. This screen is read-only and does not hold the admin key.
                </p>
              </>
            )}
          </Panel>
        </>
      ) : null}

      {tab === 'reference' ? (
        <>
          <Panel title="Reference methods and what they need"
            subtitle="Vendor reference points, not measurements from this deployment.">
            <p className="note">
              Every accuracy below is a vendor reference figure. None has been verified on a real
              network from this gateway, and none is used to judge a stored result. The result a
              client receives always names its own method, uncertainty, confidence and age.
            </p>
            <div className="table-wrap">
              <table className="data compact">
                <thead>
                  <tr>
                    <th scope="col">Method</th>
                    <th scope="col">RATs</th>
                    <th scope="col">Vendor reference accuracy</th>
                    <th scope="col">What the network must provide</th>
                    <th scope="col">Built in this deployment</th>
                    <th scope="col">Verified on a real network</th>
                  </tr>
                </thead>
                <tbody>
                  {REFERENCE_METHODS.map((row) => (
                    <tr key={row.method}>
                      <td><strong>{row.method}</strong></td>
                      <td>{row.rats}</td>
                      <td>{row.reference}</td>
                      <td>{row.needs}</td>
                      <td>{row.built}</td>
                      <td><StatusChip label="NO" tone={row.method === 'Basic Cell-ID' ? 'warn' : 'neutral'} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>

          <div className="panel-grid">
            <Panel title="Capability profile"
              subtitle="Per-tenant, and read by the client API.">
              <p className="note">
                A profile can list the methods a RAT may attempt, and the validator requires
                CELL_ID as the fallback whenever an engine is named. This build answers Cell-ID
                only, so a profile that promises anything else would be a claim the gateway cannot
                keep. The administration endpoint that edits a profile is not built; this screen
                therefore shows no profile rather than inventing one. The bundled profile states
                <span className="mono"> supl.implemented: false </span> and the validator refuses a
                profile that is silent on it: this service has no SLP, no SET and no assist-data
                endpoint, so SUPL is not implemented.
              </p>
            </Panel>
            <Panel title="What this gateway can say today"
              subtitle="No method is claimed before it answers.">
              <ul className="plain-list">
                <li>Cell-ID from a stored plan row, with the plan radius carried as uncertainty.</li>
                <li>E-CID, OTDOA, A-GNSS, U-TDOA, E-OTD and TDOA are not implemented.</li>
                <li>No E-SMLC is present. Building one is out of scope for this roadmap.</li>
                <li>SUPL is not implemented: there is no SLP, no SET and no assist-data endpoint.</li>
                <li>A request that asks for a method the deployment lacks says so; it does not fall back silently to a different claim.</li>
              </ul>
            </Panel>
          </div>
        </>
      ) : null}

      {tab === 'limits' ? (
        <>
          <div className="panel-grid">
            <Panel title="Privacy and consent" subtitle="Stored per client, subject and purpose.">
              <ul className="plain-list">
                <li>A location request is refused unless a consent row covers that client, subject and purpose.</li>
                <li>The refusal is audited. The audit view names people and clients, never a coordinate or a raw identifier.</li>
                <li>Reads mask the MSISDN and IMSI; revealing them needs REVEAL_IDENTIFIERS and is itself audited.</li>
                <li>Consent wording is part of the consent record. This console does not edit it in this build; a change is a deployment or client-onboarding step.</li>
              </ul>
            </Panel>
            <Panel title="Retention" subtitle="Environment-managed, and not wired to a deletion job in this build.">
              <ul className="plain-list">
                <li>Requests, stored results, CDRs and audit rows stay until someone deletes them.</li>
                <li>No scheduler on this screen shortens or extends retention, because no retention job runs.</li>
                <li>An operator who needs a retention rule must treat it as a database deployment task, with the reason recorded outside the console.</li>
              </ul>
            </Panel>
            <Panel title="Notifier credentials" subtitle="Sealed with the deployment key.">
              <ul className="plain-list">
                <li>Webhook and tracking-notifier signing secrets are generated on creation and returned once.</li>
                <li>The gateway stores them sealed; no read path returns them again and no log line carries them.</li>
                <li>Manage them on the Tracking screen under Notification channels. This screen does not show a secret or a key.</li>
              </ul>
            </Panel>
            <Panel title="Licence" subtitle="Not installed.">
              <ul className="plain-list">
                <li>This deployment has no licence file and does not gate or meter requests.</li>
                <li>Usage figures on the dashboard are counts of stored rows, not a billing record.</li>
                <li>Installing a licence, and metering against it, is not built. The screen says so instead of showing a zero balance.</li>
              </ul>
            </Panel>
          </div>
          <Panel title="Honest limits"
            subtitle="What this Administration area does not do.">
            <ul className="plain-list">
              <li>No route, realm or service-discovery editor: those come from deployment settings and a restart.</li>
              <li>No cell import or per-cell override: both need the deployment admin key and a separate workflow.</li>
              <li>No retention schedule and no licence installer.</li>
              <li>No SUPL server or assist-data endpoint; the profile says <span className="mono">implemented: false</span>.</li>
              <li>No capability-profile editor; a profile that cannot be honoured is not shown as if it were in force.</li>
            </ul>
          </Panel>
        </>
      ) : null}
    </div>
  );
}

function CellPlanMap({ cells }: { cells: CellPlanEntry[] }) {
  const points = useMemo(() => cells.filter((cell) => (
    typeof cell.latitude === 'number' && Number.isFinite(cell.latitude)
    && typeof cell.longitude === 'number' && Number.isFinite(cell.longitude)
  )), [cells]);

  if (points.length === 0) {
    return (
      <div className="map-frame">
        <p className="empty">Nothing to plot: no row on this page carries coordinates.</p>
      </div>
    );
  }

  const width = 680;
  const height = 300;
  const pad = 32;
  const plotW = width - pad * 2;
  const plotH = height - pad * 2;

  const lats = points.map((cell) => cell.latitude as number);
  const lons = points.map((cell) => cell.longitude as number);
  const minLat = Math.min(...lats);
  const maxLat = Math.max(...lats);
  const minLon = Math.min(...lons);
  const maxLon = Math.max(...lons);
  const spanLat = Math.max(maxLat - minLat, 0.002);
  const spanLon = Math.max(maxLon - minLon, 0.002);
  const south = minLat - (spanLat - (maxLat - minLat)) / 2;
  const west = minLon - (spanLon - (maxLon - minLon)) / 2;
  const xOf = (lon: number) => pad + ((lon - west) / spanLon) * plotW;
  const yOf = (lat: number) => pad + plotH - ((lat - south) / spanLat) * plotH;

  return (
    <div className="map-frame">
      <svg className="trail-map" viewBox={`0 0 ${width} ${height}`} role="img"
        aria-label={`${points.length} cell position(s) between latitude ${south.toFixed(4)} and ${(south + spanLat).toFixed(4)}, longitude ${west.toFixed(4)} and ${(west + spanLon).toFixed(4)}`}>
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
        {points.map((cell) => (
          <g key={`${cell.rat}:${cell.cellIdentity}`}>
            <title>{`${cell.rat} ${cell.cellIdentity}${cell.siteName ? ` · ${cell.siteName}` : ''}${cell.dataQuality !== undefined ? ` · quality ${cell.dataQuality}` : ''}`}</title>
            <circle className="trail-point"
              cx={xOf(cell.longitude as number)} cy={yOf(cell.latitude as number)} r={5} />
          </g>
        ))}
      </svg>
      <div className="legend">
        <span className="legend-item"><i className="swatch swatch-map" />Stored cell position</span>
        <span className="legend-item note">
          {spanLat.toFixed(4)}° of latitude ≈ {fmtInt(Math.round(spanLat * 111_320))} m; the plot is a plain
          equirectangular projection, not a basemap.
        </span>
      </div>
    </div>
  );
}
