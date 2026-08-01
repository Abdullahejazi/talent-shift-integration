CREATE TABLE workspace_applications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    job_id UUID REFERENCES jobs(id) ON DELETE SET NULL,
    company VARCHAR(200) NOT NULL,
    role_title VARCHAR(240) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'APPLIED',
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_workspace_application_status CHECK (status IN ('APPLIED','SCREENING','INTERVIEW','OFFER','REJECTED','WITHDRAWN'))
);
CREATE INDEX idx_workspace_applications_user ON workspace_applications(user_id, updated_at DESC);

CREATE TABLE workspace_meetings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    title VARCHAR(240) NOT NULL,
    company VARCHAR(200),
    starts_at TIMESTAMPTZ NOT NULL,
    meeting_url TEXT,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_workspace_meetings_user ON workspace_meetings(user_id, starts_at);

CREATE TABLE workspace_conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    participant VARCHAR(200) NOT NULL,
    subject VARCHAR(240),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES workspace_conversations(id) ON DELETE CASCADE,
    sender VARCHAR(20) NOT NULL,
    body TEXT NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at TIMESTAMPTZ,
    CONSTRAINT chk_workspace_message_sender CHECK (sender IN ('USER','EMPLOYER','SUPPORT'))
);
CREATE INDEX idx_workspace_conversations_user ON workspace_conversations(user_id, updated_at DESC);
CREATE INDEX idx_workspace_messages_conversation ON workspace_messages(conversation_id, sent_at);
