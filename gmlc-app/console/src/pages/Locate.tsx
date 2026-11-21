import { useCallback, useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { api, ApiError } from '../api';
import { Panel, StatusChip, stateTone } from '../components';
import { fmtAge, fmtDateTime, fmtInt, fmtMs, fmtTime } from '../format';
import type {
  ClientView,
  RequestDetail,
  Session,
  Submission,
  TimelineEvent,
} from '../types';

const FINAL = new Set(['DELIVERED', 'FAILED', 'TIMEOUT']);

const PRIORITIES = ['LOW', 'NORMAL', 'HIGH'] as const;

const PRIORITY_HINT: Record<(typeof PRIORITIES)[number], string> = {
  LOW: 'Queued behind normal work; appropriate for a non-urgent check.',
  NORMAL: 'The default. The request is routed as soon as the peer is available.',
  HIGH: 'Shown as urgent in the record. It does not skip consent, licence or the state machine.',
};

function problemText(caught: unknown): string {
  if (!(caught instanceof ApiError)) {
    return 'The gateway did not answer. Nothing was recorded by this screen.';
  }
  switch (caught.status) {
    case 403:
      return caught.message;
    case 404:
      return 'The gateway no longer holds that request, or it belongs to another tenant.';
    case 401:
      return 'The session ended. Sign in again to continue.';
    case 423:
      return 'The account is locked. Wait for the lock to expire.';
    default:
      return caught.message;
  }
}

function detailText(detail: TimelineEvent['detail']): string {
  if (!detail) {
    return '';
  }
  return Object.entries(detail)
    .map(([key, value]) => {
      const shown = value !== null && typeof value === 'object'
        ? JSON.stringify(value)
        : String(value);
      return `${key}: ${shown}`;
    })
    .join(' · ');
}

function Envelope({ detail }: { detail: RequestDetail }) {
  const result = detail.result;
  if (!result) {
    return (
      <p className="note">
        No position was stored for this request. The gateway does not show a placeholder
        coordinate: an answer exists only when a positioning method produced one.
        {detail.errorCode ? ` Final code ${detail.errorCode}.` : ''}
      </p>
    );
  }
  return (
    <dl className="kv">
      <div><dt>Method</dt><dd>{result.method}</dd></div>
      <div><dt>Uncertainty</dt><dd>{result.uncertaintyM === undefined ? 'not recorded' : `${fmtInt(result.uncertaintyM)} m`}</dd></div>
      <div><dt>Confidence</dt><dd>{result.confidence === undefined ? 'not recorded' : `${fmtInt(result.confidence)}%`}</dd></div>
      <div><dt>Age of fix</dt><dd>{fmtAge(result.ageSeconds)}</dd></div>
      <div><dt>Accuracy requested</dt>
        <dd>{result.accuracyRequestedM === undefined ? '—' : `${fmtInt(result.accuracyRequestedM)} m`}</dd></div>
      <div><dt>Accuracy met</dt>
        <dd>{result.accuracyFulfilled === undefined ? 'not recorded'
          : result.accuracyFulfilled ? 'Yes, within the request' : 'No, wider than requested'}</dd></div>
      <div><dt>Shape</dt><dd>{result.shape ?? '—'}</dd></div>
      <div><dt>Coordinates</dt>
        <dd className="mono">
          {result.latitude === undefined || result.longitude === undefined
            ? 'not a point result'
            : `${result.latitude}, ${result.longitude}`}
        </dd></div>
      <div><dt>Serving cell</dt><dd>{result.servingCell ?? '—'}</dd></div>
      <div><dt>Engine</dt><dd>{result.engine ?? '—'}</dd></div>
      <div><dt>Positioning node</dt><dd>{result.positioningNode ?? '—'}</dd></div>
      <div><dt>Produced at</dt><dd>{fmtDateTime(result.producedAt)}</dd></div>
      {result.methodsAttempted.length > 0 ? (
        <div className="span-all"><dt>Methods attempted</dt>
          <dd>{result.methodsAttempted.join(' → ')}</dd></div>
      ) : null}
    </dl>
  );
}

export function Locate(props: { session: Session; onExpired: () => void }) {
  const { session, onExpired } = props;
  const canLocate = session.permissions.includes('LOCATE');
  const canViewRequests = session.permissions.includes('VIEW_REQUESTS');
  const canReveal = session.permissions.includes('REVEAL_IDENTIFIERS');

  const [clients, setClients] = useState<ClientView[] | null>(null);
  const [clientsError, setClientsError] = useState<string | null>(null);

  const [clientId, setClientId] = useState('');
  const [subjectType, setSubjectType] = useState<'MSISDN' | 'IMSI'>('MSISDN');
  const [subject, setSubject] = useState('');
  const [purpose, setPurpose] = useState('');
  const [accuracy, setAccuracy] = useState('');
  const [priority, setPriority] = useState<(typeof PRIORITIES)[number]>('NORMAL');
  const [idempotencyKey, setIdempotencyKey] = useState('');

  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const [submission, setSubmission] = useState<Submission | null>(null);
  const [detail, setDetail] = useState<RequestDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [revealed, setRevealed] = useState(false);
  const [polling, setPolling] = useState(false);

  const selectedClient = useMemo(
    () => clients?.find((client) => client.clientId === clientId) ?? null,
    [clients, clientId],
  );

  useEffect(() => {
    let live = true;
    if (!canLocate) {
      return () => {
        live = false;
      };
    }
    api
      .clients()
      .then((list) => {
        if (!live) {
          return;
        }
        setClients(list);
        if (list.length > 0) {
          setClientId(list[0].clientId);
          setPurpose(list[0].purposes[0] ?? '');
        }
      })
      .catch((caught: unknown) => {
        if (!live) {
          return;
        }
        if (caught instanceof ApiError && caught.status === 401) {
          onExpired();
          return;
        }
        setClientsError(problemText(caught));
      });
    return () => {
      live = false;
    };
  }, [canLocate, onExpired]);

  useEffect(() => {
    if (!selectedClient) {
      return;
    }
    if (!selectedClient.purposes.includes(purpose)) {
      setPurpose(selectedClient.purposes[0] ?? '');
    }
  }, [selectedClient, purpose]);

  const state = detail?.state ?? submission?.state;
  const finished = state ? FINAL.has(state) : false;

  const readDetail = useCallback(async (requestId: string, reveal: boolean) => {
    const next = await api.requestDetail(requestId, reveal);
    setDetail(next);
    setDetailError(null);
    return next;
  }, []);

  useEffect(() => {
    if (!submission) {
      return;
    }
    if (!canViewRequests) {
      setDetailError('This account may place a request but not read request detail (VIEW_REQUESTS is missing).');
      return;
    }
    if (finished) {
      setPolling(false);
      return;
    }
    let live = true;
    let timer: number | null = null;
    setPolling(true);

    const tick = async () => {
      try {
        const next = await readDetail(submission.requestId, revealed);
        if (!live) {
          return;
        }
        setDetail(next);
        if (FINAL.has(next.state)) {
          setPolling(false);
          return;
        }
      } catch (caught) {
        if (!live) {
          return;
        }
        setPolling(false);
        if (caught instanceof ApiError && caught.status === 401) {
          onExpired();
          return;
        }
        setDetailError(problemText(caught));
        return;
      }
      timer = window.setTimeout(() => void tick(), 2_000);
    };

    timer = window.setTimeout(() => void tick(), 400);
    return () => {
      live = false;
      if (timer !== null) {
        window.clearTimeout(timer);
      }
    };
  }, [submission, finished, canViewRequests, readDetail, revealed, onExpired]);

  function validate(): string | null {
    if (!clientId) {
      return 'Choose the API client this request is made under.';
    }
    if (!selectedClient) {
      return 'The chosen client is no longer available. Reload the client list.';
    }
    if (subjectType === 'MSISDN' && !/^[0-9]{7,15}$/.test(subject.trim())) {
      return 'An MSISDN must be 7 to 15 digits, no plus sign or spaces.';
    }
    if (subjectType === 'IMSI' && !/^[0-9]{14,15}$/.test(subject.trim())) {
      return 'An IMSI must be 14 or 15 digits.';
    }
    if (!selectedClient.purposes.includes(purpose)) {
      return 'The chosen client is not registered for that purpose.';
    }
    if (accuracy.trim()) {
      const meters = Number(accuracy);
      if (!Number.isInteger(meters) || meters < 1 || meters > 100_000) {
        return 'Requested accuracy must be a whole number of metres between 1 and 100,000.';
      }
      if (meters < selectedClient.maxAccuracyM) {
        return `This client may not ask for better than ${fmtInt(selectedClient.maxAccuracyM)} m.`;
      }
    }
    if (idempotencyKey.trim().length > 80) {
      return 'The idempotency key may be at most 80 characters.';
    }
    return null;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    const problem = validate();
    if (problem) {
      setFormError(problem);
      return;
    }
    setBusy(true);
    setSubmission(null);
    setDetail(null);
    setDetailError(null);
    setRevealed(false);
    try {
      const body: Parameters<typeof api.submitLocation>[0] = {
        clientId,
        purpose,
        priority,
      };
      if (subjectType === 'MSISDN') {
        body.msisdn = subject.trim();
      } else {
        body.imsi = subject.trim();
      }
      if (accuracy.trim()) {
        body.accuracyMeters = Number(accuracy);
      }
      if (idempotencyKey.trim()) {
        body.idempotencyKey = idempotencyKey.trim();
      }
      const next = await api.submitLocation(body);
      setSubmission(next);
      if (FINAL.has(next.state) && canViewRequests) {
        await readDetail(next.requestId, false);
      }
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        onExpired();
        return;
      }
      setFormError(problemText(caught));
    } finally {
      setBusy(false);
    }
  }

  async function refreshDetail() {
    if (!submission) {
      return;
    }
    try {
      await readDetail(submission.requestId, revealed);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        onExpired();
        return;
      }
      setDetailError(problemText(caught));
    }
  }

  async function reveal() {
    if (!submission) {
      return;
    }
    try {
      await readDetail(submission.requestId, true);
      setRevealed(true);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        onExpired();
        return;
      }
      setDetailError(problemText(caught));
    }
  }

  function reset() {
    setSubmission(null);
    setDetail(null);
    setDetailError(null);
    setRevealed(false);
    setSubject('');
    setIdempotencyKey('');
    setAccuracy('');
  }

  if (!canLocate) {
    return (
      <div className="page">
        <div className="page-head">
          <div>
            <h1>Location Request</h1>
            <p>Place a request from the console under a governed API client.</p>
          </div>
        </div>
        <Panel title="Not available to this account"
          subtitle={`Your role is ${session.role}, which does not include the LOCATE permission.`}>
          <p>
            Placing a location request needs the LOCATE permission. Reading existing requests
            is separate (VIEW_REQUESTS), and revealing an identifier is separate again
            (REVEAL_IDENTIFIERS).
          </p>
          <p className="note">
            An administrator can change role membership in Users &amp; Access (phase C6).
            Until then this screen stays closed rather than offering a control the gateway
            would refuse.
          </p>
        </Panel>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Location Request</h1>
          <p>
            The request goes through the same consent, purpose, accuracy and licence checks as
            an API client. The person placing it is audited separately from the client.
          </p>
        </div>
      </div>

      <div className="panel-grid">
        <Panel title="New request"
          subtitle="One subject per request. The client list is limited to enabled clients of this tenant.">
          {clientsError ? <p className="form-error" role="alert">{clientsError}</p> : null}
          {clients && clients.length === 0 ? (
            <p className="empty">
              This tenant has no enabled API client, so no request can be placed from the
              console. Create or enable one under Configuration (phase C6).
            </p>
          ) : null}

          {!clients && !clientsError ? <p className="empty" role="status">Loading clients…</p> : null}

          {clients && clients.length > 0 ? (
            <form className="locate-form" onSubmit={submit} noValidate>
              <div className="field">
                <label htmlFor="locate-client">API client</label>
                <select id="locate-client" value={clientId}
                  onChange={(event) => setClientId(event.target.value)}>
                  {clients.map((client) => (
                    <option key={client.clientId} value={client.clientId}>{client.name}</option>
                  ))}
                </select>
                {selectedClient ? (
                  <p className="field-hint">
                    Entitlement: {selectedClient.purposes.join(', ') || 'no purposes'} ·
                    accuracy no better than {fmtInt(selectedClient.maxAccuracyM)} m.
                  </p>
                ) : null}
              </div>

              <fieldset className="field">
                <legend>Subject</legend>
                <div className="segmented" role="group" aria-label="Subject identifier type">
                  {(['MSISDN', 'IMSI'] as const).map((type) => (
                    <button key={type} type="button" aria-pressed={subjectType === type}
                      className={subjectType === type ? 'segment active' : 'segment'}
                      onClick={() => {
                        setSubjectType(type);
                        setSubject('');
                      }}>
                      {type}
                    </button>
                  ))}
                </div>
                <label className="visually-hidden" htmlFor="locate-subject">
                  {subjectType === 'MSISDN' ? 'MSISDN' : 'IMSI'}
                </label>
                <input id="locate-subject" className="mono" inputMode="numeric"
                  placeholder={subjectType === 'MSISDN' ? 'e.g. 2567XXXXXXXX' : '15 digits'}
                  value={subject} onChange={(event) => setSubject(event.target.value.replace(/\D/g, ''))} />
                <p className="field-hint">
                  {subjectType === 'MSISDN'
                    ? '7–15 digits, no plus sign. Consent is checked for this exact identifier.'
                    : '14–15 digits. Consent is checked against the subscription behind the IMSI.'}
                </p>
              </fieldset>

              <div className="field-row">
                <div className="field">
                  <label htmlFor="locate-purpose">Purpose</label>
                  <select id="locate-purpose" value={purpose}
                    onChange={(event) => setPurpose(event.target.value)}>
                    {(selectedClient?.purposes ?? []).map((code) => (
                      <option key={code} value={code}>{code.replaceAll('_', ' ')}</option>
                    ))}
                  </select>
                  <p className="field-hint">Only purposes this client is registered for are offered.</p>
                </div>

                <div className="field">
                  <label htmlFor="locate-accuracy">Requested accuracy (metres)</label>
                  <input id="locate-accuracy" inputMode="numeric" placeholder="optional"
                    value={accuracy} onChange={(event) => setAccuracy(event.target.value.replace(/\D/g, ''))} />
                  <p className="field-hint">
                    Leave blank to accept the client entitlement. A tighter figure is refused,
                    not quietly widened.
                  </p>
                </div>
              </div>

              <div className="field-row">
                <div className="field">
                  <label htmlFor="locate-priority">Priority</label>
                  <select id="locate-priority" value={priority}
                    onChange={(event) => setPriority(event.target.value as (typeof PRIORITIES)[number])}>
                    {PRIORITIES.map((key) => <option key={key} value={key}>{key}</option>)}
                  </select>
                  <p className="field-hint">{PRIORITY_HINT[priority]}</p>
                </div>

                <div className="field">
                  <label htmlFor="locate-idempotency">Idempotency key (optional)</label>
                  <input id="locate-idempotency" maxLength={80} autoComplete="off"
                    value={idempotencyKey} onChange={(event) => setIdempotencyKey(event.target.value)} />
                  <p className="field-hint">
                    Reusing the same key replays the earlier request instead of creating a
                    second row.
                  </p>
                </div>
              </div>

              {formError ? <p className="form-error" role="alert">{formError}</p> : null}

              <div className="form-actions">
                <button type="submit" className="button button-primary" disabled={busy}>
                  {busy ? 'Submitting…' : 'Submit request'}
                </button>
                <p className="note">
                  A refusal writes an audit row too. Nothing on this screen bypasses consent.
                </p>
              </div>
            </form>
          ) : null}
        </Panel>

        <Panel title="Request state"
          subtitle="Follow one request through its state machine. The screen reads the row; it does not predict the next state.">
          {!submission ? (
            <p className="empty">
              Submit a request to see its state, the stored result envelope and the timeline.
            </p>
          ) : (
            <>
              <dl className="kv">
                <div><dt>Request</dt><dd className="mono">{submission.requestId}</dd></div>
                <div><dt>State</dt>
                  <dd><StatusChip label={state ?? '—'} tone={stateTone(state)} /></dd></div>
                <div><dt>Client</dt><dd>{detail?.clientName ?? selectedClient?.name ?? '—'}</dd></div>
                <div><dt>Purpose</dt><dd>{(detail?.purpose ?? submission.purpose).replaceAll('_', ' ')}</dd></div>
                <div><dt>Priority</dt><dd>{detail?.priority ?? submission.priority}</dd></div>
                <div><dt>Subject</dt>
                  <dd className="mono">{detail
                    ? detail.identifiers.msisdn ?? detail.identifiers.imsi ?? '—'
                    : subject || '—'}</dd></div>
                <div><dt>Created</dt><dd>{fmtDateTime(detail?.createdAt ?? submission.createdAt)}</dd></div>
                <div><dt>Deadline</dt><dd>{fmtDateTime(detail?.deadlineAt ?? submission.deadlineAt)}</dd></div>
                <div><dt>Completed</dt><dd>{fmtDateTime(detail?.completedAt ?? submission.completedAt)}</dd></div>
                <div><dt>Data source</dt><dd>{detail ? `${detail.environment} · ${detail.dataSource}` : 'pending read'}</dd></div>
              </dl>

              {submission.replayed ? (
                <p className="note">
                  This matched an earlier request with the same idempotency key. No second row
                  was created; the earlier state is shown.
                </p>
              ) : null}
              {submission.errorCode ? (
                <p className="form-error" role="alert">
                  {submission.errorCode}{submission.errorDetail ? ` — ${submission.errorDetail}` : ''}
                </p>
              ) : null}

              {detailError ? <p className="form-error" role="alert">{detailError}</p> : null}
              {polling ? (
                <p className="note" role="status">
                  Waiting for a final state. This screen re-reads the stored row every 2 s while
                  the page is open.
                </p>
              ) : null}

              <div className="form-actions">
                <button type="button" className="button button-small" onClick={() => void refreshDetail()}
                  disabled={!canViewRequests}>
                  Read again
                </button>
                {canReveal && detail?.identifiers.masked ? (
                  <button type="button" className="button button-small" onClick={() => void reveal()}>
                    Reveal identifiers (audited)
                  </button>
                ) : null}
                {revealed ? <span className="note">Identifiers revealed for this session view.</span> : null}
                <button type="button" className="button button-small" onClick={reset}>New request</button>
              </div>

              <h3 className="subhead">Result envelope</h3>
              {detail ? (
                <Envelope detail={detail} />
              ) : (
                <p className="note">
                  The result is shown once the request detail has been read. A pending request
                  has no envelope yet.
                </p>
              )}

              <h3 className="subhead">Timeline</h3>
              {!canViewRequests ? (
                <p className="note">The timeline needs VIEW_REQUESTS.</p>
              ) : detail && detail.timeline.length > 0 ? (
                <ol className="timeline">
                  {detail.timeline.map((event, index) => (
                    <li key={`${event.at}-${event.action}-${index}`}>
                      <span className="timeline-time">{fmtTime(event.at)}</span>
                      <span className="timeline-body">
                        <span className="timeline-action">{event.action.replaceAll('_', ' ')}</span>
                        {event.outcome ? (
                          <StatusChip label={event.outcome} tone={stateTone(event.outcome)} />
                        ) : null}
                        {event.source ? <span className="timeline-source">{event.source}</span> : null}
                        {event.detail ? (
                          <span className="timeline-detail">{detailText(event.detail)}</span>
                        ) : null}
                      </span>
                    </li>
                  ))}
                </ol>
              ) : (
                <p className="empty">
                  {detail ? 'The gateway recorded no timeline event yet.' : 'No detail has been read yet.'}
                </p>
              )}
            </>
          )}
        </Panel>
      </div>

      <Panel title="Signalling record"
        subtitle="What the peer face recorded for this request. Absent rows mean no exchange was attempted.">
        {detail?.cdr ? (
          <dl className="kv">
            <div><dt>Operation</dt><dd>{detail.cdr.operation ?? '—'}</dd></div>
            <div><dt>Protocol</dt><dd>{detail.cdr.protocol ?? '—'}</dd></div>
            <div><dt>Result code</dt><dd>{detail.cdr.resultCode ?? '—'}</dd></div>
            <div><dt>Record status</dt><dd>{detail.cdr.recordStatus ?? '—'}</dd></div>
            <div><dt>RAT</dt><dd>{detail.cdr.rat ?? '—'}</dd></div>
            <div><dt>Response time</dt><dd>{fmtMs(detail.cdr.responseTimeMs)}</dd></div>
            <div><dt>Serving node</dt><dd>{detail.cdr.servingNode ?? '—'}</dd></div>
            <div><dt>Positioning node</dt><dd>{detail.cdr.positioningNode ?? '—'}</dd></div>
            <div><dt>Paging</dt><dd>{detail.cdr.pagingPerformed ? 'performed' : 'not performed'}</dd></div>
            <div><dt>Methods attempted</dt>
              <dd>{(detail.cdr.methodsAttempted ?? []).join(' → ') || '—'}</dd></div>
            <div><dt>Accuracy achieved</dt>
              <dd>{detail.cdr.accuracyAchievedM === undefined
                ? '—' : `${fmtInt(detail.cdr.accuracyAchievedM)} m`}</dd></div>
            <div><dt>Age of fix</dt><dd>{fmtAge(detail.cdr.ageSeconds)}</dd></div>
          </dl>
        ) : (
          <p className="empty">
            {submission
              ? 'No signalling record has been stored for this request.'
              : 'Submit a request to see the exchange its peer face recorded.'}
          </p>
        )}
      </Panel>
    </div>
  );
}
