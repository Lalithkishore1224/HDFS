import { File, FileArchive, FileCode, FileImage, FileSpreadsheet, FileText } from 'lucide-react';
import { fileKind, type FileKind } from '../lib/format';

const ICONS: Record<FileKind, { Icon: typeof File; className: string }> = {
  document: { Icon: FileText, className: 'icon-document' },
  sheet: { Icon: FileSpreadsheet, className: 'icon-sheet' },
  image: { Icon: FileImage, className: 'icon-image' },
  code: { Icon: FileCode, className: 'icon-code' },
  archive: { Icon: FileArchive, className: 'icon-archive' },
  other: { Icon: File, className: 'icon-other' },
};

export function FileIcon({ name }: { name: string }) {
  const { Icon, className } = ICONS[fileKind(name)];
  return <Icon className={`file-icon ${className}`} size={20} strokeWidth={1.75} aria-hidden />;
}
