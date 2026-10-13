import { CloudUpload, FolderPlus, Upload } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type ChangeEvent, type DragEvent } from 'react';
import { ApiError, api } from './api/client';
import { Breadcrumbs } from './components/Breadcrumbs';
import { DeleteDialog, MoveDialog, NameDialog } from './components/Dialogs';
import { FilePreview } from './components/FilePreview';
import { FileTable } from './components/FileTable';
import { Sidebar } from './components/Sidebar';
import { TopBar } from './components/TopBar';
import { UserMenu } from './components/UserMenu';
import type { DraggedItem } from './lib/dragdrop';
import type { Account, DriveFile, FolderItem, FolderSummary, StorageUsage } from './types';

/** The one modal that may be open at a time, together with the item it acts on. */
type ActiveDialog =
  | { kind: 'newFolder' }
  | { kind: 'renameFile'; file: DriveFile }
  | { kind: 'renameFolder'; folder: FolderItem }
  | { kind: 'moveFile'; file: DriveFile }
  | { kind: 'moveFolder'; folder: FolderItem }
  | { kind: 'deleteFile'; file: DriveFile }
  | { kind: 'deleteFolder'; folder: FolderItem };

function useDebounced<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

interface AppProps {
  account: Account;
  /** Accounts waiting for approval; only meaningful for administrators. */
  pendingCount: number;
  onSignOut: () => void;
}

export function App({ account, pendingCount, onSignOut }: AppProps) {
  const [currentFolderId, setCurrentFolderId] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebounced(search, 250);

  const [folders, setFolders] = useState<FolderItem[]>([]);
  const [path, setPath] = useState<FolderSummary[]>([]);
  const [files, setFiles] = useState<DriveFile[]>([]);
  const [usage, setUsage] = useState<StorageUsage | null>(null);
  const [loading, setLoading] = useState(true);

  const [dialog, setDialog] = useState<ActiveDialog | null>(null);
  const [dragging, setDragging] = useState(false);
  const [navOpen, setNavOpen] = useState(false);
  const [viewingId, setViewingId] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const fileInput = useRef<HTMLInputElement>(null);

  /** Browsing the folder tree, as opposed to looking at search results. */
  const browsing = !debouncedSearch;

  const refresh = useCallback(async () => {
    try {
      const [loadedFiles, loadedFolders, loadedPath, loadedUsage] = await Promise.all([
        api.listFiles(debouncedSearch, browsing ? currentFolderId : null),
        browsing ? api.listFolders(currentFolderId) : Promise.resolve([]),
        browsing && currentFolderId ? api.folderPath(currentFolderId) : Promise.resolve([]),
        api.storageUsage(),
      ]);
      setFiles(loadedFiles);
      setFolders(loadedFolders);
      setPath(loadedPath);
      setUsage(loadedUsage);
    } catch (error) {
      if (error instanceof ApiError && error.status === 404 && currentFolderId) {
        setCurrentFolderId(null); // the folder was deleted elsewhere
        setMessage('That folder no longer exists');
      } else {
        setMessage(errorText(error));
      }
    } finally {
      setLoading(false);
    }
  }, [debouncedSearch, browsing, currentFolderId]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (!message) return;
    const timer = setTimeout(() => setMessage(null), 4000);
    return () => clearTimeout(timer);
  }, [message]);

  /** Runs a mutation, reports its outcome in the snackbar, and reloads the listing. */
  const act = async (action: () => Promise<unknown>, success?: string) => {
    try {
      await action();
      if (success) setMessage(success);
    } catch (error) {
      setMessage(errorText(error));
    }
    await refresh();
  };

  const goHome = () => {
    setSearch('');
    setCurrentFolderId(null);
    setNavOpen(false);
  };

  const upload = (selected: FileList | File[], folderId: string | null = currentFolderId) => {
    const list = Array.from(selected);
    if (list.length === 0) return;
    void act(
      () => Promise.all(list.map((file) => api.uploadFile(file, folderId))),
      list.length === 1 ? `Uploaded ${list[0].name}` : `Uploaded ${list.length} files`,
    );
  };

  const onFilesPicked = (event: ChangeEvent<HTMLInputElement>) => {
    if (event.target.files) upload(event.target.files);
    event.target.value = '';
  };

  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setDragging(false);
    upload(event.dataTransfer.files);
  };

  const onDragOver = (event: DragEvent) => {
    if (event.dataTransfer.types.includes('Files')) {
      event.preventDefault();
      setDragging(true);
    }
  };

  const moveItem = (item: DraggedItem, destinationFolderId: string | null) => {
    const move = item.kind === 'file' ? api.moveFile : api.moveFolder;
    void act(() => move(item.id, destinationFolderId), 'Moved');
  };

  const closeDialog = useCallback(() => setDialog(null), []);

  const closeViewer = useCallback(() => {
    setViewingId(null);
    void refresh(); // viewing counts as opening the file, so "last opened" changed
  }, [refresh]);

  /** Closes the dialog, then runs the action it confirmed. */
  const confirm = (action: () => Promise<unknown>, success?: string) => {
    setDialog(null);
    void act(action, success);
  };

  const renderDialog = () => {
    if (!dialog) return null;
    switch (dialog.kind) {
      case 'newFolder':
        return (
          <NameDialog
            title="New folder"
            initialName="Untitled folder"
            confirmLabel="Create"
            onClose={closeDialog}
            onSubmit={(name) => confirm(() => api.createFolder(name, currentFolderId))}
          />
        );
      case 'renameFile':
        return (
          <NameDialog
            title="Rename"
            initialName={dialog.file.name}
            confirmLabel="OK"
            onClose={closeDialog}
            onSubmit={(name) => confirm(() => api.renameFile(dialog.file.id, name))}
          />
        );
      case 'renameFolder':
        return (
          <NameDialog
            title="Rename folder"
            initialName={dialog.folder.name}
            confirmLabel="OK"
            onClose={closeDialog}
            onSubmit={(name) => confirm(() => api.renameFolder(dialog.folder.id, name))}
          />
        );
      case 'moveFile':
        return (
          <MoveDialog
            itemName={dialog.file.name}
            currentFolderId={dialog.file.folderId}
            onClose={closeDialog}
            onSubmit={(destination) => confirm(() => api.moveFile(dialog.file.id, destination), `Moved ${dialog.file.name}`)}
          />
        );
      case 'moveFolder':
        return (
          <MoveDialog
            itemName={dialog.folder.name}
            currentFolderId={dialog.folder.parentId}
            movingFolderId={dialog.folder.id}
            onClose={closeDialog}
            onSubmit={(destination) => confirm(() => api.moveFolder(dialog.folder.id, destination), `Moved ${dialog.folder.name}`)}
          />
        );
      case 'deleteFile':
        return (
          <DeleteDialog
            title="Delete file?"
            onClose={closeDialog}
            onConfirm={() => confirm(() => api.deleteFile(dialog.file.id), `Deleted ${dialog.file.name}`)}
          >
            <strong>{dialog.file.name}</strong> will be permanently deleted and its space released.
          </DeleteDialog>
        );
      case 'deleteFolder':
        return (
          <DeleteDialog
            title="Delete folder?"
            onClose={closeDialog}
            onConfirm={() => confirm(() => api.deleteFolder(dialog.folder.id), `Deleted ${dialog.folder.name}`)}
          >
            <strong>{dialog.folder.name}</strong> and everything inside it, including subfolders, will be permanently
            deleted.
          </DeleteDialog>
        );
    }
  };

  const hasItems = files.length > 0 || folders.length > 0;
  const insideFolder = currentFolderId !== null;

  return (
    <div
      className="app"
      onDragOver={onDragOver}
      onDragLeave={(event) => event.currentTarget === event.target && setDragging(false)}
      onDrop={onDrop}
    >
      <Sidebar
        usage={usage}
        onGoHome={goHome}
        open={navOpen}
        onNewFolder={() => {
          setNavOpen(false);
          setDialog({ kind: 'newFolder' });
        }}
        onUploadClick={() => {
          setNavOpen(false);
          fileInput.current?.click();
        }}
      />
      {navOpen && <div className="sidebar-backdrop" onClick={() => setNavOpen(false)} aria-hidden />}
      <input ref={fileInput} type="file" multiple hidden onChange={onFilesPicked} />

      <div className="main">
        <TopBar
          search={search}
          onSearchChange={setSearch}
          onMenuClick={() => setNavOpen(true)}
          trailing={<UserMenu account={account} pendingCount={pendingCount} onSignOut={onSignOut} />}
        />

        <main className="content">
          {browsing ? (
            <div className="toolbar">
              <Breadcrumbs path={path} onNavigate={setCurrentFolderId} onMoveItem={moveItem} />
              <div className="toolbar-actions">
                <button className="button button-outline" onClick={() => setDialog({ kind: 'newFolder' })}>
                  <FolderPlus size={18} strokeWidth={1.75} />
                  New folder
                </button>
                <button className="button button-outline" onClick={() => fileInput.current?.click()}>
                  <Upload size={18} strokeWidth={1.75} />
                  Upload
                </button>
              </div>
            </div>
          ) : (
            <h1>Search results</h1>
          )}

          {hasItems ? (
            <FileTable
              folders={folders}
              files={files}
              downloadUrl={api.downloadUrl}
              onOpenFolder={(folder) => setCurrentFolderId(folder.id)}
              onViewFile={(file) => setViewingId(file.id)}
              onRenameFolder={(folder) => setDialog({ kind: 'renameFolder', folder })}
              onMoveFolder={(folder) => setDialog({ kind: 'moveFolder', folder })}
              onDeleteFolder={(folder) => setDialog({ kind: 'deleteFolder', folder })}
              onRenameFile={(file) => setDialog({ kind: 'renameFile', file })}
              onMoveFile={(file) => setDialog({ kind: 'moveFile', file })}
              onDeleteFile={(file) => setDialog({ kind: 'deleteFile', file })}
              onMoveItem={moveItem}
              onUploadToFolder={upload}
            />
          ) : (
            !loading && <p className="empty-state">{emptyMessage(debouncedSearch, insideFolder)}</p>
          )}
        </main>
      </div>

      {dragging && (
        <div className="drop-overlay">
          <CloudUpload size={40} strokeWidth={1.5} />
          <p>Drop files to upload{path.length > 0 ? ` to “${path[path.length - 1].name}”` : ''}</p>
          <span className="drop-hint">Drop onto a folder to upload into it</span>
        </div>
      )}

      {renderDialog()}

      {viewingId && (
        <FilePreview
          files={files}
          currentId={viewingId}
          viewUrl={api.viewUrl}
          downloadUrl={api.downloadUrl}
          onChange={setViewingId}
          onClose={closeViewer}
        />
      )}

      {message && (
        <div className="snackbar" role="status">
          {message}
        </div>
      )}
    </div>
  );
}

function emptyMessage(search: string, insideFolder: boolean): string {
  if (search) return `No files match “${search}”.`;
  return insideFolder
    ? 'This folder is empty. Drop files here, or use New to add some.'
    : 'Drop files here, or use New to upload a file or create a folder.';
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : 'Something went wrong';
}
