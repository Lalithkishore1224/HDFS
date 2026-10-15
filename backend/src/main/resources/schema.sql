-- Runs after Hibernate has created or updated the tables (spring.jpa.defer-datasource-initialization).
--
-- Uniqueness is declared here, as plain SQL, so that it is visible in one place and enforced by the
-- database itself. Without these indexes two simultaneous first sign-ins could create two accounts for
-- one person, and the application would then fail to find "the" account.
CREATE UNIQUE INDEX IF NOT EXISTS ux_accounts_subject ON accounts (subject);
CREATE UNIQUE INDEX IF NOT EXISTS ux_accounts_email ON accounts (email);

-- Username and password sign-in. Accounts created before it keep their email; give them a username from
-- the part before the "@" (numbered when two would collide) so an administrator can still sign in to them.
ALTER TABLE accounts ALTER COLUMN email DROP NOT NULL;
UPDATE accounts SET username = x.u
FROM (
    SELECT id, base || CASE WHEN rn > 1 THEN rn::text ELSE '' END AS u
    FROM (
        SELECT id,
               lower(regexp_replace(split_part(email, '@', 1), '[^A-Za-z0-9._-]', '', 'g')) AS base,
               row_number() OVER (PARTITION BY lower(regexp_replace(split_part(email, '@', 1), '[^A-Za-z0-9._-]', '', 'g'))
                                  ORDER BY created_at) AS rn
        FROM accounts
        WHERE username IS NULL AND email IS NOT NULL
    ) t
) x
WHERE accounts.id = x.id AND length(x.u) >= 3;
CREATE UNIQUE INDEX IF NOT EXISTS ux_accounts_username ON accounts (username);

-- Lookups the application does constantly; cheap to keep indexed.
CREATE INDEX IF NOT EXISTS ix_stored_files_owner_folder ON stored_files (owner_id, folder_id);
CREATE INDEX IF NOT EXISTS ix_folders_owner_parent ON folders (owner_id, parent_id);
