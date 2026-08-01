CREATE TABLE user_saved_jobs (
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id,job_id)
);

CREATE TABLE user_preferences (
    user_id UUID PRIMARY KEY REFERENCES user_accounts(id) ON DELETE CASCADE,
    new_matching_jobs BOOLEAN NOT NULL DEFAULT TRUE,
    interview_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    employer_messages BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
