# TraceDesk — Сервис обращений в техподдержку 

Самостоятельный REST-сервис, в котором следователь (`INVESTIGATOR`) сообщает о проблеме и прикладывает скриншот, а оператор техподдержки (`SUPPORT`) берёт обращение в работу, отвечает и закрывает его.
Все переходы статусов (`OPEN → IN_PROGRESS → CLOSED`) фиксируются в истории аудита в рамках одной транзакции БД.

---

## Технологический стек
* **Java 17**, **Spring Boot**, **Spring Security** (HTTP Basic Auth), **Spring Data JPA (Hibernate)**
* **PostgreSQL 17** + миграции схемы **Flyway** (`ddl-auto: validate`)
* **MinIO** (S3-совместимое объектное хранилище для скриншотов PNG / JPEG до 5 МБ)
* **OpenAPI 3 / Swagger UI** (`/swagger-ui.html`) с поддержкой кнопки `Authorize`
* **JUnit 5 + Spring Boot Test + MockMvc** (автоматические интеграционные тесты всех сценариев ТЗ)

---

## Быстрый запуск с пустой базы

1. **Поднимите контейнеры PostgreSQL и MinIO:**
   ```bash
   docker compose up -d
   ```
2. **Запустите сервис:**
   * Linux / macOS:
     ```bash
     ./mvnw spring-boot:run
     ```
   * Windows:
     ```cmd
     .\mvnw.cmd spring-boot:run
     ```

При первом старте:
* **Flyway** автоматически накатывает миграции `V1__init_schema.sql` и `V2__ticket_number_sequence.sql`.
* **DemoDataInitializer** идемпотентно (без дублей при повторных перезапусках) создаёт демо-аккаунты.
* **StorageService** автоматически проверяет и создаёт бакет `tracedesk-attachments` в MinIO.

### Доступные адреса после запуска:
* **Веб-интерфейс (UI с переключением демо-аккаунтов и пагинацией по 10 обращений):** `http://localhost:8080/`
* **Swagger UI (OpenAPI документация):** `http://localhost:8080/swagger-ui.html`
* **MinIO Web Console:** `http://localhost:9001` (логин/пароль: `minioadmin` / `minioadmin`)

---

## Демо-аккаунты (HTTP Basic Auth)

Пароль для всех демо-аккаунтов по умолчанию: **`password`** (настраивается через `DEMO_PASSWORD`).

| Логин | Имя | Роль | Назначение в сценариях |
| :--- | :--- | :--- | :--- |
| `irina` | Ирина (Следователь) | `INVESTIGATOR` | Основной автор обращения «Не скачивается PDF» |
| `pavel` | Павел (Следователь) | `INVESTIGATOR` | Второй следователь для проверки запрета доступа к чужому обращению (`403`) |
| `oleg` | Олег (Оператор поддержки) | `SUPPORT` | Основной оператор техподдержки |
| `anna` | Анна (Оператор поддержки) | `SUPPORT` | Второй оператор для проверки конкурентного взятия в работу (`409`) |

---

## Переменные окружения

Конфигурация в `application.yml` поддерживает переопределение через системные переменные окружения или локальный файл `.env` (шаблон — в файле `.env.example`):

| Переменная | Значение по умолчанию | Описание |
| :--- | :--- | :--- |
| `SERVER_PORT` | `8080` | HTTP-порт приложения |
| `DB_HOST` | `localhost` | Хост PostgreSQL |
| `DB_PORT` | `5432` | Порт PostgreSQL |
| `DB_NAME` | `tracedesk` | Имя базы данных |
| `DB_USER` | `tracedesk` | Пользователь БД |
| `DB_PASSWORD` | `tracedesk` | Пароль БД |
| `MINIO_ENDPOINT` | `http://localhost:9000` | Адрес MinIO S3 API |
| `MINIO_ACCESS_KEY` | `minioadmin` | Ключ доступа MinIO |
| `MINIO_SECRET_KEY` | `minioadmin` | Секретный ключ MinIO |
| `MINIO_BUCKET` | `tracedesk-attachments` | Имя бакета для вложений |
| `DEMO_PASSWORD` | `password` | Пароль демо-аккаунтов |

---

## Контракт REST API

### 1. `POST /tickets` — Создать обращение (`multipart/form-data`)
* **Правило:** Доступно только роли `INVESTIGATOR` (оператор `SUPPORT` не может создавать обращения и получает `403 Forbidden`).
* **Параметры формы:**
  * `title` (обязательный, непустой) — тема обращения.
  * `description` (обязательный, непустой) — описание проблемы.
  * `file` (необязательный) — скриншот `PNG` или `JPEG` размером до `5 МБ` (проверяются размер и сигнатура файла / Magic Bytes).
* **Успешный ответ (`201 Created`):**
  ```json
  {
    "id": 1,
    "ticketNumber": "TD-00001",
    "status": "OPEN",
    "createdAt": "2026-11-12T11:37:56.132+05:00"
  }
  ```
* **Ошибки:** `400 Bad Request` (`VALIDATION_ERROR`, `INVALID_FILE`, `FILE_TOO_LARGE`), `403 Forbidden` (`ACCESS_DENIED`).

### 2. `GET /tickets` — Список обращений с фильтрацией и пагинацией
* **Query-параметры:** `status` (`OPEN`, `IN_PROGRESS`, `CLOSED` — опционально), `page` (по умолчанию `0`), `size` (по умолчанию `10`).
* **Поведение:** Оператор `SUPPORT` видит все обращения. Следователь `INVESTIGATOR` видит только свои обращения.

### 3. `GET /tickets/{id}` — Карточка обращения с ответами и историей
* **Успешный ответ (`200 OK`):** полные данные карточки, список `replies` и список `history` (`oldStatus`, `newStatus`, `changedByUsername`, `changedAt`).
* **Ошибки:** `403 Forbidden` (`ACCESS_DENIED` — при попытке следователя открыть чужое обращение), `404 Not Found` (`TICKET_NOT_FOUND`).

### 4. `POST /tickets/{id}/assign` — Взять обращение в работу
* **Правило:** Доступно только роли `SUPPORT`. Переводит `OPEN → IN_PROGRESS` и сохраняет текущего оператора.
* **Защита от гонки (Race Condition):** Используется оптимистическая блокировка (`@Version`) и немедленный сброс `saveAndFlush`. При одновременном запросе двух операторов назначается ровно один (`200 OK`), второй получает **`409 Conflict`**.

### 5. `POST /tickets/{id}/replies` — Ответить на обращение
* **Тело запроса (`application/json`):**
  ```json
  {
    "message": "Проверьте доступ к материалам дела"
  }
  ```
* **Правило:** Только назначенный оператор в статусе `IN_PROGRESS`. Попытка ответить в статусе `OPEN` или `CLOSED` возвращает **`409 Conflict`** (`INVALID_TICKET_STATUS`).

### 6. `POST /tickets/{id}/close` — Закрыть обращение
* **Ключевое правило (`TicketService.closeTicket`):** Закрытие (`IN_PROGRESS → CLOSED`) возможно только после хотя бы одного ответа оператора (`replyRepository.existsByTicketId(id)`). Попытка закрыть без ответа возвращает **`409 Conflict`** (`CLOSE_WITHOUT_REPLY`).

### 7. `GET /tickets/{id}/attachment` — Скачать вложение
* **Правило:** Возвращает оригинальный файл из MinIO автору обращения или оператору `SUPPORT`. Чужому следователю возвращается `403 Forbidden`.

---

## Формат ошибок

Все ошибки возвращаются в едином JSON-формате (`GlobalExceptionHandler`):
```json
{
  "timestamp": "2026-11-12T11:37:56.389+05:00",
  "status": 409,
  "code": "CLOSE_WITHOUT_REPLY",
  "message": "Нельзя закрыть обращение без хотя бы одного ответа оператора",
  "path": "/tickets/1/close"
}
```

---

## Автоматические тесты и сценарии проверки

В файле [`requests.http`](requests.http) подготовлены 14 готовых HTTP-запросов для пошаговой демонстрации всех проверок ТЗ (включая загрузку синтетического скриншота [`error.png`](error.png)).

Для запуска автоматических интеграционных тестов (`TicketIntegrationTest`) при запущенных контейнерах (`docker compose up -d`) выполните:
```bash
./mvnw test
```
Тесты используют профиль `test` (`application-test.yml`) и полностью изолированы от рабочих данных: они работают в отдельной схеме PostgreSQL `tracedesk_test` и отдельном бакете MinIO `tracedesk-attachments-test`.

Автотесты проверяют:
1. **Основной сценарий:** создание тикета с `error.png` → поиск `OPEN` → взятие в работу → ответ → закрытие → проверка карточки (`CLOSED`, ответ, 3 записи истории) → скачивание и побайтовое сравнение `error.png`.
2. **Права и состояния:** запрет создания обращения оператором `SUPPORT` (`403 ACCESS_DENIED`), запрет чтения чужой карточки и вложения Павлом (`403`), запрет вызова `/assign` Ириной (`403`), запрет закрытия без ответа (`409 CLOSE_WITHOUT_REPLY`), запрет ответа после закрытия (`409`).
3. **Конкурентность (Race Condition):** одновременный вызов `POST /tickets/{id}/assign` двумя операторами (`oleg` и `anna`) в параллельных потоках — один получает `200 OK`, второй получает `409 Conflict`.
