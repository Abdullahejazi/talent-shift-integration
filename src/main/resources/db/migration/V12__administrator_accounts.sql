ALTER TABLE user_accounts
    ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'CANDIDATE';

ALTER TABLE user_accounts
    DROP CONSTRAINT IF EXISTS chk_user_accounts_role;

ALTER TABLE user_accounts
    ADD CONSTRAINT chk_user_accounts_role CHECK (role IN ('CANDIDATE', 'ADMIN'));

CREATE INDEX IF NOT EXISTS idx_user_accounts_role ON user_accounts(role);
