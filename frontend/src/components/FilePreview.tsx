import { ChevronLeft, ChevronRight, Download, FileQuestion, X } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { formatBytes, formatRelativeTime } from '../lib/format';
import { MAX_TEXT_PREVIEW_BYTES, parseDelimited, previewKindFor } from '../lib/preview';
import type { DriveFile } from '../types';
import { FileIcon } from './FileIcon';

interface FilePreviewProps {
  /** Every file in the folder being viewed, so the viewer can step to the next and previous one. */
  files: DriveFile[];
  currentId: string;
  viewUrl: (id: string) => string;
  downloadUrl: (id: string) => string;
  onChange: (id: string) => void;
  onClose: () => void;
}

/** Full-screen viewer. Every file can be opened; types the browser cannot render show its details instead. */
export function FilePreview({ files, currentId, viewUrl, downloadUrl, onChange, onClose }: FilePreviewProps) {
  const index = files.findIndex((file) => file.id === currentId);
  const file = files[index];
  const previous = index > 0 ? files[index - 1] : null;
  const next = index >= 0 && index < files.length - 1 ? files[index + 1] : null;

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
      if (event.key === 'ArrowLeft' && previous) onChange(previous.id);
      if (event.key === 'ArrowRight' && next) onChange(next.id);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [previous, next, onChange, onClose]);

  if (!file) return null;

  return (
    <div className="viewer" role="dialog" aria-modal="true" aria-label={`Preview of ${file.name}`}>
      <header className="viewer-bar">
        <button className="icon-button viewer-icon" onClick={onClose} aria-label="Close preview">
          <X size={22} strokeWidth={1.75} />
        </button>
        <FileIcon name={file.name} />
        <h2 className="viewer-title" title={file.name}>{file.name}</h2>
        <a className="icon-button viewer-icon" href={downloadUrl(file.id)} download aria-label={`Download ${file.name}`} title="Download">
          <Download size={22} strokeWidth={1.75} />
        </a>
      </header>

      <div className="viewer-stage" onClick={(event) => event.target === event.currentTarget && onClose()}>
        {previous && (
          <button className="viewer-nav viewer-nav-previous" onClick={() => onChange(previous.id)} aria-label="Previous file">
            <ChevronLeft size={28} strokeWidth={1.75} />
          </button>
        )}

        <PreviewBody key={file.id} file={file} viewUrl={viewUrl} downloadUrl={downloadUrl} />

        {next && (
          <button className="viewer-nav viewer-nav-next" onClick={() => onChange(next.id)} aria-label="Next file">
            <ChevronRight size={28} strokeWidth={1.75} />
          </button>
        )}
      </div>
    </div>
  );
}

interface PreviewBodyProps {
  file: DriveFile;
  viewUrl: (id: string) => string;
  downloadUrl: (id: string) => string;
}

function PreviewBody({ file, viewUrl, downloadUrl }: PreviewBodyProps) {
  const kind = previewKindFor(file.name);
  const url = viewUrl(file.id);

  switch (kind) {
    case 'image':
      return <img className="viewer-image" src={url} alt={file.name} />;
    case 'pdf':
      return <iframe className="viewer-frame" src={url} title={file.name} />;
    case 'video':
      return <video className="viewer-video" src={url} controls autoPlay={false} />;
    case 'audio':
      return (
        <div className="viewer-card">
          <FileIcon name={file.name} />
          <p className="viewer-card-name">{file.name}</p>
          <audio src={url} controls />
        </div>
      );
    case 'text':
    case 'csv':
      return <TextPreview file={file} url={url} delimited={kind === 'csv'} downloadUrl={downloadUrl} />;
    default:
      return <NoPreview file={file} downloadUrl={downloadUrl} reason="This file type can't be previewed in the browser." />;
  }
}

type TextState = { status: 'loading' } | { status: 'ready'; text: string } | { status: 'error'; message: string };

function TextPreview({ file, url, delimited, downloadUrl }: { file: DriveFile; url: string; delimited: boolean; downloadUrl: (id: string) => string }) {
  const tooLarge = file.sizeBytes > MAX_TEXT_PREVIEW_BYTES;
  const [state, setState] = useState<TextState>({ status: 'loading' });

  useEffect(() => {
    if (tooLarge) return;
    const controller = new AbortController();
    fetch(url, { credentials: 'same-origin', signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error(`Could not load the file (${response.status})`);
        setState({ status: 'ready', text: await response.text() });
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setState({ status: 'error', message: error instanceof Error ? error.message : 'Could not load the file' });
        }
      });
    return () => controller.abort();
  }, [url, tooLarge]);

  if (tooLarge) {
    return <NoPreview file={file} downloadUrl={downloadUrl} reason={`This file is larger than ${formatBytes(MAX_TEXT_PREVIEW_BYTES)}, too big to show here.`} />;
  }
  if (state.status === 'loading') return <p className="viewer-message">Loading…</p>;
  if (state.status === 'error') return <p className="viewer-message">{state.message}</p>;

  return delimited ? (
    <DelimitedTable text={state.text} delimiter={file.name.toLowerCase().endsWith('.tsv') ? '\t' : ','} />
  ) : (
    <pre className="viewer-text">{state.text || '(empty file)'}</pre>
  );
}

const MAX_TABLE_ROWS = 1000;

function DelimitedTable({ text, delimiter }: { text: string; delimiter: string }) {
  const rows = useMemo(() => parseDelimited(text, delimiter), [text, delimiter]);
  if (rows.length === 0) return <p className="viewer-message">(empty file)</p>;

  const [header, ...body] = rows;
  const shown = body.slice(0, MAX_TABLE_ROWS);

  return (
    <div className="viewer-table-wrap">
      <table className="viewer-table">
        <thead>
          <tr>{header.map((cell, i) => <th key={i}>{cell}</th>)}</tr>
        </thead>
        <tbody>
          {shown.map((row, r) => (
            <tr key={r}>{row.map((cell, c) => <td key={c}>{cell}</td>)}</tr>
          ))}
        </tbody>
      </table>
      {body.length > shown.length && (
        <p className="viewer-table-note">Showing the first {MAX_TABLE_ROWS} of {body.length} rows. Download the file to see everything.</p>
      )}
    </div>
  );
}

function NoPreview({ file, reason, downloadUrl }: { file: DriveFile; reason: string; downloadUrl: (id: string) => string }) {
  return (
    <div className="viewer-card">
      <FileQuestion size={48} strokeWidth={1.25} className="viewer-card-icon" />
      <p className="viewer-card-name">{file.name}</p>
      <p className="muted">{reason}</p>
      <dl className="viewer-facts">
        <div><dt>Size</dt><dd>{formatBytes(file.sizeBytes)}</dd></div>
        <div><dt>Last opened</dt><dd>{formatRelativeTime(file.lastAccessedAt)}</dd></div>
      </dl>
      <a className="button button-primary" href={downloadUrl(file.id)} download>
        <Download size={18} strokeWidth={1.75} />
        Download
      </a>
    </div>
  );
}
