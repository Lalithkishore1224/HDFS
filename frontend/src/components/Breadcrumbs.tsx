import { ChevronRight } from 'lucide-react';
import { useState, type DragEvent } from 'react';
import { isItemDrag, readDraggedItem, type DraggedItem } from '../lib/dragdrop';
import type { FolderSummary } from '../types';

interface BreadcrumbsProps {
  path: FolderSummary[];
  /** Pass null to go to the top level. */
  onNavigate: (folderId: string | null) => void;
  /** An item was dropped on a crumb: move it to that folder (null = top level). */
  onMoveItem: (item: DraggedItem, destinationFolderId: string | null) => void;
}

/** The current location as a clickable trail: My files › Reports › Archive. */
export function Breadcrumbs({ path, onNavigate, onMoveItem }: BreadcrumbsProps) {
  const [hoveredKey, setHoveredKey] = useState<string | null>(null);
  const crumbs = [{ id: null as string | null, name: 'My files' }, ...path];

  return (
    <nav className="breadcrumbs" aria-label="Folder path">
      {crumbs.map((crumb, index) => {
        const isCurrent = index === crumbs.length - 1;
        return (
          <span className="crumb-wrap" key={crumb.id ?? 'root'}>
            {index > 0 && <ChevronRight size={18} strokeWidth={1.75} className="crumb-separator" aria-hidden />}
            {isCurrent ? (
              <h1 className="crumb crumb-current">{crumb.name}</h1>
            ) : (
              <button
                className={`crumb ${hoveredKey === (crumb.id ?? 'root') ? 'drop-target' : ''}`}
                onClick={() => onNavigate(crumb.id)}
                onDragOver={(event: DragEvent) => {
                  if (isItemDrag(event)) {
                    event.preventDefault();
                    setHoveredKey(crumb.id ?? 'root');
                  }
                }}
                onDragLeave={() => setHoveredKey(null)}
                onDrop={(event: DragEvent) => {
                  setHoveredKey(null);
                  const item = readDraggedItem(event);
                  if (item) {
                    event.preventDefault();
                    onMoveItem(item, crumb.id);
                  }
                }}
              >
                {crumb.name}
              </button>
            )}
          </span>
        );
      })}
    </nav>
  );
}
