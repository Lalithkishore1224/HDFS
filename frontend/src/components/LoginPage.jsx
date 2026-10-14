import { Cloud } from 'lucide-react';
import { useState } from 'react';
import { api } from '../api/client';

import { GoogleLogo } from './GoogleLogo';

/** Quick identities for the development sign-in; the first matches the administrator in application-dev.yml. */
const DEV_IDENTITIES = [
  { email: 'admin@dev.local', name: 'Dev Admin', label: 'Administrator' },
  { email: 'new.user@dev.local', name: 'New User', label: 'New person (needs approval)' },
];

export function LoginPage({ config, authFailed, onSignedIn }) {
  return (
    <div className="login">
      <div className="login-card">
        <div className="login-brand">
          <Cloud size={32} strokeWidth={1.75} />
          <span>Drive</span>
        </div>
        <h1>Sign in</h1>
        <p className="muted">Use your Google account to continue.</p>

        {authFailed && (
          <p className="form-error" role="alert">
            Google sign-in did not complete. Please try again.
          </p>
        )}

        {config.googleEnabled ? (
          <a className="google-button" href="/oauth2/authorization/google">
            <GoogleLogo />
            Continue with Google
          </a>
        ) : (
          <p className="notice">
            Google sign-in is not configured on this server yet. Set the Google client ID and secret (see the README) to
            enable “Continue with Google”.
          </p>
        )}

        <p className="login-fineprint">New accounts are reviewed by an administrator before they can use Drive.</p>

        {config.devLoginEnabled && <DevSignIn onSignedIn={onSignedIn} />}
      </div>
    </div>
  );
}

function DevSignIn({ onSignedIn }) {
  const [email, setEmail] = useState('');
  const [error, setError] = useState(null);

  const signIn = async (address, name) => {
    setError(null);
    try {
      onSignedIn(await api.devLogin(address, name));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Sign-in failed');
    }
  };

  const submit = (event) => {
    event.preventDefault();
    if (email.trim()) void signIn(email.trim(), email.trim().split('@')[0]);
  };

  return (
    <section className="dev-signin">
      <h2>Local development sign-in</h2>
      <p className="muted">Not available in production. Goes through the same approval rules as Google.</p>
      <div className="chip-row">
        {DEV_IDENTITIES.map((identity) => (
          <button key={identity.email} className="chip" onClick={() => void signIn(identity.email, identity.name)}>
            {identity.label}
          </button>
        ))}
      </div>
      <form className="dev-form" onSubmit={submit}>
        <input
          className="text-input"
          type="email"
          placeholder="any.email@example.com"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          aria-label="Email address"
        />
        <button className="button button-outline" type="submit" disabled={!email.trim()}>
          Sign in
        </button>
      </form>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
