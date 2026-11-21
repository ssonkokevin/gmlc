import { useState } from 'react';
import type { FormEvent } from 'react';
import { api, ApiError } from '../api';
import type { Session } from '../types';

export function SignIn({ onSignedIn }: { onSignedIn: (session: Session) => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [totp, setTotp] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const session = await api.signIn(username.trim(), password, totp.trim());
      onSignedIn(session);
    } catch (caught) {
      if (caught instanceof ApiError) {
        setError(caught.status === 423
          ? 'The account is locked after repeated failed sign-ins. Wait for the lock to expire and try again.'
          : caught.message);
      } else {
        setError('The gateway could not be reached. Check the connection and try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="signin-page">
      <main className="signin-card">
        <p className="brand-mark">GMLC</p>
        <h1>Operations console</h1>
        <p className="signin-lead">
          Operator accounts are people, not API clients. Sign-in uses your password and the
          code from your authenticator.
        </p>
        <form onSubmit={submit} className="signin-form" noValidate>
          <label htmlFor="username">Username</label>
          <input id="username" name="username" autoComplete="username" required
            value={username} onChange={(event) => setUsername(event.target.value)} />

          <label htmlFor="password">Password</label>
          <input id="password" name="password" type="password" autoComplete="current-password"
            required value={password} onChange={(event) => setPassword(event.target.value)} />

          <label htmlFor="totp">Six-digit code</label>
          <input id="totp" name="totp" inputMode="numeric" pattern="[0-9]{6}" maxLength={6}
            autoComplete="one-time-code" required value={totp}
            onChange={(event) => setTotp(event.target.value.replace(/\D/g, ''))} />

          {error ? <p className="form-error" role="alert">{error}</p> : null}

          <button type="submit" className="button button-primary" disabled={busy}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
        <p className="signin-foot">
          Every location request still needs consent, purpose and an audit record. The console
          has no bypass.
        </p>
      </main>
    </div>
  );
}
