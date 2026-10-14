// Shapes of the data exchanged with the API, as JSDoc typedefs.
// There is no runtime code here: editors use these for hints, e.g.  /** @type {import('./types').DriveFile} */

/**
 * @typedef {Object} DriveFile
 * @property {string} id
 * @property {string} name
 * @property {string | null} folderId The folder holding the file; null means the top level of the drive.
 * @property {string | null} contentType
 * @property {number} sizeBytes
 * @property {string} uploadedAt
 * @property {string} lastAccessedAt
 */

/**
 * What the storage meter shows: space used against the quota.
 * @typedef {Object} StorageUsage
 * @property {number} usedBytes
 * @property {number} quotaBytes
 */

/**
 * Operational numbers for the internal admin page. Tiering is only visible here.
 * @typedef {Object} Stats
 * @property {number} fileCount
 * @property {number} hotFileCount
 * @property {number} coldFileCount
 * @property {number} logicalBytes
 * @property {number} storedBytes
 * @property {number} quotaBytes
 * @property {number} downloads
 * @property {number} coldRestores
 * @property {number} averageResponseMs
 */

/**
 * @typedef {Object} OptimizationReport
 * @property {string} ranAt
 * @property {string} model
 * @property {number} trainingSamples
 * @property {number} filesEvaluated
 * @property {number} movedToCold
 * @property {number} movedToHot
 */

/**
 * The admin's storage summary, written by an AI or, as a fallback, by the built-in summarizer.
 * @typedef {Object} InsightsReport
 * @property {string | null} status OK, ATTENTION or CRITICAL
 * @property {string | null} headline The single most important point
 * @property {string[]} findings What the numbers show
 * @property {string} summary
 * @property {string[]} suggestions
 * @property {'AI' | 'BUILT_IN'} source
 * @property {string | null} model
 * @property {string | null} note Why the built-in summary was used; null when the AI answered.
 * @property {string} generatedAt
 */

/**
 * @typedef {Object} OptimizerStatus
 * @property {number} hotProbabilityThreshold
 * @property {OptimizationReport | null} lastRun
 */

/**
 * @typedef {Object} FolderItem
 * @property {string} id
 * @property {string} name
 * @property {string | null} parentId
 * @property {string} createdAt
 * @property {number} itemCount Files plus direct subfolders inside it.
 */

/**
 * The minimum needed to draw a breadcrumb or a folder picker.
 * @typedef {Object} FolderSummary
 * @property {string} id
 * @property {string} name
 * @property {string | null} parentId
 */

/**
 * @typedef {'USER' | 'ADMIN'} AccountRole
 */

/**
 * @typedef {'PENDING' | 'APPROVED' | 'REJECTED'} AccountStatus
 */

/**
 * The signed-in person.
 * @typedef {Object} Account
 * @property {string} id
 * @property {string} email
 * @property {string | null} name
 * @property {string | null} pictureUrl
 * @property {AccountRole} role
 * @property {AccountStatus} status
 * @property {string} createdAt
 */

/**
 * How this server lets people sign in.
 * @typedef {Object} AuthConfig
 * @property {boolean} googleEnabled
 * @property {boolean} devLoginEnabled
 */

/**
 * An account as listed in the admin panel, with the space it occupies.
 * @typedef {Object} ManagedAccount
 * @property {Account} account
 * @property {number} usedBytes
 */

/**
 * @typedef {Object} StorageNodeStatus
 * @property {string} name
 * @property {'ONLINE' | 'MAINTENANCE' | 'UNREACHABLE'} state
 * @property {boolean} online
 * @property {string | null} endpoint
 * @property {number} chunks
 * @property {number} bytes
 */

/**
 * @typedef {Object} ClusterStatus
 * @property {number} replicationFactor
 * @property {number} nodesThatCanFail
 * @property {number} files
 * @property {number} chunks
 * @property {number} underReplicatedChunks
 * @property {number} chunkBytes
 * @property {boolean} separateProcesses
 * @property {StorageNodeStatus[]} nodes
 */

export {};
