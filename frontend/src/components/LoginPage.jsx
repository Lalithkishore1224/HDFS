import { Cloud } from 'lucide-react';
import { useState } from 'react';
import { api } from '../api/client';

const MIN_PASSWORD_LENGTH = 8;

/** Sign in with a username and password, or create an account. A new account waits for administrator approval. */
export function LoginPage({ config, onSignedIn }) {
  const [mode, setMode] = useState('signIn');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const creating = mode === 'create';

  const switchMode = (next) => {
    setMode(next);
    setError(null);
    setPassword('');
    setConfirm('');
  };

  const submit = async (event) => {
    event.preventDefault();
    setError(null);
    if (creating && password !== confirm) {
      setError('The two passwords do not match.');
      return;
    }
    setBusy(true);
    try {
      const account = creating
        ? await api.register(username.trim(), password)
        : await api.login(username.trim(), password);
      onSignedIn(account);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Something went wrong. Please try again.');
    } finally {
      setBusy(false);
    }
  };

  const ready = username.trim() && password && (!creating || confirm);

  return (
    <div className="login">
      <div className="login-card">
        <div className="login-brand">
          <Cloud size={32} strokeWidth={1.75} />
          <span>Drive</span>
        </div>

        <div className="login-tabs" role="tablist">
          <button
            role="tab"
            aria-selected={!creating}
            className={`login-tab ${!creating ? 'login-tab-active' : ''}`}
            onClick={() => switchMode('signIn')}
            type="button"
          >
            Sign in
          </button>
          {config.registrationOpen && (
            <button
              role="tab"
              aria-selected={creating}
              className={`login-tab ${creating ? 'login-tab-active' : ''}`}
              onClick={() => switchMode('create')}
              type="button"
            >
              Create account
            </button>
          )}
        </div>

        <h1>{creating ? 'Create your account' : 'Welcome back'}</h1>
        <p className="muted">
          {creating ? 'Choose a username and a password.' : 'Enter your username and password to continue.'}
        </p>

        <form className="login-form" onSubmit={submit}>
          <label className="field">
            <span>Username</span>
            <input
              className="text-input"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              autoFocus
            />
            {creating && <small className="muted">3 to 30 characters: letters, digits, dot, dash or underscore.</small>}
          </label>

          <label className="field">
            <span>Password</span>
            <input
              className="text-input"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={creating ? 'new-password' : 'current-password'}
            />
            {creating && <small className="muted">At least {MIN_PASSWORD_LENGTH} characters.</small>}
          </label>

          {creating && (
            <label className="field">
              <span>Confirm password</span>
              <input
                className="text-input"
                type="password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                autoComplete="new-password"
              />
            </label>
          )}

          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}

          <button className="button button-primary login-submit" type="submit" disabled={!ready || busy}>
            {busy ? 'Please wait…' : creating ? 'Create account' : 'Sign in'}
          </button>
        </form>

        <p className="login-fineprint">
          {creating
            ? 'New accounts are reviewed by an administrator before they can use Drive.'
            : 'No account yet? Choose Create account. An administrator approves new accounts.'}
        </p>
      </div>
    </div>
  );
}
