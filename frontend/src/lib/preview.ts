/**
 * How the viewer shows a file. The server makes the final call about what it will serve inline (see
 * PreviewTypes.java); these extension lists mirror it so the UI never asks for something it will refuse.
 */
export type PreviewKind = 'image' | 'pdf' | 'video' | 'audio' | 'csv' | 'text' | 'none';

const IMAGE = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'ico', 'avif', 'svg'];
const VIDEO = ['mp4', 'webm', 'ogv', 'mov'];
const AUDIO = ['mp3', 'wav', 'ogg', 'm4a', 'flac'];
const CSV = ['csv', 'tsv'];
const TEXT = [
  'txt', 'md', 'markdown', 'json', 'log', 'xml', 'html', 'htm', 'css', 'js', 'mjs', 'ts', 'tsx', 'jsx', 'java', 'kt',
  'py', 'rb', 'go', 'rs', 'c', 'h', 'cpp', 'hpp', 'cs', 'php', 'sh', 'bash', 'sql', 'yml', 'yaml', 'toml', 'ini',
  'conf', 'env', 'properties', 'gradle', 'tex',
];

/** Text files larger than this are not loaded into the page; they can still be downloaded. */
export const MAX_TEXT_PREVIEW_BYTES = 2 * 1024 * 1024;

export function previewKindFor(fileName: string): PreviewKind {
  const dot = fileName.lastIndexOf('.');
  if (dot < 0) return 'none';
  const extension = fileName.slice(dot + 1).toLowerCase();

  if (IMAGE.includes(extension)) return 'image';
  if (extension === 'pdf') return 'pdf';
  if (VIDEO.includes(extension)) return 'video';
  if (AUDIO.includes(extension)) return 'audio';
  if (CSV.includes(extension)) return 'csv';
  if (TEXT.includes(extension)) return 'text';
  return 'none';
}

/**
 * Minimal CSV/TSV reader: handles quoted fields, escaped quotes and newlines inside quotes.
 * Good enough for looking at a file; it is not meant to be a data-import parser.
 */
export function parseDelimited(text: string, delimiter: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let inQuotes = false;

  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (inQuotes) {
      if (char === '"' && text[i + 1] === '"') {
        field += '"';
        i++;
      } else if (char === '"') {
        inQuotes = false;
      } else {
        field += char;
      }
    } else if (char === '"') {
      inQuotes = true;
    } else if (char === delimiter) {
      row.push(field);
      field = '';
    } else if (char === '\n' || char === '\r') {
      if (char === '\r' && text[i + 1] === '\n') i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = '';
    } else {
      field += char;
    }
  }
  if (field !== '' || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}
