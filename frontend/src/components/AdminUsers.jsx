import { Check, X } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import { formatBytes, formatRelativeTime } from '../lib/format';

import { Avatar } from './Avatar';

const FILTERS = [
  { value: 'PENDING', label: 'Waiting' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'ALL', label: 'Everyone' },
];

const STATUS_LABELS = {
  PENDING: 'Waiting',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
};

/** The approval queue: who has signed up, and the decision for each person. */
export function AdminUsers({ onChanged }) {
  const [filter, setFilter] = useState('PENDING');
  const [accounts, setAccounts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    try {
      setAccounts(await api.accounts(filter === 'ALL' ? null : filter));
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load accounts');
    } finally {
      setLoading(false);
    }
  }, [filter]);

  useEffect(() => {
    void load();
  }, [load]);

  const decide = async (id, decision) => {
    try {
      await (decision === 'approve' ? api.approveAccount(id) : api.rejectAccount(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'That change could not be saved');
    }
    await load();
    onChanged();
  };

  return (
    <section>
      <div className="segmented" role="tablist" aria-label="Filter accounts">
        {FILTERS.map(({ value, label }) => (
          <button
            key={value}
            role="tab"
            aria-selected={filter === value}
            className={`segment ${filter === value ? 'segment-active' : ''}`}
            onClick={() => setFilter(value)}
          >
            {label}
          </button>
        ))}
      </div>

      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      {accounts.length === 0 ? (
        !loading && (
          <p className="empty-state">
            {filter === 'PENDING' ? 'Nobody is waiting for approval.' : 'No accounts in this list.'}
          </p>
        )
      ) : (
        <table className="file-table accounts-table">
          <thead>
            <tr>
              <th>Person</th>
              <th className="col-status">Status</th>
              <th className="col-joined">Signed up</th>
              <th className="col-used">Storage used</th>
              <th className="col-decision" aria-label="Decision" />
            </tr>
          </thead>
          <tbody>
            {accounts.map(({ account, usedBytes }) => (
              <tr key={account.id}>
                <td>
                  <div className="person">
                    <Avatar account={account} size={36} />
                    <div className="person-text">
                      <p className="person-name">
                        {account.name || account.username}
                        {account.role === 'ADMIN' && <span className="role-tag">Admin</span>}
                      </p>
                      <p className="muted person-email">{account.username}</p>
                      <span className={`status-chip status-${account.status.toLowerCase()} status-inline`}>
                        {STATUS_LABELS[account.status]}
                      </span>
                    </div>
                  </div>
                </td>
                <td className="col-status">
                  <span className={`status-chip status-${account.status.toLowerCase()}`}>
                    {STATUS_LABELS[account.status]}
                  </span>
                </td>
                <td className="col-joined">{formatRelativeTime(account.createdAt)}</td>
                <td className="col-used">{formatBytes(usedBytes)}</td>
                <td className="col-decision">
                  {account.role !== 'ADMIN' && (
                    <div className="decision-actions">
                      {account.status !== 'APPROVED' && (
                        <button
                          className="button button-primary button-small"
                          onClick={() => void decide(account.id, 'approve')}
                        >
                          <Check size={16} strokeWidth={2} />
                          Approve
                        </button>
                      )}
                      {account.status !== 'REJECTED' && (
                        <button
                          className="button button-outline button-small"
                          onClick={() => void decide(account.id, 'reject')}
                        >
                          <X size={16} strokeWidth={2} />
                          {account.status === 'APPROVED' ? 'Revoke' : 'Reject'}
                        </button>
                      )}
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
