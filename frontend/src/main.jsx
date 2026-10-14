import '@fontsource/poppins/400.css';
import '@fontsource/poppins/500.css';
import '@fontsource/poppins/600.css';
import { StrictMode, useCallback, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { ApiError, api } from './api/client';
import { AccountStatusPage } from './components/AccountStatusPage';
import { AdminPage } from './components/AdminPage';
import { LoginPage } from './components/LoginPage';
import './styles.css';

/**
 * Decides what to show: the sign-in page, the "waiting for approval" page, the drive, or the admin
 * panel (administrators only; reached with the #/admin address).
 */
function Root() {
  const [session, setSession] = useState({ state: 'loading' });
  const [hash, setHash] = useState(window.location.hash);
  const [pendingCount, setPendingCount] = useState(0);
  const authFailed = new URLSearchParams(window.location.search).has('authError');

  const loadSession = useCallback(async () => {
    try {
      setSession({ state: 'signedIn', account: await api.me() });
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setSession({ state: 'signedOut', config: await api.authConfig() });
      } else {
        throw error;
      }
    }
  }, []);

  useEffect(() => {
    void loadSession();
  }, [loadSession]);

  useEffect(() => {
    const onHashChange = () => setHash(window.location.hash);
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const account = session.state === 'signedIn' ? session.account : null;
  const isAdmin = account?.role === 'ADMIN' && account.status === 'APPROVED';

  const loadPendingCount = useCallback(async () => {
    if (!isAdmin) return;
    try {
      setPendingCount((await api.accounts('PENDING')).length);
    } catch {
      setPendingCount(0);
    }
  }, [isAdmin]);

  useEffect(() => {
    void loadPendingCount();
  }, [loadPendingCount, hash]);

  const signOut = async () => {
    await api.signOut().catch(() => undefined);
    window.history.replaceState(null, '', '/');
    setHash('');
    await loadSession();
  };

  if (session.state === 'loading') return null;

  if (session.state === 'signedOut') {
    return (
      <LoginPage
        config={session.config}
        authFailed={authFailed}
        onSignedIn={(signedIn) => setSession({ state: 'signedIn', account: signedIn })}
      />
    );
  }

  if (session.account.status !== 'APPROVED') {
    return <AccountStatusPage account={session.account} onRecheck={() => void loadSession()} onSignOut={signOut} />;
  }

  if (hash.startsWith('#/admin') && isAdmin) {
    return <AdminPage pendingCount={pendingCount} onAccountsChanged={() => void loadPendingCount()} />;
  }

  return <App account={session.account} pendingCount={pendingCount} onSignOut={signOut} />;
}

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
