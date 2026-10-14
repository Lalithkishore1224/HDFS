import { ArrowLeft } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';

import { AdminUsers } from './AdminUsers';
import { InsightsPanel } from './InsightsPanel';
import { InsightsView } from './InsightsView';
import { StorageNodesPanel } from './StorageNodesPanel';

/** The administrator's control room: approve people, and watch the storage optimizer. */
export function AdminPage({ pendingCount, onAccountsChanged }) {
  const [tab, setTab] = useState('accounts');

  return (
    <div className="admin">
      <a className="admin-back" href="#/">
        <ArrowLeft size={18} strokeWidth={1.75} />
        Back to Drive
      </a>
      <h1>Admin panel</h1>

      <div className="tabs" role="tablist">
        <button
          role="tab"
          aria-selected={tab === 'accounts'}
          className={`tab ${tab === 'accounts' ? 'tab-active' : ''}`}
          onClick={() => setTab('accounts')}
        >
          Accounts
          {pendingCount > 0 && <span className="tab-count">{pendingCount}</span>}
        </button>
        <button
          role="tab"
          aria-selected={tab === 'storage'}
          className={`tab ${tab === 'storage' ? 'tab-active' : ''}`}
          onClick={() => setTab('storage')}
        >
          Storage operations
        </button>
      </div>

      {tab === 'accounts' ? <AdminUsers onChanged={onAccountsChanged} /> : <StorageOperations />}
    </div>
  );
}

/**
 * The only place hot and cold storage are visible. Files are moved between fast and compressed
 * storage automatically; people using the drive never see this.
 */
function StorageOperations() {
  const [stats, setStats] = useState(null);
  const [optimizer, setOptimizer] = useState(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    try {
      const [loadedStats, loadedOptimizer] = await Promise.all([api.adminStats(), api.optimizerStatus()]);
      setStats(loadedStats);
      setOptimizer(loadedOptimizer);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load operations data');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const runOptimizer = async () => {
    setRunning(true);
    try {
      await api.runOptimizer();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Optimizer failed');
    }
    await load();
    setRunning(false);
  };

  return (
    <section>
      <p className="muted admin-intro">
        Files are moved between fast and compressed storage automatically; users never see this.
      </p>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {stats && optimizer && (
        <InsightsView stats={stats} optimizer={optimizer} running={running} onRunOptimizer={runOptimizer} />
      )}
      <StorageNodesPanel />
      <InsightsPanel />
    </section>
  );
}
