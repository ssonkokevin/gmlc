import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { api, ApiError } from '../api';
import { Panel, StatusChip } from '../components';
import { fmtDateTime, fmtInt } from '../format';
import type {
  AccessPolicy,
  ActiveSession,
  AdminClient,
  AuditRow,
  BreakGlassGrant,
  CreatedOpsUser,
  OpsUserView,
  RoleMatrix,
  Session,
} from '../types';

type TabKey = 'people' | 'sessions' | 'audit' | 'policy' | 'break-glass' | 'clients';

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'people', label: 'People' },
  { key: 'sessions', label: 'Sessions' },
  { key: 'audit', label: 'Audit log' },
  { key: 'policy', label: 'Policy & roles' },
  { key: 'break-glass', label: 'Break-glass' },
  { key: 'clients', label: 'API clients' },
];

const ROLES = ['ADMIN', 'OPERATOR', 'ANALYST', 'VIEWER'];

function problemText(caught: unknown): string {
  if (!(caught instanceof ApiError)) {
    return 'The gateway did not answer. What is on screen is the last successful read.';
  }
  return caught.message;
}

function outcomeTone(outcome: string) {
  switch (outcome) {
    case 'ALLOWED':
      return 'good' as const;
    case 'DENIED':
      return 'bad' as const;
    case 'ERROR':
      return 'warn' as const;
    default:
      return 'neutral' as const;
  }
}

export function Users(props: { session: Session; onExpired: () => void }) {
  const { session, onExpired } = props;
  const [elevated, setElevated] = useState(Boolean(session.breakGlass));
  const canManageUsers = session.permissions.includes('MANAGE_USERS') || elevated;
  const canManageClients = session.permissions.includes('MANAGE_CLIENTS') || elevated;

  const [tab, setTab] = useState<TabKey>(canManageUsers ? 'people' : 'break-glass');
  const [users, setUsers] = useState<OpsUserView[]>([]);
  const [sessions, setSessions] = useState<ActiveSession[]>([]);
  const [roles, setRoles] = useState<RoleMatrix | null>(null);
  const [policy, setPolicy] = useState<AccessPolicy | null>(null);
  const [clients, setClients] = useState<AdminClient[]>([]);
  const [grants, setGrants] = useState<BreakGlassGrant[]>([]);
  const [grantStatus, setGrantStatus] = useState('ALL');
  const [audit, setAudit] = useState<AuditRow[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);
  const [auditQuery, setAuditQuery] = useState({ action: '', outcome: 'ALL', actor: '', limit: 100 });
  const [auditBefore, setAuditBefore] = useState<number | undefined>(undefined);
  const [auditNonce, setAuditNonce] = useState(0);
  const [loading, setLoading] = useState(canManageUsers);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [credentials, setCredentials] = useState<{ username: string; password: string; totp: string } | null>(null);
  const [activeGrant, setActiveGrant] = useState<BreakGlassGrant | null>(null);
  const [emergencyReason, setEmergencyReason] = useState('');
  const [emergencyCode, setEmergencyCode] = useState('');
  const [emergencyBusy, setEmergencyBusy] = useState(false);
  const [emergencyError, setEmergencyError] = useState<string | null>(null);
  const [reviewing, setReviewing] = useState<{ grant: BreakGlassGrant; note: string } | null>(null);
  const [reviewError, setReviewError] = useState<string | null>(null);

  const handle = useCallback((caught: unknown) => {
    if (caught instanceof ApiError && caught.status === 401) {
      onExpired();
      return;
    }
    setError(problemText(caught));
  }, [onExpired]);

  const loadAccess = useCallback(async () => {
    if (!canManageUsers) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const [nextUsers, nextSessions, nextRoles, nextPolicy] = await Promise.all([
        api.opsUsers(),
        api.activeSessions(),
        api.roleMatrix(),
        api.accessPolicy(),
      ]);
      setUsers(nextUsers);
      setSessions(nextSessions);
      setRoles(nextRoles);
      setPolicy(nextPolicy);
      const nextGrants = await api.breakGlassGrants(grantStatus);
      setGrants(nextGrants);
      const mine = nextGrants.find((grant) => grant.active && grant.userId === session.userId);
      setActiveGrant((current) => current ?? mine ?? null);
    } catch (caught) {
      handle(caught);
    } finally {
      setLoading(false);
    }
  }, [canManageUsers, grantStatus, handle, session.userId]);

  useEffect(() => {
    void loadAccess();
  }, [loadAccess]);

  useEffect(() => {
    if (!canManageClients) {
      return;
    }
    let live = true;
    api
      .adminClients()
      .then((next) => {
        if (live) {
          setClients(next);
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
  }, [canManageClients, handle]);

  const loadAudit = useCallback(async () => {
    if (!canManageUsers) {
      return;
    }
    setAuditLoading(true);
    setError(null);
    try {
      setAudit(await api.auditLog({
        action: auditQuery.action.trim() || undefined,
        outcome: auditQuery.outcome,
        actor: auditQuery.actor.trim() || undefined,
        limit: auditQuery.limit,
        before: auditBefore,
      }));
    } catch (caught) {
      handle(caught);
    } finally {
      setAuditLoading(false);
    }
  }, [canManageUsers, auditQuery, auditBefore, handle]);

  useEffect(() => {
    if (tab === 'audit' && canManageUsers) {
      void loadAudit();
    }
    // The query is applied deliberately; typing in a filter must not fire a read per keystroke.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, canManageUsers, auditNonce]);

  const startElevation = async (event: FormEvent) => {
    event.preventDefault();
    setEmergencyError(null);
    if (emergencyReason.trim().length < 20) {
      setEmergencyError('Write why the elevation is needed, at least 20 characters. It is stored in the audit trail.');
      return;
    }
    if (!emergencyCode.trim()) {
      setEmergencyError('Enter the sealed deployment code.');
      return;
    }
    setEmergencyBusy(true);
    try {
      const grant = await api.startBreakGlass(emergencyReason.trim(), emergencyCode);
      setEmergencyCode('');
      setEmergencyReason('');
      setActiveGrant(grant);
      setElevated(true);
      setNotice(`Emergency elevation is active until ${fmtDateTime(grant.expiresAt)}. A second administrator must review it.`);
      setGrantStatus('ALL');
      try {
        const nextGrants = await api.breakGlassGrants('ALL');
        setGrants(nextGrants);
        const [nextRoles, nextPolicy, nextUsers, nextSessions] = await Promise.all([
          api.roleMatrix(),
          api.accessPolicy(),
          api.opsUsers(),
          api.activeSessions(),
        ]);
        setRoles(nextRoles);
        setPolicy(nextPolicy);
        setUsers(nextUsers);
        setSessions(nextSessions);
      } catch {
        // The grant is in force even if a follow-up read fails.
      }
    } catch (caught) {
      setEmergencyError(problemText(caught));
    } finally {
      setEmergencyBusy(false);
    }
  };

  const endElevation = async () => {
    setEmergencyBusy(true);
    setEmergencyError(null);
    try {
      const grant = await api.endBreakGlass();
      setActiveGrant(grant);
      setElevated(false);
      setNotice('Emergency elevation ended. The ADMIN permission set no longer applies to this session.');
      if (canManageUsers) {
        setGrants(await api.breakGlassGrants(grantStatus));
      }
    } catch (caught) {
      setEmergencyError(problemText(caught));
    } finally {
      setEmergencyBusy(false);
    }
  };

  const submitReview = async (event: FormEvent) => {
    event.preventDefault();
    if (!reviewing) {
      return;
    }
    setReviewError(null);
    if (reviewing.note.trim().length < 10) {
      setReviewError('A review needs a note of at least 10 characters.');
      return;
    }
    try {
      await api.reviewBreakGlass(reviewing.grant.grantId, reviewing.note.trim());
      setNotice('Review recorded.');
      setReviewing(null);
      setGrants(await api.breakGlassGrants(grantStatus));
    } catch (caught) {
      setReviewError(problemText(caught));
    }
  };

  const reloadGrants = async (status = grantStatus) => {
    if (!canManageUsers) {
      return;
    }
    try {
      setGrants(await api.breakGlassGrants(status));
    } catch (caught) {
      handle(caught);
    }
  };

  const activeForMe = activeGrant
    ?? grants.find((grant) => grant.active && grant.userId === session.userId)
    ?? null;

  return (
    <div className="dashboard">
      <div className="page-head">
        <div>
          <h1>Users &amp; access</h1>
          <p>
            Accounts, roles, live sessions, the audit trail, the sign-in policy and emergency
            access. A role decides what a session may do on its next request; nothing here changes
            what happened in the past. The audit view names people and clients, never a coordinate
            or a raw subscriber identifier.
          </p>
        </div>
        <div className="page-actions">
          <button type="button" className="button" onClick={() => void loadAccess()} disabled={loading || !canManageUsers}>
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

      {error ? <p className="form-error" role="alert">{error}</p> : null}
      {notice ? <p className="note" role="status">{notice}</p> : null}

      {activeForMe && activeForMe.active ? (
        <p className="note">
          This session carries an emergency ADMIN grant until {fmtDateTime(activeForMe.expiresAt)}.
          The assigned role shown on the roster is unchanged; the extra permission set applies to
          this session only.
        </p>
      ) : null}

      {credentials ? (
        <Panel title="Credentials — shown once"
          subtitle="Give these to the operator over a channel you trust. The gateway stores only a hash and cannot display them again.">
          <p className="note">
            <strong>{credentials.username}</strong> · one-time password{' '}
            <span className="mono secret-value">{credentials.password}</span> · TOTP secret{' '}
            <span className="mono secret-value">{credentials.totp}</span>
          </p>
          <div className="form-actions">
            <button type="button" className="button" onClick={() => setCredentials(null)}>
              I have stored them
            </button>
          </div>
        </Panel>
      ) : null}

      <div className="segmented tabs" role="tablist" aria-label="Users and access views">
        {TABS.map((item) => (
          <button key={item.key} type="button" role="tab" aria-selected={tab === item.key}
            className={tab === item.key ? 'segment active' : 'segment'}
            onClick={() => setTab(item.key)}>
            {item.label}
          </button>
        ))}
      </div>

      {tab === 'people' ? (
        <>
          <Panel title="Operator accounts"
            subtitle="Role changes take effect on the account's next request; a disabled account cannot sign in.">
            {!canManageUsers ? (
              <p className="note">MANAGE_USERS is required to read or change accounts.</p>
            ) : users.length === 0 ? (
              <p className="empty">{loading ? 'Reading accounts…' : 'No account was returned.'}</p>
            ) : (
              <div className="table-wrap">
                <table className="data compact">
                  <thead>
                    <tr>
                      <th scope="col">Operator</th>
                      <th scope="col">Role</th>
                      <th scope="col">State</th>
                      <th scope="col">Last sign-in</th>
                      <th scope="col">Created</th>
                      <th scope="col">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {users.map((user) => (
                      <UserRow key={user.userId} user={user} roles={roles}
                        isSelf={user.userId === session.userId}
                        onChanged={(message) => {
                          setNotice(message);
                          void loadAccess();
                        }}
                        onError={handle} />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          {canManageUsers ? (
            <CreateUser roles={roles} onCreated={(created) => {
              setCredentials({
                username: created.user.username,
                password: created.oneTimePassword,
                totp: created.totpSecret,
              });
              setNotice(`${created.user.displayName} created as ${created.user.role}.`);
              void loadAccess();
            }} onError={handle} />
          ) : (
            <Panel title="Create an account">
              <p className="note">This account does not carry MANAGE_USERS, so it cannot create one.</p>
            </Panel>
          )}

          <Panel title="What a role does not grant"
            subtitle="The server checks the permission on each request; a hidden button is not a control.">
            <ul className="plain-list">
              <li>The roster shows the assigned role. Break-glass adds ADMIN for one session and leaves the roster unchanged.</li>
              <li>The last enabled administrator cannot be disabled or demoted; the server refuses and says so.</li>
              <li>Removing a role does not end a session; use Sessions to sign one out, or the account's next request already sees the new role.</li>
            </ul>
          </Panel>
        </>
      ) : null}

      {tab === 'sessions' ? (
        <Panel title="Live sessions"
          subtitle="Every session with a valid idle and absolute clock. Ending one takes effect on its next request.">
          {!canManageUsers ? (
            <p className="note">MANAGE_USERS is required to read or end another operator's session.</p>
          ) : sessions.length === 0 ? (
            <p className="empty">{loading ? 'Reading sessions…' : 'No live session was returned.'}</p>
          ) : (
            <div className="table-wrap">
              <table className="data compact">
                <thead>
                  <tr>
                    <th scope="col">Operator</th>
                    <th scope="col">Role</th>
                    <th scope="col">Remote address</th>
                    <th scope="col">Started</th>
                    <th scope="col">Last seen</th>
                    <th scope="col">Expires</th>
                    <th scope="col">Action</th>
                  </tr>
                </thead>
                <tbody>
                  {sessions.map((row) => (
                    <tr key={row.sessionId}>
                      <td>
                        <strong>{row.displayName}</strong>
                        <br /><span className="mono note">{row.username}</span>
                      </td>
                      <td>{row.role}</td>
                      <td className="mono">{row.remoteAddr ?? '—'}</td>
                      <td>{fmtDateTime(row.createdAt)}</td>
                      <td>{fmtDateTime(row.lastSeenAt)}</td>
                      <td>{fmtDateTime(row.expiresAt)}</td>
                      <td>
                        {row.current ? (
                          <StatusChip label="THIS SESSION" tone="info" />
                        ) : (
                          <button type="button" className="button button-small"
                            onClick={async () => {
                              setError(null);
                              try {
                                await api.signOutSession(row.sessionId);
                                setNotice(`${row.displayName}'s session ended.`);
                                await loadAccess();
                              } catch (caught) {
                                handle(caught);
                              }
                            }}>
                            Sign out
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
      ) : null}

      {tab === 'audit' ? (
        <Panel title="Audit log"
          subtitle="Newest first. The detail is what the trail stored; it carries no position and no raw identifier."
          actions={canManageUsers ? (
            <form className="row-actions" onSubmit={(event) => {
              event.preventDefault();
              setAuditBefore(undefined);
              setAuditNonce((value) => value + 1);
            }}>
              <input aria-label="Action filter" placeholder="Action, e.g. LOCATION_REQUEST"
                value={auditQuery.action}
                onChange={(event) => setAuditQuery((current) => ({ ...current, action: event.target.value }))} />
              <select aria-label="Outcome filter" value={auditQuery.outcome}
                onChange={(event) => setAuditQuery((current) => ({ ...current, outcome: event.target.value }))}>
                <option value="ALL">All outcomes</option>
                <option value="ALLOWED">Allowed</option>
                <option value="DENIED">Denied</option>
                <option value="ERROR">Error</option>
              </select>
              <input aria-label="Actor filter" placeholder="Actor username or client"
                value={auditQuery.actor}
                onChange={(event) => setAuditQuery((current) => ({ ...current, actor: event.target.value }))} />
              <select aria-label="Row limit" value={String(auditQuery.limit)}
                onChange={(event) => setAuditQuery((current) => ({ ...current, limit: Number(event.target.value) }))}>
                <option value="50">50 rows</option>
                <option value="100">100 rows</option>
                <option value="200">200 rows</option>
              </select>
              <button type="submit" className="button button-small">Apply</button>
            </form>
          ) : undefined}>
          {!canManageUsers ? (
            <p className="note">MANAGE_USERS is required to read the audit trail.</p>
          ) : auditLoading ? (
            <p className="empty">Reading the trail…</p>
          ) : audit.length === 0 ? (
            <p className="empty">No audit row matches these filters.</p>
          ) : (
            <>
              <div className="table-wrap">
                <table className="data compact">
                  <thead>
                    <tr>
                      <th scope="col">When</th>
                      <th scope="col">Action</th>
                      <th scope="col">Outcome</th>
                      <th scope="col">Actor</th>
                      <th scope="col">From</th>
                      <th scope="col">Request / subject</th>
                      <th scope="col">Detail</th>
                    </tr>
                  </thead>
                  <tbody>
                    {audit.map((row) => (
                      <tr key={row.auditId}>
                        <td>{fmtDateTime(row.occurredAt)}<br /><span className="note">#{row.auditId}</span></td>
                        <td className="mono">{row.action}</td>
                        <td><StatusChip label={row.outcome} tone={outcomeTone(row.outcome)} /></td>
                        <td>
                          {row.actorType === 'OPERATOR'
                            ? <>{row.actorDisplayName}<br /><span className="mono note">{row.actorUsername}</span></>
                            : row.actorType === 'CLIENT'
                              ? <>{row.clientName}<br /><span className="note">API client</span></>
                              : <span className="note">System decision</span>}
                        </td>
                        <td className="mono">{row.remoteAddr ?? '—'}</td>
                        <td className="mono">
                          {row.requestId ? `${row.requestId.slice(0, 8)}…` : '—'}
                          {row.subjectRef ? <><br /><span className="note">{row.subjectRef}</span></> : null}
                        </td>
                        <td>
                          {row.detail ? (
                            <details>
                              <summary>View</summary>
                              <pre className="mono note">{JSON.stringify(row.detail, null, 2)}</pre>
                            </details>
                          ) : '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="form-actions">
                <button type="button" className="button button-small"
                  onClick={() => {
                    const last = audit[audit.length - 1];
                    setAuditBefore(last.auditId);
                    setAuditNonce((value) => value + 1);
                  }}>
                  Older rows
                </button>
                {auditBefore !== undefined ? (
                  <button type="button" className="button button-small"
                    onClick={() => {
                      setAuditBefore(undefined);
                      setAuditNonce((value) => value + 1);
                    }}>
                    Back to newest
                  </button>
                ) : null}
                <span className="note">{fmtInt(audit.length)} row(s) on this page.</span>
              </div>
            </>
          )}
        </Panel>
      ) : null}

      {tab === 'policy' ? (
        <>
          <div className="panel-grid">
            <Panel title="Sign-in policy" subtitle="What the gateway enforces, read from the running configuration.">
              {!canManageUsers ? (
                <p className="note">MANAGE_USERS is required to read the policy.</p>
              ) : policy === null ? (
                <p className="empty">{loading ? 'Reading policy…' : 'The policy was not returned.'}</p>
              ) : (
                <table className="data compact">
                  <tbody>
                    <tr><th scope="row">Second factor</th>
                    <td>{policy.mfaRequired ? `TOTP, ${policy.totpDigits} digits every ${policy.totpPeriodSeconds} s` : 'Not required'}</td></tr>
                    <tr><th scope="row">TOTP issuer</th><td className="mono">{policy.totpIssuer}</td></tr>
                    <tr><th scope="row">Password floor</th><td>{policy.passwordMinLength} characters</td></tr>
                    <tr><th scope="row">Password hash</th><td>{policy.passwordHash} · memory {fmtInt(policy.argon2MemoryKib)} KiB · iterations {policy.argon2Iterations} · parallelism {policy.argon2Parallelism}</td></tr>
                    <tr><th scope="row">Lockout</th><td>{policy.lockoutThreshold} failed sign-ins · {policy.lockoutMinutes} min</td></tr>
                    <tr><th scope="row">Session clocks</th><td>idle {policy.sessionIdleMinutes} min · absolute {policy.sessionAbsoluteHours} h</td></tr>
                    <tr><th scope="row">Break-glass</th><td>{policy.breakGlassConfigured ? `Configured · ${policy.breakGlassMinutes} min · grants ${policy.breakGlassScope} for one session` : 'Disabled: no emergency code hash is configured'}</td></tr>
                  </tbody>
                </table>
              )}
            </Panel>
            <Panel title="What the policy cannot do" subtitle="Honest limits.">
              <ul className="plain-list">
                <li>There is no self-service TOTP re-enrolment; a lost authenticator is recovered by an administrator setting a new password and issuing a new secret.</li>
                <li>Password history and expiry are not enforced.</li>
                <li>A session is checked on every request, but a role change does not tear down a session that holds it.</li>
                <li>The emergency code exists only as a SHA-256 hash in the environment; it is never stored, logged or shown.</li>
              </ul>
            </Panel>
          </div>

          <Panel title="Role matrix"
            subtitle="Each role carries exactly these permissions; the server checks the permission, not the screen.">
            {!canManageUsers || roles === null ? (
              <p className="note">MANAGE_USERS is required to read the matrix.</p>
            ) : (
              <div className="table-wrap">
                <table className="data compact">
                  <thead>
                    <tr>
                      <th scope="col">Permission</th>
                      {roles.roles.map((role) => <th key={role.role} scope="col">{role.role}</th>)}
                    </tr>
                  </thead>
                  <tbody>
                    {roles.permissions.map((permission) => (
                      <tr key={permission}>
                        <th scope="row" className="mono">{permission}</th>
                        {roles.roles.map((role) => (
                          <td key={role.role}>{role.permissions.includes(permission) ? 'yes' : '—'}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        </>
      ) : null}

      {tab === 'break-glass' ? (
        <>
          {policy && !policy.breakGlassConfigured ? (
            <p className="note">
              Break-glass is disabled: this deployment has no emergency code hash. Starting an
              elevation is refused with <span className="mono">BREAK_GLASS_DISABLED</span>.
            </p>
          ) : null}

          {activeForMe && activeForMe.active ? (
            <Panel title="Emergency elevation in force"
              subtitle="The ADMIN permission set applies to this session until it ends or expires.">
              <p className="note">
                Started {fmtDateTime(activeForMe.grantedAt)} · expires {fmtDateTime(activeForMe.expiresAt)}.
                Reason stored with the grant: {activeForMe.reason}
              </p>
              <div className="form-actions">
                <button type="button" className="button" onClick={() => void endElevation()} disabled={emergencyBusy}>
                  {emergencyBusy ? 'Ending…' : 'End elevation now'}
                </button>
              </div>
            </Panel>
          ) : (
            <Panel title="Start an emergency elevation"
              subtitle="The sealed deployment code is the second factor; it is compared by hash and never stored or logged.">
              <form className="stack" onSubmit={(event) => void startElevation(event)}>
                <div className="field">
                  <label htmlFor="emergency-reason">Why is elevation needed? (at least 20 characters)</label>
                  <textarea id="emergency-reason" value={emergencyReason} maxLength={500} required
                    onChange={(event) => setEmergencyReason(event.target.value)} />
                  <p className="field-hint">The reason is stored on the grant and in the audit trail; it is not a secret.</p>
                </div>
                <div className="field">
                  <label htmlFor="emergency-code">Sealed deployment code</label>
                  <input id="emergency-code" type="password" value={emergencyCode} autoComplete="off"
                    onChange={(event) => setEmergencyCode(event.target.value)} required />
                  <p className="field-hint">
                    Cleared from this form after submission. The gateway holds only its SHA-256 hash.
                  </p>
                </div>
                {emergencyError ? <p className="form-error" role="alert">{emergencyError}</p> : null}
                <div className="form-actions">
                  <button type="submit" className="button button-primary" disabled={emergencyBusy}>
                    {emergencyBusy ? 'Checking…' : 'Start 30-minute elevation'}
                  </button>
                </div>
              </form>
            </Panel>
          )}

          <Panel title="Grant history"
            subtitle="A grant keeps a review obligation until a second administrator records what the elevation was used for."
            actions={canManageUsers ? (
              <label className="note">
                Status{' '}
                <select value={grantStatus} onChange={(event) => {
                  setGrantStatus(event.target.value);
                  void reloadGrants(event.target.value);
                }}>
                  <option value="ALL">All</option>
                  <option value="PENDING">Review pending</option>
                  <option value="REVIEWED">Reviewed</option>
                </select>
              </label>
            ) : undefined}>
            {!canManageUsers ? (
              <p className="note">MANAGE_USERS is required to read grants raised by other operators.</p>
            ) : grants.length === 0 ? (
              <p className="empty">No grant matches this filter.</p>
            ) : (
              <div className="table-wrap">
                <table className="data compact">
                  <thead>
                    <tr>
                      <th scope="col">Operator</th>
                      <th scope="col">Window</th>
                      <th scope="col">Reason</th>
                      <th scope="col">State</th>
                      <th scope="col">Review</th>
                      <th scope="col">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {grants.map((grant) => (
                      <tr key={grant.grantId}>
                        <td>{grant.displayName}<br /><span className="mono note">{grant.username}</span></td>
                        <td>
                          {fmtDateTime(grant.grantedAt)} → {fmtDateTime(grant.expiresAt)}
                          {grant.endedAt ? <><br /><span className="note">ended {fmtDateTime(grant.endedAt)}</span></> : null}
                        </td>
                        <td>{grant.reason}</td>
                        <td>
                          <StatusChip label={grant.active ? 'ACTIVE' : 'ENDED'}
                            tone={grant.active ? 'warn' : 'neutral'} />
                        </td>
                        <td>
                          <StatusChip label={grant.reviewStatus}
                            tone={grant.reviewStatus === 'REVIEWED' ? 'good' : grant.reviewDue ? 'bad' : 'neutral'} />
                          {grant.reviewedByName ? <><br /><span className="note">{grant.reviewedByName}</span></> : null}
                          {grant.reviewNote ? <><br /><span className="note">{grant.reviewNote}</span></> : null}
                        </td>
                        <td>
                          {grant.reviewDue && grant.userId !== session.userId ? (
                            <button type="button" className="button button-small"
                              onClick={() => {
                                setReviewing({ grant, note: '' });
                                setReviewError(null);
                              }}>
                              Review
                            </button>
                          ) : grant.userId === session.userId && grant.reviewDue ? (
                            <span className="note">Another administrator must review this</span>
                          ) : '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          {reviewing ? (
            <Panel title={`Review grant for ${reviewing.grant.displayName}`}
              subtitle="You cannot review your own grant; the server refuses that too.">
              <form className="stack" onSubmit={(event) => void submitReview(event)}>
                <div className="field">
                  <label htmlFor="review-note">What was the elevation used for? (at least 10 characters)</label>
                  <textarea id="review-note" value={reviewing.note} maxLength={500} required
                    onChange={(event) => setReviewing({ ...reviewing, note: event.target.value })} />
                </div>
                {reviewError ? <p className="form-error" role="alert">{reviewError}</p> : null}
                <div className="form-actions">
                  <button type="submit" className="button button-primary">Record review</button>
                  <button type="button" className="button" onClick={() => setReviewing(null)}>Cancel</button>
                </div>
              </form>
            </Panel>
          ) : null}
        </>
      ) : null}

      {tab === 'clients' ? (
        <Panel title="API clients"
          subtitle="Limits and permitted purposes. The key itself is never shown; the gateway stores only a hash.">
          {!canManageClients ? (
            <p className="note">MANAGE_CLIENTS is required to read the client register.</p>
          ) : clients.length === 0 ? (
            <p className="empty">No client is registered for this tenant.</p>
          ) : (
            <div className="table-wrap">
              <table className="data compact">
                <thead>
                  <tr>
                    <th scope="col">Client</th>
                    <th scope="col">Purposes</th>
                    <th scope="col">Accuracy ceiling</th>
                    <th scope="col">Rate</th>
                    <th scope="col">Address allowlist</th>
                    <th scope="col">State</th>
                    <th scope="col">Created</th>
                  </tr>
                </thead>
                <tbody>
                  {clients.map((client) => (
                    <tr key={client.clientId}>
                      <td><strong>{client.name}</strong><br /><span className="mono note">{client.clientId}</span></td>
                      <td className="mono">{client.purposes.join(', ') || '—'}</td>
                      <td>{fmtInt(client.maxAccuracyM)} m</td>
                      <td>{fmtInt(client.ratePerSecond)}/s · {fmtInt(client.ratePerDay)}/day</td>
                      <td className="mono">{client.ipAllowlist.length === 0 ? 'Any' : client.ipAllowlist.join(', ')}</td>
                      <td><StatusChip label={client.enabled ? 'ENABLED' : 'DISABLED'}
                        tone={client.enabled ? 'good' : 'neutral'} /></td>
                      <td>{fmtDateTime(client.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="note">
            This screen is read-only. Issuing or rotating a client key, and changing a quota, is a
            deployment procedure in this build; the console does not hold a key that could do it.
          </p>
        </Panel>
      ) : null}
    </div>
  );
}

function UserRow(props: {
  user: OpsUserView;
  roles: RoleMatrix | null;
  isSelf: boolean;
  onChanged: (message: string) => void;
  onError: (caught: unknown) => void;
}) {
  const { user } = props;
  const options = props.roles?.roles.map((role) => role.role) ?? ROLES;
  const [role, setRole] = useState(user.role);
  const [settingPassword, setSettingPassword] = useState(false);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);

  useEffect(() => {
    setRole(user.role);
  }, [user.role]);

  const run = async (work: () => Promise<unknown>, message: string): Promise<boolean> => {
    setBusy(true);
    setLocalError(null);
    try {
      await work();
      props.onChanged(message);
      return true;
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        props.onError(caught);
        return false;
      }
      setLocalError(problemText(caught));
      return false;
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <tr>
        <td>
          <strong>{user.displayName}</strong>{props.isSelf ? <> <StatusChip label="YOU" tone="info" /></> : null}
          <br /><span className="mono note">{user.username}</span>
        </td>
        <td>
          <select aria-label={`Role for ${user.username}`} value={role}
            onChange={(event) => setRole(event.target.value)}>
            {options.map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
          {role !== user.role ? (
            <button type="button" className="button button-small"
              onClick={() => void run(
                () => api.patchOpsUser(user.userId, { role }),
                `${user.displayName} is now ${role}.`)}
              disabled={busy}>
              Save role
            </button>
          ) : null}
        </td>
        <td>
          <StatusChip label={user.enabled ? 'ENABLED' : 'DISABLED'}
            tone={user.enabled ? 'good' : 'neutral'} />
        </td>
        <td>{fmtDateTime(user.lastLoginAt)}</td>
        <td>{fmtDateTime(user.createdAt)}</td>
        <td>
          <div className="row-actions">
            <button type="button" className="button button-small"
              onClick={() => void run(
                () => api.patchOpsUser(user.userId, { enabled: !user.enabled }),
                `${user.displayName} ${user.enabled ? 'disabled' : 'enabled'}.`)}
              disabled={busy}>
              {user.enabled ? 'Disable' : 'Enable'}
            </button>
            <button type="button" className="button button-small"
              onClick={() => void run(
                () => api.signOutOpsUser(user.userId),
                `Every session of ${user.displayName} was ended.`)}
              disabled={busy}>
              Sign out all
            </button>
            <button type="button" className="button button-small"
              onClick={() => {
                setSettingPassword(!settingPassword);
                setPassword('');
              }}
              disabled={busy}>
              {settingPassword ? 'Cancel' : 'Set password'}
            </button>
          </div>
          {localError ? <p className="form-error" role="alert">{localError}</p> : null}
        </td>
      </tr>
      {settingPassword ? (
        <tr>
          <td colSpan={6}>
            <form className="row-actions" onSubmit={(event) => {
              event.preventDefault();
              if (password.length < 12) {
                setLocalError('A password needs at least 12 characters.');
                return;
              }
              void (async () => {
                const saved = await run(
                  () => api.setOpsPassword(user.userId, password),
                  `Password for ${user.displayName} replaced. Tell the operator out of band; the gateway cannot show it again.`);
                if (saved) {
                  setPassword('');
                  setSettingPassword(false);
                }
              })();
            }}>
              <label className="note">
                New password for {user.username} (at least 12 characters)
                <input type="password" value={password} autoComplete="new-password"
                  onChange={(event) => setPassword(event.target.value)} />
              </label>
              <button type="submit" className="button button-small" disabled={busy}>Replace</button>
              <span className="note">This does not end the account&apos;s existing sessions.</span>
            </form>
          </td>
        </tr>
      ) : null}
    </>
  );
}

function CreateUser(props: {
  roles: RoleMatrix | null;
  onCreated: (created: CreatedOpsUser) => void;
  onError: (caught: unknown) => void;
}) {
  const options = props.roles?.roles.map((role) => role.role) ?? ROLES;
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [role, setRole] = useState('OPERATOR');
  const [enabled, setEnabled] = useState(true);
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    setBusy(true);
    try {
      const created = await api.createOpsUser({ username, displayName, role, enabled });
      setUsername('');
      setDisplayName('');
      props.onCreated(created);
    } catch (caught) {
      setFormError(problemText(caught));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title="Create an operator"
      subtitle="The password and TOTP secret are generated here and returned once.">
      <form className="stack" onSubmit={(event) => void submit(event)}>
        <div className="field-row">
          <div className="field">
            <label htmlFor="new-username">Username</label>
            <input id="new-username" value={username} maxLength={64} required
              pattern="[A-Za-z0-9._-]{3,64}"
              onChange={(event) => setUsername(event.target.value)} />
            <p className="field-hint">3 to 64 letters, digits, dot, underscore or dash.</p>
          </div>
          <div className="field">
            <label htmlFor="new-display">Display name</label>
            <input id="new-display" value={displayName} maxLength={128} required
              onChange={(event) => setDisplayName(event.target.value)} />
          </div>
        </div>
        <div className="field-row">
          <div className="field">
            <label htmlFor="new-role">Role</label>
            <select id="new-role" value={role} onChange={(event) => setRole(event.target.value)}>
              {options.map((name) => <option key={name} value={name}>{name}</option>)}
            </select>
          </div>
          <label className="check">
            <input type="checkbox" checked={enabled}
              onChange={(event) => setEnabled(event.target.checked)} />
            Enabled immediately
          </label>
        </div>
        {formError ? <p className="form-error" role="alert">{formError}</p> : null}
        <div className="form-actions">
          <button type="submit" className="button button-primary" disabled={busy}>
            {busy ? 'Creating…' : 'Create operator'}
          </button>
        </div>
      </form>
    </Panel>
  );
}
