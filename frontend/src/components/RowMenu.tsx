import { MoreVertical } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';

export interface RowMenuItem {
  label: string;
  icon: ReactNode;
  /** Either a click handler or, for downloads, a link. */
  onSelect?: () => void;
  href?: string;
  danger?: boolean;
}

interface RowMenuProps {
  /** Accessible name of the trigger, e.g. "Actions for report.pdf". */
  label: string;
  items: RowMenuItem[];
}

const ITEM_HEIGHT = 44;
const MENU_PADDING = 16;

/**
 * A "more actions" button. The hover icons on each row can't be used on touch screens or in a narrow
 * layout, so this folds the same actions into one menu. It is positioned with fixed coordinates so
 * the scrolling table can never clip it.
 */
export function RowMenu({ label, items }: RowMenuProps) {
  const [position, setPosition] = useState<{ top: number; right: number } | null>(null);
  const trigger = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!position) return;
    const close = () => setPosition(null);
    const closeOnOutsideClick = (event: MouseEvent) => {
      const target = event.target as Node;
      if (!trigger.current?.contains(target) && !(target as Element).closest?.('.row-menu-panel')) close();
    };
    const closeOnEscape = (event: KeyboardEvent) => event.key === 'Escape' && close();
    document.addEventListener('mousedown', closeOnOutsideClick);
    document.addEventListener('keydown', closeOnEscape);
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);
    return () => {
      document.removeEventListener('mousedown', closeOnOutsideClick);
      document.removeEventListener('keydown', closeOnEscape);
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', close, true);
    };
  }, [position]);

  const toggle = () => {
    if (position) {
      setPosition(null);
      return;
    }
    const rect = trigger.current?.getBoundingClientRect();
    if (!rect) return;
    const menuHeight = items.length * ITEM_HEIGHT + MENU_PADDING;
    const opensUpwards = rect.bottom + menuHeight + 8 > window.innerHeight;
    setPosition({
      top: opensUpwards ? Math.max(8, rect.top - menuHeight - 4) : rect.bottom + 4,
      right: Math.max(8, window.innerWidth - rect.right),
    });
  };

  return (
    <div className="row-menu">
      <button ref={trigger} className="icon-button" onClick={toggle} aria-label={label} aria-haspopup="menu" aria-expanded={position !== null}>
        <MoreVertical size={20} strokeWidth={1.75} />
      </button>
      {position && (
        <div className="menu row-menu-panel" role="menu" style={{ position: 'fixed', top: position.top, right: position.right, left: 'auto' }}>
          {items.map((item) => {
            const className = `menu-item ${item.danger ? 'menu-item-danger' : ''}`;
            const close = () => setPosition(null);
            return item.href ? (
              <a key={item.label} className={className} role="menuitem" href={item.href} download onClick={close}>
                {item.icon}
                {item.label}
              </a>
            ) : (
              <button
                key={item.label}
                className={className}
                role="menuitem"
                onClick={() => {
                  close();
                  item.onSelect?.();
                }}
              >
                {item.icon}
                {item.label}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
