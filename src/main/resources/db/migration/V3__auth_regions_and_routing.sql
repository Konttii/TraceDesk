-- 1. Справочник регионов Республики Казахстан
CREATE TABLE regions (
    id VARCHAR(2) PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

INSERT INTO regions (id, name) VALUES
    ('01', 'г. Астана'),
    ('02', 'г. Алматы'),
    ('03', 'Акмолинская область'),
    ('04', 'Актюбинская область'),
    ('05', 'Алматинская область'),
    ('06', 'Атырауская область'),
    ('07', 'Западно-Казахстанская область'),
    ('08', 'Жамбылская область'),
    ('09', 'Карагандинская область'),
    ('10', 'Костанайская область'),
    ('11', 'Кызылординская область'),
    ('12', 'Мангистауская область'),
    ('13', 'Туркестанская область'),
    ('14', 'Павлодарская область'),
    ('15', 'Северо-Казахстанская область'),
    ('16', 'Восточно-Казахстанская область'),
    ('17', 'г. Шымкент'),
    ('18', 'область Абай'),
    ('19', 'область Жетісу'),
    ('20', 'область Ұлытау');

-- 2. Справочник следственных управлений (департаментов)
CREATE TABLE departments (
    id SMALLINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

INSERT INTO departments (id, name) VALUES
    (1, '1 - Следственное управление'),
    (2, '2 - Следственное управление'),
    (3, '3 - Следственное управление'),
    (4, '4 - Следственное управление');

-- 3. Справочник должностей
CREATE TABLE positions (
    id SMALLSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

INSERT INTO positions (name) VALUES
    ('Следователь по особо важным делам'),
    ('Старший следователь'),
    ('Следователь'),
    ('Старший дознаватель'),
    ('Дознаватель'),
    ('Старший криминалист'),
    ('Криминалист'),
    ('Специалист по кадрам и делопроизводству');

-- 4. Модификация таблицы пользователей
ALTER TABLE users
    ADD COLUMN iin VARCHAR(12) UNIQUE,
    ADD COLUMN email VARCHAR(150) UNIQUE,
    ADD COLUMN first_name VARCHAR(50),
    ADD COLUMN last_name VARCHAR(50),
    ADD COLUMN status VARCHAR(25) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN region_id VARCHAR(2) REFERENCES regions(id),
    ADD COLUMN department_id SMALLINT REFERENCES departments(id),
    ADD COLUMN position_id SMALLINT REFERENCES positions(id),
    ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN locked_until TIMESTAMPTZ NULL,
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- 5. Модификация таблицы тикетов для региональной маршрутизации
ALTER TABLE tickets
    ADD COLUMN region_id VARCHAR(2) REFERENCES regions(id);

CREATE INDEX idx_tickets_region_status ON tickets(region_id, status);
