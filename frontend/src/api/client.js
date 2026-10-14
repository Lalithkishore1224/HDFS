/** An error response from the API, carrying the HTTP status so callers can react to 404s. */
export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

/** The backend hands out its CSRF token as a cookie; state-changing requests must echo it in a header. */
function csrfToken() {
  const entry = document.cookie.split('; ').find((cookie) => cookie.startsWith('XSRF-TOKEN='));
  return entry ? decodeURIComponent(entry.split('=')[1]) : undefined;
}

async function request(path, init = {}) {
  const headers = new Headers(init.headers);
  const token = csrfToken();
  if (token && (init.method ?? 'GET') !== 'GET') headers.set('X-XSRF-TOKEN', token);

  const response = await fetch(path, { ...init, headers, credentials: 'same-origin' });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new ApiError(problem?.detail ?? `Request failed (${response.status})`, response.status);
  }
  return response.status === 204 ? undefined : response.json();
}

function jsonRequest(method, body) {
  return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
}

export const api = {
  // ----- signing in
  authConfig() {
    return request('/api/auth/config');
  },

  me() {
    return request('/api/me');
  },

  devLogin(email, name) {
    return request('/api/auth/dev-login', jsonRequest('POST', { email, name }));
  },

  signOut() {
    return request('/api/auth/logout', { method: 'POST' });
  },

  // ----- administration
  accounts(status) {
    return request(status ? `/api/admin/users?status=${status}` : '/api/admin/users');
  },

  approveAccount(id) {
    return request(`/api/admin/users/${id}/approve`, { method: 'POST' });
  },

  rejectAccount(id) {
    return request(`/api/admin/users/${id}/reject`, { method: 'POST' });
  },

  // ----- files
  listFiles(search, folderId) {
    const params = new URLSearchParams({ search });
    if (folderId) params.set('folderId', folderId);
    return request(`/api/files?${params}`);
  },

  uploadFile(file, folderId) {
    const body = new FormData();
    body.append('file', file);
    if (folderId) body.append('folderId', folderId);
    return request('/api/files', { method: 'POST', body });
  },

  renameFile(id, name) {
    return request(`/api/files/${id}`, jsonRequest('PATCH', { name }));
  },

  moveFile(id, folderId) {
    return request(`/api/files/${id}/move`, jsonRequest('POST', { folderId }));
  },

  deleteFile(id) {
    return request(`/api/files/${id}`, { method: 'DELETE' });
  },

  downloadUrl(id) {
    return `/api/files/${id}/download`;
  },

  /** The same bytes, but served for display in the browser rather than as an attachment. */
  viewUrl(id) {
    return `/api/files/${id}/view`;
  },

  // ----- folders
  listFolders(parentId) {
    return request(parentId ? `/api/folders?parentId=${parentId}` : '/api/folders');
  },

  allFolders() {
    return request('/api/folders/all');
  },

  folderPath(id) {
    return request(`/api/folders/${id}/path`);
  },

  createFolder(name, parentId) {
    return request('/api/folders', jsonRequest('POST', { name, parentId }));
  },

  renameFolder(id, name) {
    return request(`/api/folders/${id}`, jsonRequest('PATCH', { name }));
  },

  moveFolder(id, parentId) {
    return request(`/api/folders/${id}/move`, jsonRequest('POST', { parentId }));
  },

  deleteFolder(id) {
    return request(`/api/folders/${id}`, { method: 'DELETE' });
  },

  // ----- storage
  storageUsage() {
    return request('/api/storage');
  },

  // ----- internal operations (not linked from the user interface)
  adminStats() {
    return request('/api/admin/stats');
  },

  optimizerStatus() {
    return request('/api/admin/optimizer');
  },

  summariseStorage() {
    return request('/api/admin/insights', { method: 'POST' });
  },

  storageNodes() {
    return request('/api/admin/storage/nodes');
  },

  setNodeOnline(name, online) {
    return request(`/api/admin/storage/nodes/${name}/${online ? 'online' : 'offline'}`, { method: 'POST' });
  },

  addStorageNode() {
    return request('/api/admin/storage/nodes', { method: 'POST' });
  },

  repairStorage() {
    return request('/api/admin/storage/repair', { method: 'POST' });
  },

  runOptimizer() {
    return request('/api/admin/optimizer/run', { method: 'POST' });
  },
};
