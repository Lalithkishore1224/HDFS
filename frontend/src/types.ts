export interface DriveFile {
  id: string;
  name: string;
  /** The folder holding the file; null means the top level of the drive. */
  folderId: string | null;
  contentType: string | null;
  sizeBytes: number;
  uploadedAt: string;
  lastAccessedAt: string;
}

/** What the storage meter shows: space used against the quota. */
export interface StorageUsage {
  usedBytes: number;
  quotaBytes: number;
}

/** Operational numbers for the internal admin page. Tiering is only visible here. */
export interface Stats {
  fileCount: number;
  hotFileCount: number;
  coldFileCount: number;
  logicalBytes: number;
  storedBytes: number;
  quotaBytes: number;
  downloads: number;
  coldRestores: number;
  averageResponseMs: number;
}

export interface OptimizationReport {
  ranAt: string;
  model: string;
  trainingSamples: number;
  filesEvaluated: number;
  movedToCold: number;
  movedToHot: number;
}

/** The admin's storage summary, written by an AI or, as a fallback, by the built-in summarizer. */
export interface InsightsReport {
  summary: string;
  suggestions: string[];
  source: 'AI' | 'BUILT_IN';
  model: string | null;
  /** Why the built-in summary was used; null when the AI answered. */
  note: string | null;
  generatedAt: string;
}

export interface OptimizerStatus {
  hotProbabilityThreshold: number;
  lastRun: OptimizationReport | null;
}

export interface FolderItem {
  id: string;
  name: string;
  parentId: string | null;
  createdAt: string;
  /** Files plus direct subfolders inside it. */
  itemCount: number;
}

/** The minimum needed to draw a breadcrumb or a folder picker. */
export interface FolderSummary {
  id: string;
  name: string;
  parentId: string | null;
}

export type AccountRole = 'USER' | 'ADMIN';
export type AccountStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

/** The signed-in person. */
export interface Account {
  id: string;
  email: string;
  name: string | null;
  pictureUrl: string | null;
  role: AccountRole;
  status: AccountStatus;
  createdAt: string;
}

/** How this server lets people sign in. */
export interface AuthConfig {
  googleEnabled: boolean;
  devLoginEnabled: boolean;
}

/** An account as listed in the admin panel, with the space it occupies. */
export interface ManagedAccount {
  account: Account;
  usedBytes: number;
}

export interface StorageNodeStatus {
  name: string;
  state: 'ONLINE' | 'MAINTENANCE' | 'UNREACHABLE';
  online: boolean;
  endpoint: string | null;
  chunks: number;
  bytes: number;
}

export interface ClusterStatus {
  replicationFactor: number;
  nodesThatCanFail: number;
  files: number;
  chunks: number;
  underReplicatedChunks: number;
  chunkBytes: number;
  separateProcesses: boolean;
  nodes: StorageNodeStatus[];
}
