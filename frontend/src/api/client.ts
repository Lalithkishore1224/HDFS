import type {
  Account,
  AccountStatus,
  AuthConfig,
  ClusterStatus,
  DriveFile,
  InsightsReport,
  FolderItem,
  FolderSummary,
  ManagedAccount,
  OptimizationReport,
  OptimizerStatus,
  Stats,
  StorageUsage,
} from '../types';

/** An error response from the API, carrying the HTTP status so callers can react to 404s. */
export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

/** The backend hands out its CSRF token as a cookie; state-changing requests must echo it in a header. */
function csrfToken(): string | undefined {
  const entry = document.cookie.split('; ').find((cookie) => cookie.startsWith('XSRF-TOKEN='));
  return entry ? decodeURIComponent(entry.split('=')[1]) : undefined;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const token = csrfToken();
  if (token && (init.method ?? 'GET') !== 'GET') headers.set('X-XSRF-TOKEN', token);

  const response = await fetch(path, { ...init, headers, credentials: 'same-origin' });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new ApiError(problem?.detail ?? `Request failed (${response.status})`, response.status);
  }
  return response.status === 204 ? (undefined as T) : response.json();
}

function jsonRequest(method: string, body: unknown): RequestInit {
  return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
}

export const api = {
  // ----- signing in
  authConfig(): Promise<AuthConfig> {
    return request('/api/auth/config');
  },

  me(): Promise<Account> {
    return request('/api/me');
  },

  devLogin(email: string, name: string): Promise<Account> {
    return request('/api/auth/dev-login', jsonRequest('POST', { email, name }));
  },

  signOut(): Promise<void> {
    return request('/api/auth/logout', { method: 'POST' });
  },

  // ----- administration
  accounts(status: AccountStatus | null): Promise<ManagedAccount[]> {
    return request(status ? `/api/admin/users?status=${status}` : '/api/admin/users');
  },

  approveAccount(id: string): Promise<ManagedAccount> {
    return request(`/api/admin/users/${id}/approve`, { method: 'POST' });
  },

  rejectAccount(id: string): Promise<ManagedAccount> {
    return request(`/api/admin/users/${id}/reject`, { method: 'POST' });
  },

  // ----- files
  listFiles(search: string, folderId: string | null): Promise<DriveFile[]> {
    const params = new URLSearchParams({ search });
    if (folderId) params.set('folderId', folderId);
    return request(`/api/files?${params}`);
  },

  uploadFile(file: File, folderId: string | null): Promise<DriveFile> {
    const body = new FormData();
    body.append('file', file);
    if (folderId) body.append('folderId', folderId);
    return request('/api/files', { method: 'POST', body });
  },

  renameFile(id: string, name: string): Promise<DriveFile> {
    return request(`/api/files/${id}`, jsonRequest('PATCH', { name }));
  },

  moveFile(id: string, folderId: string | null): Promise<DriveFile> {
    return request(`/api/files/${id}/move`, jsonRequest('POST', { folderId }));
  },

  deleteFile(id: string): Promise<void> {
    return request(`/api/files/${id}`, { method: 'DELETE' });
  },

  downloadUrl(id: string): string {
    return `/api/files/${id}/download`;
  },

  /** The same bytes, but served for display in the browser rather than as an attachment. */
  viewUrl(id: string): string {
    return `/api/files/${id}/view`;
  },

  // ----- folders
  listFolders(parentId: string | null): Promise<FolderItem[]> {
    return request(parentId ? `/api/folders?parentId=${parentId}` : '/api/folders');
  },

  allFolders(): Promise<FolderSummary[]> {
    return request('/api/folders/all');
  },

  folderPath(id: string): Promise<FolderSummary[]> {
    return request(`/api/folders/${id}/path`);
  },

  createFolder(name: string, parentId: string | null): Promise<FolderItem> {
    return request('/api/folders', jsonRequest('POST', { name, parentId }));
  },

  renameFolder(id: string, name: string): Promise<FolderItem> {
    return request(`/api/folders/${id}`, jsonRequest('PATCH', { name }));
  },

  moveFolder(id: string, parentId: string | null): Promise<FolderItem> {
    return request(`/api/folders/${id}/move`, jsonRequest('POST', { parentId }));
  },

  deleteFolder(id: string): Promise<void> {
    return request(`/api/folders/${id}`, { method: 'DELETE' });
  },

  // ----- storage
  storageUsage(): Promise<StorageUsage> {
    return request('/api/storage');
  },

  // ----- internal operations (not linked from the user interface)
  adminStats(): Promise<Stats> {
    return request('/api/admin/stats');
  },

  optimizerStatus(): Promise<OptimizerStatus> {
    return request('/api/admin/optimizer');
  },

  summariseStorage(): Promise<InsightsReport> {
    return request('/api/admin/insights', { method: 'POST' });
  },

  storageNodes(): Promise<ClusterStatus> {
    return request('/api/admin/storage/nodes');
  },

  setNodeOnline(name: string, online: boolean): Promise<ClusterStatus> {
    return request(`/api/admin/storage/nodes/${name}/${online ? 'online' : 'offline'}`, { method: 'POST' });
  },

  addStorageNode(): Promise<ClusterStatus> {
    return request('/api/admin/storage/nodes', { method: 'POST' });
  },

  repairStorage(): Promise<{ copiesMade: number; cluster: ClusterStatus }> {
    return request('/api/admin/storage/repair', { method: 'POST' });
  },

  runOptimizer(): Promise<OptimizationReport> {
    return request('/api/admin/optimizer/run', { method: 'POST' });
  },
};
