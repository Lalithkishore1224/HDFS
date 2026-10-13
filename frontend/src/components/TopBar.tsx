import { Menu, Search, X } from 'lucide-react';
import type { ReactNode } from 'react';

interface TopBarProps {
  search: string;
  onSearchChange: (value: string) => void;
  /** Opens the navigation drawer; only visible on narrow screens. */
  onMenuClick: () => void;
  /** Shown at the far right, e.g. the account menu. */
  trailing?: ReactNode;
}

export function TopBar({ search, onSearchChange, onMenuClick, trailing }: TopBarProps) {
  return (
    <header className="topbar">
      <button className="icon-button menu-toggle" onClick={onMenuClick} aria-label="Open navigation">
        <Menu size={22} strokeWidth={1.75} />
      </button>
      <label className="search">
        <Search size={18} strokeWidth={1.75} aria-hidden />
        <input
          type="search"
          placeholder="Search in Drive"
          value={search}
          onChange={(event) => onSearchChange(event.target.value)}
        />
        {search && (
          <button type="button" className="icon-button" onClick={() => onSearchChange('')} aria-label="Clear search">
            <X size={16} />
          </button>
        )}
      </label>
      {trailing}
    </header>
  );
}
