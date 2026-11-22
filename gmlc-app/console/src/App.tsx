import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError } from './api';
import { Dashboard } from './pages/Dashboard';
import { Geofencing } from './pages/Geofencing';
import { Locate } from './pages/Locate';
import { Monitoring } from './pages/Monitoring';
import { Reports } from './pages/Reports';
import { SignIn } from './pages/SignIn';
import { Tracking } from './pages/Tracking';
import type { RangeKey, Session } from './types';

type RouteKey =
  | 'dashboard'
  | 'monitoring'
  | 'locate'
  | 'tracking'
  | 'geofencing'
  | 'reports'
  | 'configuration'
  | 'users';

interface NavItem {
  key: RouteKey;
  label: string;
  badge?: 'cdr';
}

const NAV: Array<{ section: string; items: NavItem[] }> = [
  {
    section: 'Operations',
    items: [
      { key: 'dashboard', label: 'Operations Dashboard' },
      { key: 'monitoring', label: 'Monitoring & Tracing', badge: 'cdr' },
      { key: 'locate', label: 'Location Request' },
    ],
  },
  {
    section: 'Location intelligence',
    items: [
      { key: 'tracking', label: 'Tracking' },
      { key: 'geofencing', label: 'Geofencing' },
      { key: 'reports', label: 'Reports & Analytics' },
    ],
  },
  {
    section: 'Administration',
    items: [
      { key: 'configuration', label: 'Configuration' },
      { key: 'users', label: 'Users & Access' },
    ],
  },
];

const ROUTE_KEYS = NAV.flatMap((section) => section.items.map((item) => item.key));

const SECTION_OF: Record<RouteKey, string> = NAV.reduce((map, section) => {
  section.items.forEach((item) => {
    map[item.key] = section.section;
  });
  return map;
}, {} as Record<RouteKey, string>);

const TITLE_OF: Record<RouteKey, string> = NAV.reduce((map, section) => {
  section.items.forEach((item) => {
    map[item.key] = item.label;
  });
  return map;
}, {} as Record<RouteKey, string>);

function routeFromHash(): RouteKey {
  const hash = window.location.hash.replace(/^#\/?/, '');
  return (ROUTE_KEYS as string[]).includes(hash) ? (hash as RouteKey) : 'dashboard';
}

type NotBuiltKey = Exclude<RouteKey, 'dashboard' | 'monitoring' | 'locate' | 'reports' | 'geofencing' | 'tracking'>;

const NOT_YET: Record<NotBuiltKey, { phase: string; lines: string[] }> = {
  configuration: {
    phase: 'phase C6',
    lines: [
      'Alert thresholds, SLO targets and licence limits, changeable by administrators.',
      'Four-eyes approval for changes that widen what the deployment can do.',
      'Every change written to the audit trail with the person who made it.',
    ],
  },
  users: {
    phase: 'phase C6',
    lines: [
      'Operator accounts, roles and permissions managed from the console.',
      'Enrolment for the authenticator used at sign-in, with recovery handled by a second administrator.',
      'Revoking a session or an account takes effect on the next request.',
    ],
  },
};

export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [starting, setStarting] = useState(true);
  const [startError, setStartError] = useState<string | null>(null);
  const [route, setRoute] = useState<RouteKey>(routeFromHash);
  const [range, setRange] = useState<RangeKey>('24h');
  const [search, setSearch] = useState('');
  const [openAlerts, setOpenAlerts] = useState(0);
  const [signingOut, setSigningOut] = useState(false);
  const alertTimer = useRef<number | null>(null);

  useEffect(() => {
    const onHash = () => setRoute(routeFromHash());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  useEffect(() => {
    let live = true;
    api
      .me()
      .then((next) => {
        if (live) {
          setSession(next);
          setStarting(false);
        }
      })
      .catch((caught: unknown) => {
        if (!live) {
          return;
        }
        setStarting(false);
        if (caught instanceof ApiError && caught.status === 401) {
          setSession(null);
          return;
        }
        setStartError('The gateway could not be reached. Reload the page or check the service.');
      });
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => () => {
    if (alertTimer.current !== null) {
      window.clearTimeout(alertTimer.current);
    }
  }, []);

  const go = (key: RouteKey) => {
    window.location.hash = `#/${key}`;
    setRoute(key);
  };

  const signedIn = useCallback((next: Session) => {
    setSession(next);
    setStartError(null);
    setRange('24h');
    setSearch('');
    go('dashboard');
  }, []);

  const expired = useCallback(() => {
    setSession(null);
    setOpenAlerts(0);
  }, []);

  const signOut = async () => {
    setSigningOut(true);
    try {
      await api.signOut();
    } finally {
      setSigningOut(false);
      setSession(null);
      setOpenAlerts(0);
    }
  };

  const focusAlerts = () => {
    go('dashboard');
    let tries = 0;
    const tick = () => {
      const element = document.getElementById('alerts');
      if (element) {
        element.scrollIntoView({ behavior: 'smooth', block: 'start' });
        return;
      }
      if (tries < 20) {
        tries += 1;
        alertTimer.current = window.setTimeout(tick, 150);
      }
    };
    tick();
  };

  if (starting) {
    return (
      <main className="boot">
        <p role="status">Checking the session…</p>
      </main>
    );
  }

  if (startError) {
    return (
      <main className="boot">
        <p className="form-error" role="alert">{startError}</p>
      </main>
    );
  }

  if (!session) {
    return <SignIn onSignedIn={signedIn} />;
  }

  const initials = session.displayName
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('');

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">GMLC</span>
          <span className="brand-sub">Operations console</span>
        </div>
        <nav aria-label="Console sections">
          {NAV.map((section) => (
            <div className="nav-section" key={section.section}>
              <p className="nav-title">{section.section}</p>
              <ul>
                {section.items.map((item) => (
                  <li key={item.key}>
                    <button
                      type="button"
                      className={item.key === route ? 'nav-link active' : 'nav-link'}
                      aria-current={item.key === route ? 'page' : undefined}
                      onClick={() => go(item.key)}
                    >
                      <span>{item.label}</span>
                      {item.badge === 'cdr' ? <span className="nav-badge">CDR</span> : null}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>
        <button type="button" className="nav-link nav-signout" onClick={() => void signOut()}
          disabled={signingOut}>
          {signingOut ? 'Signing out…' : 'Sign out'}
        </button>
        <p className="sidebar-foot">
          Environment <strong>{session.environment}</strong><br />
          Data <strong>{session.dataSource}</strong>
        </p>
      </aside>

      <div className="main-area">
        <header className="topbar">
          <p className="crumb">
            <span>{SECTION_OF[route]}</span>
            <span aria-hidden="true">/</span>
            <strong>{TITLE_OF[route]}</strong>
          </p>

          <div className="topbar-tools">
            {route === 'dashboard' ? (
              <form className="search" role="search" onSubmit={(event) => event.preventDefault()}>
                <label className="visually-hidden" htmlFor="console-search">Filter recent requests</label>
                <input
                  id="console-search"
                  type="search"
                  placeholder="Filter requests by number, client or state"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                />
              </form>
            ) : null}

            <span className="env-badge" title="Deployment and data class">
              {session.environment} · {session.dataSource}
            </span>

            <button type="button" className="icon-button bell" onClick={focusAlerts}
              aria-label={openAlerts === 0 ? 'No open alerts' : `${openAlerts} open alerts`}>
              <span aria-hidden="true">Alerts</span>
              {openAlerts > 0 ? <span className="bell-count">{openAlerts}</span> : null}
            </button>

            <details className="help">
              <summary className="icon-button" aria-label="About the figures on this page">Help</summary>
              <div className="menu-panel">
                <h2>What these figures are</h2>
                <p>
                  Every number is counted from rows this gateway stored. Nothing is estimated,
                  interpolated or filled from a sample.
                </p>
                <p>
                  A location result names its method, uncertainty, confidence and age. When a
                  capability is not present in this deployment the screen says so instead of
                  showing a zero.
                </p>
                <p>
                  Peer state comes from exchanges the gateway attempted. Silence is IDLE, never
                  evidence that a peer is healthy.
                </p>
                <p className="note">Console build: phase C5. Dashboard, Location Request,
                  Monitoring, Reports, Geofencing and Tracking are live; Administration
                  screens arrive in a later phase.</p>
              </div>
            </details>

            <details className="user-menu">
              <summary aria-label="Account menu">
                <span className="avatar" aria-hidden="true">{initials || 'OP'}</span>
                <span className="user-name">{session.displayName}</span>
              </summary>
              <div className="menu-panel">
                <p className="menu-user">
                  <strong>{session.displayName}</strong><br />
                  {session.username} · {session.role}
                </p>
                <p className="note">
                  {session.permissions.length} permission(s) on this account. Session ends{' '}
                  {new Date(session.expiresAt).toLocaleTimeString('en-GB', {
                    timeZone: 'Africa/Kampala',
                    hour: '2-digit',
                    minute: '2-digit',
                  })}{' '}
                  Africa/Kampala at the latest.
                </p>
                <button type="button" className="button" onClick={() => void signOut()}
                  disabled={signingOut}>
                  {signingOut ? 'Signing out…' : 'Sign out'}
                </button>
              </div>
            </details>
          </div>
        </header>

        <main className="content">
          {route === 'dashboard' ? (
            <Dashboard
              session={session}
              range={range}
              onRange={setRange}
              search={search}
              onOpenAlerts={setOpenAlerts}
              onExpired={expired}
            />
          ) : route === 'monitoring' ? (
            <Monitoring session={session} onExpired={expired} />
          ) : route === 'locate' ? (
            <Locate session={session} onExpired={expired} />
          ) : route === 'reports' ? (
            <Reports session={session} onExpired={expired} />
          ) : route === 'geofencing' ? (
            <Geofencing session={session} onExpired={expired} />
          ) : route === 'tracking' ? (
            <Tracking session={session} onExpired={expired} />
          ) : (
            <ComingSoon route={route} />
          )}
        </main>
      </div>
    </div>
  );
}

function ComingSoon({ route }: { route: NotBuiltKey }) {
  const plan = NOT_YET[route];
  return (
    <div className="coming-soon">
      <p className="phase-tag">Not built yet</p>
      <h1>{TITLE_OF[route]}</h1>
      <p>
        This screen is scheduled for {plan.phase}. It is empty on purpose: no placeholder
        figures are shown, because a number on this console must come from a stored row.
      </p>
      <h2>What it will do</h2>
      <ul>
        {plan.lines.map((line) => <li key={line}>{line}</li>)}
      </ul>
      <p className="note">
        Until then, the API endpoints that back this screen are covered by the same consent,
        purpose, licence and audit checks as every other request.
      </p>
    </div>
  );
}
