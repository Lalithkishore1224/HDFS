import type { DragEvent } from 'react';

/** Custom drag type, so moving an item inside the drive is told apart from dropping files from the OS. */
const ITEM_TYPE = 'application/x-drive-item';

export interface DraggedItem {
  kind: 'file' | 'folder';
  id: string;
}

/**
 * Starts dragging an item from the drive. The browser's default preview is a faded copy of the whole
 * table row; a small labelled chip is much clearer about what is being moved.
 */
export function startItemDrag(event: DragEvent, item: DraggedItem, label: string): void {
  event.dataTransfer.setData(ITEM_TYPE, JSON.stringify(item));
  event.dataTransfer.effectAllowed = 'move';

  const ghost = document.createElement('div');
  ghost.className = 'drag-ghost';
  ghost.textContent = label;
  document.body.appendChild(ghost);
  event.dataTransfer.setDragImage(ghost, 16, 20);
  // The browser snapshots the element synchronously, so it can be removed on the next frame.
  requestAnimationFrame(() => ghost.remove());
}

/** True while an item from the drive (not an OS file) is being dragged. Readable during dragover. */
export function isItemDrag(event: DragEvent): boolean {
  return event.dataTransfer.types.includes(ITEM_TYPE);
}

export function isFileDrag(event: DragEvent): boolean {
  return event.dataTransfer.types.includes('Files');
}

export function readDraggedItem(event: DragEvent): DraggedItem | null {
  try {
    return JSON.parse(event.dataTransfer.getData(ITEM_TYPE)) as DraggedItem;
  } catch {
    return null;
  }
}
