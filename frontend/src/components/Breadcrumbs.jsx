import { ChevronRight } from 'lucide-react';
import { useState } from 'react';
import { isItemDrag, readDraggedItem } from '../lib/dragdrop';

/** The current location as a clickable trail: My files › Reports › Archive. */
export function Breadcrumbs({ path, onNavigate, onMoveItem }) {
  const [hoveredKey, setHoveredKey] = useState(null);
  const crumbs = [{ id: null, name: 'My files' }, ...path];

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
                onDragOver={(event) => {
                  if (isItemDrag(event)) {
                    event.preventDefault();
                    setHoveredKey(crumb.id ?? 'root');
                  }
                }}
                onDragLeave={() => setHoveredKey(null)}
                onDrop={(event) => {
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
