import { Cloud, FolderPlus, HardDrive, Plus, Upload } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import type { StorageUsage } from '../types';
import { StorageMeter } from './StorageMeter';

interface SidebarProps {
  usage: StorageUsage | null;
  /** On narrow screens the sidebar is a drawer; this says whether it is showing. */
  open: boolean;
  onGoHome: () => void;
  onNewFolder: () => void;
  onUploadClick: () => void;
}

export function Sidebar({ usage, open, onGoHome, onNewFolder, onUploadClick }: SidebarProps) {
  return (
    <aside className={`sidebar ${open ? 'sidebar-open' : ''}`} aria-label="Navigation">
      <div className="brand">
        <Cloud size={26} strokeWidth={1.75} />
        <span>Drive</span>
      </div>

      <NewMenu onNewFolder={onNewFolder} onUploadClick={onUploadClick} />

      <nav className="nav">
        <button className="nav-item nav-item-active" onClick={onGoHome} aria-current="page">
          <HardDrive size={18} strokeWidth={1.75} />
          <span className="nav-label">My files</span>
        </button>
      </nav>

      <StorageMeter usage={usage} />
    </aside>
  );
}

function NewMenu({ onNewFolder, onUploadClick }: Pick<SidebarProps, 'onNewFolder' | 'onUploadClick'>) {
  const [open, setOpen] = useState(false);
  const container = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const closeOnOutsideClick = (event: MouseEvent) => {
      if (!container.current?.contains(event.target as Node)) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => event.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', closeOnOutsideClick);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('mousedown', closeOnOutsideClick);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [open]);

  const choose = (action: () => void) => () => {
    setOpen(false);
    action();
  };

  return (
    <div className="new-menu" ref={container}>
      <button className="new-button" onClick={() => setOpen(!open)} aria-haspopup="menu" aria-expanded={open}>
        <Plus size={20} strokeWidth={2} />
        New
      </button>
      {open && (
        <div className="menu" role="menu">
          <button className="menu-item" role="menuitem" onClick={choose(onNewFolder)}>
            <FolderPlus size={18} strokeWidth={1.75} />
            New folder
          </button>
          <hr className="menu-divider" />
          <button className="menu-item" role="menuitem" onClick={choose(onUploadClick)}>
            <Upload size={18} strokeWidth={1.75} />
            File upload
          </button>
        </div>
      )}
    </div>
  );
}
