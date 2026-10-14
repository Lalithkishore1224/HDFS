const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

export function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), UNITS.length - 1);
  const value = bytes / 1024 ** exponent;
  return `${value >= 10 ? value.toFixed(0) : value.toFixed(1)} ${UNITS[exponent]}`;
}

export function formatRelativeTime(iso) {
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return 'Just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days} d ago`;
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

const EXTENSION_KINDS = {
  txt: 'document',
  md: 'document',
  pdf: 'document',
  doc: 'document',
  docx: 'document',
  csv: 'sheet',
  xls: 'sheet',
  xlsx: 'sheet',
  png: 'image',
  jpg: 'image',
  jpeg: 'image',
  gif: 'image',
  svg: 'image',
  webp: 'image',
  json: 'code',
  js: 'code',
  ts: 'code',
  java: 'code',
  py: 'code',
  html: 'code',
  log: 'code',
  xml: 'code',
  zip: 'archive',
  gz: 'archive',
  tar: 'archive',
  '7z': 'archive',
};

export function fileKind(name) {
  const extension = name.split('.').pop()?.toLowerCase() ?? '';
  return EXTENSION_KINDS[extension] ?? 'other';
}
