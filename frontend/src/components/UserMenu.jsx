import { LogOut, ShieldCheck } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import { Avatar } from './Avatar';

export function UserMenu({ account, pendingCount, onSignOut }) {
  const [open, setOpen] = useState(false);
  const container = useRef(null);

  useEffect(() => {
    if (!open) return;
    const closeOnOutsideClick = (event) => {
      if (!container.current?.contains(event.target)) setOpen(false);
    };
    const closeOnEscape = (event) => event.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', closeOnOutsideClick);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('mousedown', closeOnOutsideClick);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [open]);

  return (
    <div className="user-menu" ref={container}>
      <button
        className="avatar-button"
        onClick={() => setOpen(!open)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Account menu"
      >
        <Avatar account={account} size={36} />
        {pendingCount > 0 && <span className="avatar-badge">{pendingCount}</span>}
      </button>

      {open && (
        <div className="menu user-menu-panel" role="menu">
          <div className="user-menu-header">
            <Avatar account={account} size={48} />
            <div className="user-menu-identity">
              <p className="user-menu-name">{account.name || account.username}</p>
              <p className="muted user-menu-email">@{account.username}</p>
            </div>
          </div>
          <hr className="menu-divider" />
          {account.role === 'ADMIN' && (
            <a className="menu-item" role="menuitem" href="#/admin">
              <ShieldCheck size={18} strokeWidth={1.75} />
              Admin panel
              {pendingCount > 0 && <span className="menu-count">{pendingCount} waiting</span>}
            </a>
          )}
          <button className="menu-item" role="menuitem" onClick={onSignOut}>
            <LogOut size={18} strokeWidth={1.75} />
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
