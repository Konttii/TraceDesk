-- 1. Таблица пользователей
CREATE TABLE IF NOT EXISTS users (
                                     id BIGSERIAL PRIMARY KEY,
                                     username VARCHAR(50) UNIQUE NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL
    );

-- 2. Таблица обращений (тикетов)
CREATE TABLE IF NOT EXISTS tickets (
                                       id BIGSERIAL PRIMARY KEY,
                                       ticket_number VARCHAR(20) UNIQUE NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    author_id BIGINT NOT NULL REFERENCES users(id),
    assignee_id BIGINT REFERENCES users(id),
    attachment_key VARCHAR(255),
    attachment_name VARCHAR(255),
    attachment_type VARCHAR(100),
    attachment_size BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    );

-- Индекс для быстрой фильтрации по статусу (OPEN, IN_PROGRESS, CLOSED)
CREATE INDEX IF NOT EXISTS idx_tickets_status ON tickets(status);
-- Индекс для быстрого поиска обращений конкретного следователя
CREATE INDEX IF NOT EXISTS idx_tickets_author_id ON tickets(author_id);

-- 3. Таблица ответов на обращения
CREATE TABLE IF NOT EXISTS ticket_replies (
                                              id BIGSERIAL PRIMARY KEY,
                                              ticket_id BIGINT NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    author_id BIGINT NOT NULL REFERENCES users(id),
    message TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    );

CREATE INDEX IF NOT EXISTS idx_ticket_replies_ticket_id ON ticket_replies(ticket_id);

-- 4. Таблица истории смены статусов
CREATE TABLE IF NOT EXISTS ticket_status_history (
                                                     id BIGSERIAL PRIMARY KEY,
                                                     ticket_id BIGINT NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    old_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    changed_by_id BIGINT NOT NULL REFERENCES users(id),
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    );

CREATE INDEX IF NOT EXISTS idx_ticket_history_ticket_id ON ticket_status_history(ticket_id);