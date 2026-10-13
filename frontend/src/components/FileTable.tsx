import { Download, Eye, Folder, FolderInput, FolderOpen, GripVertical, Pencil, Trash2 } from 'lucide-react';
import { useState, type DragEvent } from 'react';
import { isFileDrag, isItemDrag, readDraggedItem, startItemDrag, type DraggedItem } from '../lib/dragdrop';
import { formatBytes, formatRelativeTime } from '../lib/format';
import type { DriveFile, FolderItem } from '../types';
import { FileIcon } from './FileIcon';
import { RowMenu } from './RowMenu';

interface FileTableProps {
  folders: FolderItem[];
  files: DriveFile[];
  downloadUrl: (id: string) => string;
  onOpenFolder: (folder: FolderItem) => void;
  onViewFile: (file: DriveFile) => void;
  onRenameFolder: (folder: FolderItem) => void;
  onMoveFolder: (folder: FolderItem) => void;
  onDeleteFolder: (folder: FolderItem) => void;
  onRenameFile: (file: DriveFile) => void;
  onMoveFile: (file: DriveFile) => void;
  onDeleteFile: (file: DriveFile) => void;
  /** An item from the drive was dropped onto a folder row. */
  onMoveItem: (item: DraggedItem, destinationFolderId: string) => void;
  /** Files from the computer were dropped onto a folder row. */
  onUploadToFolder: (files: FileList, folderId: string) => void;
}

const ICON = { size: 18, strokeWidth: 1.75 } as const;

/** Folders first, then files, in one table like a file manager. */
export function FileTable(props: FileTableProps) {
  const { folders, files, downloadUrl } = props;
  const [dropTargetId, setDropTargetId] = useState<string | null>(null);
  const [draggedId, setDraggedId] = useState<string | null>(null);

  const dragHandlers = (item: DraggedItem, label: string) => ({
    draggable: true,
    onDragStart: (event: DragEvent) => {
      startItemDrag(event, item, label);
      setDraggedId(item.id);
    },
    onDragEnd: () => {
      setDraggedId(null);
      setDropTargetId(null);
    },
  });

  const dropHandlers = (folder: FolderItem) => ({
    onDragOver: (event: DragEvent) => {
      if (isItemDrag(event) || isFileDrag(event)) {
        event.preventDefault();
        event.dataTransfer.dropEffect = isItemDrag(event) ? 'move' : 'copy';
        setDropTargetId(folder.id);
      }
    },
    onDragLeave: (event: DragEvent) => {
      if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDropTargetId(null);
    },
    onDrop: (event: DragEvent) => {
      setDropTargetId(null);
      const item = readDraggedItem(event);
      if (item) {
        event.preventDefault();
        event.stopPropagation();
        if (item.id !== folder.id) props.onMoveItem(item, folder.id);
      } else if (event.dataTransfer.files.length > 0) {
        event.preventDefault();
        event.stopPropagation();
        props.onUploadToFolder(event.dataTransfer.files, folder.id);
      }
    },
  });

  return (
    <table className="file-table">
      <thead>
        <tr>
          <th className="col-grip" aria-label="Drag handle" />
          <th>Name</th>
          <th className="col-size">Size</th>
          <th className="col-opened">Last opened</th>
          <th className="col-actions" aria-label="Actions" />
        </tr>
      </thead>
      <tbody>
        {folders.map((folder) => {
          const isTarget = dropTargetId === folder.id;
          const itemCount = folder.itemCount === 1 ? '1 item' : `${folder.itemCount} items`;
          return (
            <tr
              key={folder.id}
              className={`folder-row ${isTarget ? 'drop-target' : ''} ${draggedId === folder.id ? 'is-dragged' : ''}`}
              onDoubleClick={() => props.onOpenFolder(folder)}
              {...dragHandlers({ kind: 'folder', id: folder.id }, folder.name)}
              {...dropHandlers(folder)}
            >
              <td className="col-grip">
                <GripVertical className="grip" size={18} strokeWidth={1.75} aria-hidden />
              </td>
              <td>
                <button className="folder-link" onClick={() => props.onOpenFolder(folder)} title={folder.name}>
                  {isTarget ? (
                    <FolderOpen className="file-icon icon-folder" size={20} strokeWidth={0} fill="currentColor" aria-hidden />
                  ) : (
                    <Folder className="file-icon icon-folder" size={20} strokeWidth={0} fill="currentColor" aria-hidden />
                  )}
                  <span className="name-block">
                    <span className="name-text">{folder.name}</span>
                    <span className="file-meta">{itemCount}</span>
                  </span>
                </button>
              </td>
              <td className="col-size muted">{itemCount}</td>
              <td className="col-opened muted">—</td>
              <td className="col-actions">
                <div className="row-actions">
                  <IconButton label={`Rename ${folder.name}`} onClick={() => props.onRenameFolder(folder)}>
                    <Pencil {...ICON} />
                  </IconButton>
                  <IconButton label={`Move ${folder.name}`} onClick={() => props.onMoveFolder(folder)}>
                    <FolderInput {...ICON} />
                  </IconButton>
                  <IconButton label={`Delete ${folder.name}`} onClick={() => props.onDeleteFolder(folder)}>
                    <Trash2 {...ICON} />
                  </IconButton>
                </div>
                <RowMenu
                  label={`Actions for ${folder.name}`}
                  items={[
                    { label: 'Rename', icon: <Pencil {...ICON} />, onSelect: () => props.onRenameFolder(folder) },
                    { label: 'Move', icon: <FolderInput {...ICON} />, onSelect: () => props.onMoveFolder(folder) },
                    { label: 'Delete', icon: <Trash2 {...ICON} />, onSelect: () => props.onDeleteFolder(folder), danger: true },
                  ]}
                />
              </td>
            </tr>
          );
        })}

        {files.map((file) => (
          <tr
            key={file.id}
            className={draggedId === file.id ? 'is-dragged' : ''}
            {...dragHandlers({ kind: 'file', id: file.id }, file.name)}
          >
            <td className="col-grip">
              <GripVertical className="grip" size={18} strokeWidth={1.75} aria-hidden />
            </td>
            <td>
              <button className="folder-link file-link" onClick={() => props.onViewFile(file)} title={`View ${file.name}`}>
                <FileIcon name={file.name} />
                <span className="name-block">
                  <span className="name-text">{file.name}</span>
                  <span className="file-meta">
                    {formatBytes(file.sizeBytes)} · {formatRelativeTime(file.lastAccessedAt)}
                  </span>
                </span>
              </button>
            </td>
            <td className="col-size">{formatBytes(file.sizeBytes)}</td>
            <td className="col-opened">{formatRelativeTime(file.lastAccessedAt)}</td>
            <td className="col-actions">
              <div className="row-actions">
                <IconButton label={`View ${file.name}`} onClick={() => props.onViewFile(file)}>
                  <Eye {...ICON} />
                </IconButton>
                <a className="icon-button" href={downloadUrl(file.id)} download aria-label={`Download ${file.name}`} title="Download">
                  <Download {...ICON} />
                </a>
                <IconButton label={`Rename ${file.name}`} onClick={() => props.onRenameFile(file)}>
                  <Pencil {...ICON} />
                </IconButton>
                <IconButton label={`Move ${file.name}`} onClick={() => props.onMoveFile(file)}>
                  <FolderInput {...ICON} />
                </IconButton>
                <IconButton label={`Delete ${file.name}`} onClick={() => props.onDeleteFile(file)}>
                  <Trash2 {...ICON} />
                </IconButton>
              </div>
              <RowMenu
                label={`Actions for ${file.name}`}
                items={[
                  { label: 'View', icon: <Eye {...ICON} />, onSelect: () => props.onViewFile(file) },
                  { label: 'Download', icon: <Download {...ICON} />, href: downloadUrl(file.id) },
                  { label: 'Rename', icon: <Pencil {...ICON} />, onSelect: () => props.onRenameFile(file) },
                  { label: 'Move', icon: <FolderInput {...ICON} />, onSelect: () => props.onMoveFile(file) },
                  { label: 'Delete', icon: <Trash2 {...ICON} />, onSelect: () => props.onDeleteFile(file), danger: true },
                ]}
              />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function IconButton({ label, onClick, children }: { label: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button className="icon-button" onClick={onClick} aria-label={label} title={label.split(' ')[0]}>
      {children}
    </button>
  );
}
