CREATE UNIQUE INDEX IF NOT EXISTS uq_user_accounts_single_admin
    ON user_accounts ((role))
    WHERE role = 'ADMIN';
