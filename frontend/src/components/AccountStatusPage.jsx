import { Ban, Hourglass } from 'lucide-react';
import { useEffect } from 'react';

const POLL_INTERVAL_MS = 10_000;

/** Shown to people who have signed in but are not (or no longer) allowed to use the drive. */
export function AccountStatusPage({ account, onRecheck, onSignOut }) {
  const waiting = account.status === 'PENDING';

  // While waiting, keep asking so access appears as soon as an administrator approves.
  useEffect(() => {
    if (!waiting) return;
    const timer = setInterval(onRecheck, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [waiting, onRecheck]);

  return (
    <div className="login">
      <div className="login-card status-card">
        <div className={`status-icon ${waiting ? 'status-icon-waiting' : 'status-icon-declined'}`}>
          {waiting ? <Hourglass size={28} strokeWidth={1.75} /> : <Ban size={28} strokeWidth={1.75} />}
        </div>
        <h1>{waiting ? 'Waiting for approval' : 'Access declined'}</h1>
        <p className="muted">
          {waiting
            ? 'Your account has been created. An administrator needs to approve it before you can use Drive. This page updates on its own.'
            : 'An administrator has declined access for this account. If you think this is a mistake, contact them.'}
        </p>
        <p className="status-email">{account.username}</p>
        <div className="status-actions">
          {waiting && (
            <button className="button button-outline" onClick={onRecheck}>
              Check again
            </button>
          )}
          <button className="button button-text" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      </div>
    </div>
  );
}
