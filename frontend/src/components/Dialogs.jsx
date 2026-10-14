import { Folder, HardDrive } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api/client';

function DialogShell({ title, onClose, children }) {
  useEffect(() => {
    const closeOnEscape = (event) => event.key === 'Escape' && onClose();
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [onClose]);

  return (
    <div className="dialog-backdrop" onMouseDown={onClose}>
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- name entry

/** Asks for a name: used for creating a folder and for renaming files and folders. */
export function NameDialog({ title, initialName, confirmLabel, onSubmit, onClose }) {
  const [name, setName] = useState(initialName);
  const input = useRef(null);

  useEffect(() => {
    input.current?.focus();
    // Select the base name only, so a file's extension survives a quick edit.
    const dot = initialName.lastIndexOf('.');
    input.current?.setSelectionRange(0, dot > 0 ? dot : initialName.length);
  }, [initialName]);

  const submit = (event) => {
    event.preventDefault();
    if (name.trim()) onSubmit(name.trim());
  };

  return (
    <DialogShell title={title} onClose={onClose}>
      <form onSubmit={submit}>
        <input ref={input} className="text-input" value={name} onChange={(e) => setName(e.target.value)} />
        <div className="dialog-actions">
          <button type="button" className="button button-text" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="button button-primary" disabled={!name.trim()}>
            {confirmLabel}
          </button>
        </div>
      </form>
    </DialogShell>
  );
}

// -------------------------------------------------------------------- delete

export function DeleteDialog({ title, children, onConfirm, onClose }) {
  return (
    <DialogShell title={title} onClose={onClose}>
      <p className="dialog-text">{children}</p>
      <div className="dialog-actions">
        <button className="button button-text" onClick={onClose}>
          Cancel
        </button>
        <button className="button button-danger" onClick={onConfirm}>
          Delete
        </button>
      </div>
    </DialogShell>
  );
}

// ---------------------------------------------------------------------- move

export function MoveDialog({ itemName, currentFolderId, movingFolderId, onSubmit, onClose }) {
  const [folders, setFolders] = useState(null);
  const [selected, setSelected] = useState(currentFolderId);

  useEffect(() => {
    void api.allFolders().then(setFolders, () => setFolders([]));
  }, []);

  const rows = useMemo(() => buildPickerRows(folders ?? [], movingFolderId), [folders, movingFolderId]);

  return (
    <DialogShell title={`Move “${itemName}”`} onClose={onClose}>
      <div className="picker" role="radiogroup" aria-label="Destination folder">
        {folders === null && <p className="muted picker-note">Loading folders…</p>}
        {rows.map((row) => (
          <button
            key={row.id ?? 'root'}
            role="radio"
            aria-checked={selected === row.id}
            disabled={row.disabled}
            className={`picker-row ${selected === row.id ? 'picker-row-selected' : ''}`}
            style={{ paddingLeft: 12 + row.depth * 20 }}
            onClick={() => setSelected(row.id)}
          >
            {row.id === null ? <HardDrive size={18} strokeWidth={1.75} /> : <Folder size={18} strokeWidth={1.75} />}
            <span>{row.name}</span>
            {row.id === currentFolderId && <span className="picker-hint">current</span>}
          </button>
        ))}
      </div>
      <div className="dialog-actions">
        <button className="button button-text" onClick={onClose}>
          Cancel
        </button>
        <button
          className="button button-primary"
          disabled={selected === currentFolderId}
          onClick={() => onSubmit(selected)}
        >
          Move here
        </button>
      </div>
    </DialogShell>
  );
}

/** Flattens the folder tree depth-first, so each folder appears right under its parent. */
function buildPickerRows(folders, movingFolderId) {
  const byParent = new Map();
  folders.forEach((folder) => {
    const siblings = byParent.get(folder.parentId) ?? [];
    siblings.push(folder);
    byParent.set(folder.parentId, siblings);
  });

  const rows = [{ id: null, name: 'My files', depth: 0, disabled: false }];
  const walk = (parentId, depth, insideMovedFolder) => {
    const siblings = (byParent.get(parentId) ?? []).sort((a, b) => a.name.localeCompare(b.name));
    siblings.forEach((folder) => {
      const blocked = insideMovedFolder || folder.id === movingFolderId;
      rows.push({ id: folder.id, name: folder.name, depth, disabled: blocked });
      walk(folder.id, depth + 1, blocked);
    });
  };
  walk(null, 1, false);
  return rows;
}
