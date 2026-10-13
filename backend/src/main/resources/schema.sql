-- Runs after Hibernate has created or updated the tables (spring.jpa.defer-datasource-initialization).
--
-- Uniqueness is declared here, as plain SQL, so that it is visible in one place and enforced by the
-- database itself. Without these indexes two simultaneous first sign-ins could create two accounts for
-- one person, and the application would then fail to find "the" account.
CREATE UNIQUE INDEX IF NOT EXISTS ux_accounts_subject ON accounts (subject);
CREATE UNIQUE INDEX IF NOT EXISTS ux_accounts_email ON accounts (email);

-- Lookups the application does constantly; cheap to keep indexed.
CREATE INDEX IF NOT EXISTS ix_stored_files_owner_folder ON stored_files (owner_id, folder_id);
CREATE INDEX IF NOT EXISTS ix_folders_owner_parent ON folders (owner_id, parent_id);
