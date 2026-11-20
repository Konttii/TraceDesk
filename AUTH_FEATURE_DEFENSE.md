# Шпаргалка для устной защиты новой фичи TraceDesk (Code Walkthrough)

> **Как пользоваться этим файлом на защите:** открывай в IntelliJ IDEA нужный класс через `Ctrl + N`, ставь курсор на указанный метод и объясняй логику простыми словами по тезисам ниже. В документе разобран **только новый функционал** (регистрация, Redis, RabbitMQ, ИИН-авторизация, админка, региональная маршрутизация и сброс пароля).

---

## 1. Карта кода: «Что и в каком файле показывать»

| Слой | Файл / Класс | Ключевые методы | За что отвечает (в 1 предложении) |
| :--- | :--- | :--- | :--- |
| **БД (Flyway)** | `V3__auth_regions_and_routing.sql` | SQL DDL / DML | Добавляет справочники РК (20 регионов, 4 управления, 8 должностей), новые поля в `users` и `region_id` + индекс в `tickets`. |
| **Сущность** | `User.java` | геттеры/сеттеры, `prePersist()` | Хранит ИИН, Email, ФИО, статус (`UserStatus`), связи со справочниками, `failedLoginAttempts` и `lockedUntil`. |
| **Сущность** | `Ticket.java` | `getRegion()`, `setRegion()` | Привязывает каждое обращение к региону РК (`region_id`) для маршрутизации. |
| **Репозиторий** | `UserRepository.java` | `findByIin`, `findWithProfileById`, `countByRoleAndStatus` | Ищет пользователей по ИИН/Email с `@EntityGraph` и считает активных админов для защиты от удаления последнего. |
| **Репозиторий** | `TicketRepository.java` | `findByRegionId`, `findByRegionIdAndStatus`, `findWithDetailsById` | Фильтрует тикеты по региону и подгружает связи одним запросом через `@EntityGraph`. |
| **Справочники** | `DictionaryController.java` | `getRegions()`, `getDepartments()`, `getPositions()` | Отдаёт публичные списки регионов, управлений и должностей на форму регистрации (`/api/dictionaries/**`). |
| **Валидаторы** | `IinValidator.java`, `PasswordValidator.java` | `validateOrThrow()`, `calculateControlDigit()`, `evaluateStrength()` | Проверяет 12 цифр, дату рождения, 7-ю цифру века и контрольную сумму ИИН, а также сложность пароля. |
| **Регистрация** | `AuthRegistrationController.java`, `RegistrationService.java` | `initRegistration()`, `verifyOtp()`, `resendOtp()` | Ведёт 2-шаговую регистрацию через черновик в Redis (TTL 10 мин) с защитой от перебора и спама. |
| **Очередь (Config)** | `RabbitMqConfig.java` | `emailExchange()`, `emailQueue()`, `emailBinding()`, `jackson2JsonMessageConverter()` | Настраивает Direct Exchange, durable-очередь, binding и JSON-сериализацию сообщений для писем. |
| **Очередь (Email)** | `EmailNotificationProducer.java`, `EmailNotificationConsumer.java` | `publish()`, `sendOtpVerificationEmail()`, `handleEmailMessage()` | Кладет задачи на письма в RabbitMQ и асинхронно рендерит Thymeleaf-шаблоны с отправкой через SMTP. |
| **Security** | `SecurityConfig.java`, `AppUserDetailsService.java`, `AppUserPrincipal.java` | `securityFilterChain()`, `loadUserByUsername()`, `isEnabled()`, `isAccountNonLocked()` | Настраивает доступы, вход по 12-значному ИИН и различает статусы `PENDING_APPROVAL`, `BLOCKED` и 1-часовой бан. |
| **Блокировка входа** | `LoginAttemptService.java` | `onAuthenticationFailure()`, `onAuthenticationSuccess()` | Слушает события Spring Security, ставит бан на 5 мин после 3 ошибок и на 1 час за каждые 2 следующие ошибки. |
| **Админка** | `AdminController.java`, `AdminUserService.java` | `approveRegistration()`, `rejectRegistration()`, `updateUserProfile()`, `blockUser()`, `unblockUser()` | Одобряет/отклоняет заявки, редактирует сотрудников и блокирует доступ с защитой от самоблокировки. |
| **Маршрутизация** | `TicketService.java` | `createTicket()`, `listTickets()`, `checkReadAccess()`, `checkSupportRegionAccess()` | Привязывает тикет к региону автора и не пускает операторов `SUPPORT` в чужие регионы (`403`). |
| **Сброс пароля** | `PasswordResetController.java`, `PasswordResetService.java` | `initPasswordReset()`, `verifyOtp()`, `confirmPasswordReset()`, `maskEmail()` | Сбрасывает забытый пароль по ИИН через OTP в Redis + RabbitMQ, маскирует почту и снимает бан входа. |

---

## 2. Как в нашем проекте работает Redis (Регистрация и защита от перебора)

В `RegistrationService` используется `StringRedisTemplate` — все временные данные хранятся в Redis как JSON-строки или флаги с автоматическим временем жизни (**TTL**).

### Ключи Redis и их TTL

| Ключ в Redis | Константа в `RegistrationService` | Что внутри | TTL |
| :--- | :--- | :--- | :--- |
| `reg:draft:<email>` | `DRAFT_KEY_PREFIX = "reg:draft:"` | JSON объекта `RegistrationDraft` (анкета, BCrypt-хэш пароля, `otpCode`, `verifyAttempts`, `resendCount`) | **10 минут** (`DRAFT_TTL`) |
| `reg:cooldown:<email>` | `COOLDOWN_KEY_PREFIX = "reg:cooldown:"` | Флаг `"1"` — запрещает повторную отправку кода чаще раза в минуту | **60 секунд** (`COOLDOWN_TTL`) |
| `reg:ban:<email>` | `BAN_KEY_PREFIX = "reg:ban:"` | Флаг `"1"` — временная блокировка почты после исчерпания всех попыток | **1 час** (`BAN_TTL`) |

### Разбор методов `RegistrationService`

1. **`initRegistration(RegisterInitRequest request)`**
   - **Почему не пишем сразу в PostgreSQL?** Пользователь ещё не подтвердил почту. Если писать в БД сразу, таблица `users` забьётся фейковыми/брошенными заявками, которые будут занимать чужие ИИН и Email. Пока код не введён, все данные живут в Redis 10 минут и сами удалятся по TTL.
   - **Как проверяем уникальность и данные:** сначала `checkNotBanned(normalizedEmail)`, затем проверяем формат имени, 12 цифр ИИН, сложность пароля (`PasswordValidator`), допустимые роли (`INVESTIGATOR` или `SUPPORT`), затем `checkUserUniqueness(iin, normalizedEmail)` (`userRepository.existsByIin` и `existsByEmailIgnoreCase` в PostgreSQL), контрольную сумму ИИН (`iinValidator.validateOrThrow`), наличие региона/управления/должности в БД.
   - Далее генерируем 6-значный OTP через `SecureRandom`, хэшируем пароль через `PasswordEncoder`, сохраняем `RegistrationDraft` в Redis (`saveDraft`), ставим кулдаун (`setCooldown`) и кидаем событие в RabbitMQ (`emailNotificationProducer.sendOtpVerificationEmail`).

2. **`verifyOtp(VerifyOtpRequest request)` — 2-уровневая защита**
   - **Уровень 1 (попытки ввода кода — макс. 5):** при неверном коде увеличиваем `verifyAttempts`. Пока `attempts < 5` (`MAX_VERIFY_ATTEMPTS`), перезаписываем черновик и кидаем `400 INVALID_OTP_CODE`. На 5-й ошибке проверяем `resendCount`:
     - Если `resendCount < 3`, обнуляем `otpCode = null` в черновике и кидаем `400 OTP_ATTEMPTS_EXCEEDED` — старый код «сгорает», пользователь обязан запросить новый.
   - **Уровень 2 (лимит переотправок — макс. 3 -> бан):** если на 5-й ошибке `draft.resendCount() >= 3` (`MAX_RESEND_COUNT`), вызываем `clearDraftAndCooldown` и `banEmail(normalizedEmail)` (ключ `reg:ban:<email>` на 1 час) и кидаем `429 EMAIL_VERIFICATION_BLOCKED`.
   - **При успехе:** повторно вызываем `checkUserUniqueness` (защита от гонки, если кто-то занял ИИН за эти 10 минут), создаём `User` в PostgreSQL со статусом `UserStatus.PENDING_APPROVAL` и чистим черновик в Redis (`clearDraftAndCooldown`).

3. **`resendOtp(ResendOtpRequest request)` — бэкенд-кулдаун 60 сек**
   - Сначала проверяем бан и наличие черновика.
   - Затем проверяем `redisTemplate.hasKey("reg:cooldown:" + normalizedEmail)`. Если ключ жив — берём остаток времени через `redisTemplate.getExpire(cooldownKey, TimeUnit.SECONDS)` и возвращаем `429 OTP_COOLDOWN_ACTIVE` с точным количеством секунд до разблокировки.
   - Если `draft.resendCount() >= 3` — чистим черновик и ставим `banEmail` на 1 час (`429 EMAIL_VERIFICATION_BLOCKED`).
   - Иначе: увеличиваем `resendCount`, сбрасываем `verifyAttempts = 0`, генерируем новый код, обновляем черновик, снова ставим кулдаун на 60 сек и отправляем письмо через RabbitMQ.

> **Как показать в коде за 1 минуту:** открой `RegistrationService.java`, наведи курсор на константы в строках `39–48` (`DRAFT_KEY_PREFIX`, `COOLDOWN_KEY_PREFIX`, `BAN_KEY_PREFIX`), а затем покажи методы внизу файла (строки `390–427`): `saveDraft` (`opsForValue().set(..., DRAFT_TTL)`), `getDraftOrThrow` (`opsForValue().get`), `setCooldown`, `banEmail` и `clearDraftAndCooldown` (`redisTemplate.delete`).

---

## 3. Как в нашем проекте работает RabbitMQ (Асинхронные письма)

### Поток отправки письма

`Service` (`RegistrationService` / `AdminUserService` / `PasswordResetService`) -> `EmailNotificationProducer` (`RabbitTemplate.convertAndSend`) -> `DirectExchange` (`tracedesk.email.exchange`) -> `Binding` (`tracedesk.email.routing-key`) -> `Queue` (`tracedesk.email.queue`) -> `EmailNotificationConsumer` (`@RabbitListener`) -> `TemplateEngine` (Thymeleaf HTML) -> `JavaMailSender` (SMTP)

### Точные названия из кода

- **`RabbitMqConfig.java`**:
  - Exchange: `EMAIL_EXCHANGE = "tracedesk.email.exchange"` (`DirectExchange`, `durable = true`, `autoDelete = false`).
  - Queue: `EMAIL_QUEUE = "tracedesk.email.queue"` (`QueueBuilder.durable(emailQueueName).build()`).
  - Routing Key: `EMAIL_ROUTING_KEY = "tracedesk.email.routing-key"`.
  - Биндинг: `emailBinding(Queue emailQueue, DirectExchange emailExchange)` связывает их по `emailRoutingKey`.
- **`EmailMessageEvent.java`**: Java `record(toEmail, subject, templateName, variables)`.
- **`EmailNotificationProducer.java`**: метод `publish(EmailMessageEvent)` вызывает `rabbitTemplate.convertAndSend(emailExchange, emailRoutingKey, event)` и готовит 4 типа писем (шаблоны из `src/main/resources/templates/email/`):
  - `email/otp-verification` — код регистрации;
  - `email/account-decision` — решение админа (`APPROVE` / `REJECT`);
  - `email/password-reset-otp` — код сброса пароля;
  - `email/password-changed` — уведомление о смене пароля.
- **`EmailNotificationConsumer.java`**: метод `handleEmailMessage(EmailMessageEvent)` с аннотацией `@RabbitListener(queues = ...)` прогоняет шаблон через Thymeleaf `TemplateEngine` (`Locale.forLanguageTag("ru")`), собирает HTML-письмо через `MimeMessageHelper` (UTF-8) и отправляет через `mailSender.send(mimeMessage)`.

### Зачем нужны `Jackson2JsonMessageConverter` и `durable = true`

- **`Jackson2JsonMessageConverter`** (в `RabbitMqConfig`): превращает Java-объект `EmailMessageEvent` в обычный читаемый JSON при отправке в RabbitMQ и обратно в `EmailMessageEvent` у консьюмера. Без него Spring использует небезопасную и жёсткую стандартную Java-сериализацию (`Serializable`).
- **`durable = true` (и `QueueBuilder.durable(...)`)**: гарантирует, что Exchange и Queue переживут перезапуск контейнера/брокера RabbitMQ и не исчезнут вместе с настройками маршрутизации.

### Ответ на вопрос: *«Почему вы использовали RabbitMQ, а не вызвали `mailSender.send()` напрямую?»*

> «Прямой вызов `mailSender.send()` блокирует HTTP-поток Tomcat на несколько секунд, пока идёт общение с SMTP-сервером — пользователь видит зависание, а если SMTP временно недоступен, запрос вообще падает с ошибкой. Через RabbitMQ бэкенд мгновенно кладёт JSON-событие в очередь и сразу отвечает клиенту, а `EmailNotificationConsumer` в фоне рендерит Thymeleaf-шаблон и отправляет письмо (при ошибке консьюмер бросит исключение и RabbitMQ сохранит сообщение для повторной доставки)».

---

## 4. Авторизация по ИИН, статусы аккаунта и прогрессивная блокировка (5 мин -> 1 час)

### Как Spring Security находит пользователя по ИИН
- В `AppUserDetailsService.loadUserByUsername(String login)` мы не привязаны только к `username`:
  ```java
  userRepository.findByIin(normalizedLogin)
          .or(() -> userRepository.findByUsername(normalizedLogin))
  ```
  Сначала ищем по 12-значному `iin` (основной способ входа), а `findByUsername` оставлен как запасной вариант для старых демо-аккаунтов.
- Метод возвращает `AppUserPrincipal` — нашу обёртку над `User`, реализующую `UserDetails`. В `AppUserPrincipal.getUsername()` возвращается `user.getIin()` (если он есть).

### Где и как проверяются статусы `PENDING_APPROVAL`, `REJECTED`, `BLOCKED`
1. В `AppUserPrincipal.isEnabled()` проверяется строго:
   ```java
   return user.getStatus() == UserStatus.ACTIVE;
   ```
   Если у пользователя статус `PENDING_APPROVAL`, `REJECTED` или `BLOCKED`, Spring Security при аутентификации сам выбрасывает `DisabledException`.
2. Чтобы фронтенд получил **понятную причину**, в `SecurityConfig` настроена кастомная `AuthenticationEntryPoint` (`authEntryPoint`):
   - Если поймано `DisabledException`, вызывается приватный метод `resolveUserFromBasicAuth(request, userRepository)` — он декодирует заголовок `Authorization: Basic ...`, достаёт ИИН и смотрит реальный статус в БД:
     - `PENDING_APPROVAL` -> возвращает `403` с кодом `ACCOUNT_PENDING_APPROVAL` (*«Ваша заявка еще находится на рассмотрении у администратора»*).
     - `REJECTED` или `BLOCKED` -> возвращает `403` с кодом `ACCOUNT_BLOCKED` (*«Доступ к системе заблокирован администратором»*).

### Прогрессивный счётчик ошибок пароля (`LoginAttemptService`)
- В `LoginAttemptService` использован событийный подход Spring Security через `@EventListener`:
  - **`onAuthenticationFailure(AuthenticationFailureBadCredentialsEvent)`**: срабатывает при каждом неверном пароле и ведёт накопительный счётчик `failedLoginAttempts`:
    1. **3 ошибки** (`INITIAL_LOCK_ATTEMPTS = 3`) -> бан на **5 минут** (`SHORT_LOCK_DURATION_MINUTES = 5`, `lockedUntil = now + 5m`).
    2. **После истечения 5 минут даются 2 попытки** (`attempts = 4`, `attempts = 5`): на 4-й ошибке бан не ставится, а на 5-й (`(attempts - 3) % 2 == 0`) -> бан на **1 час** (`LONG_LOCK_DURATION_HOURS = 1`, `lockedUntil = now + 1h`).
    3. **После истечения 1 часа** снова даются 2 попытки (6-я и 7-я ошибки -> бан на 1 час, и так далее).
  - **`onAuthenticationSuccess(AuthenticationSuccessEvent)`**: при успешном входе сбрасывает `failedLoginAttempts = 0` и `lockedUntil = null` в БД и в текущем `AppUserPrincipal`.
- В `AppUserPrincipal.isAccountNonLocked()` идёт проверка:
  ```java
  return user.getLockedUntil() == null || !user.getLockedUntil().isAfter(OffsetDateTime.now());
  ```
  Пока блокировка активна, Spring Security кидает `LockedException` ещё до проверки пароля (поэтому во время бана `failedLoginAttempts` не накручивается), а `authEntryPoint` в `SecurityConfig` смотрит на стадию блокировки пользователя и возвращает HTTP **`423 ACCOUNT_LOCKED`** с текстом про 5 минут или 1 час.
- **Почему обошлись без фонового `@Scheduled`?** Фоновая задача, которая каждую минуту обходит всю таблицу `users` и снимает блокировки, даёт лишнюю нагрузку на БД и не нужна. У нас разблокировка происходит **лениво по времени**: как только текущее время перешагнуло `lockedUntil`, `isAccountNonLocked()` сам вернёт `true`, пользователь сможет войти, и `onAuthenticationSuccess` (или следующий `onAuthenticationFailure`) почистит поле в БД.

---

## 5. Панель Администратора и защита от «выстрела в ногу»

### Защита эндпоинтов
- В `SecurityConfig` прописано:
  ```java
  .requestMatchers("/admin/**").hasRole("ADMIN")
  ```
  Любой не-админ (следователи и саппорты) при попытке обратиться к `/admin/**` получает `403 ACCESS_DENIED` от `accessDeniedHandler`.
- В `AdminController` в методы передаётся `@AuthenticationPrincipal AppUserPrincipal principal`, чтобы сервис знал, **кто именно** (какой админ) сейчас нажимает кнопку.

### Одобрение и отклонение заявок (`AdminUserService`)
- **`approveRegistration(Long id)`**: проверяет, что статус строго `PENDING_APPROVAL` (иначе `409 INVALID_USER_STATUS`), переводит в `UserStatus.ACTIVE` и отправляет письмо `"APPROVE"`.
- **`rejectRegistration(Long id, User currentAdmin)`**: проверяет `PENDING_APPROVAL`, переводит в `UserStatus.REJECTED` и отправляет письмо `"REJECT"`.
  - **Почему при `reject` запись остаётся в БД, а не удаляется?**
    1. Сохраняется история/аудит: админ в `/admin/users?status=REJECTED` видит, кому и когда уже было отказано.
    2. Предотвращается бесконечный спам заявками: пока запись с этим `iin` и `email` лежит в `users`, `RegistrationService.checkUserUniqueness()` не даст человеку просто заново подать заявку с теми же данными.
- **Важная деталь — `publishAfterCommit(...)`**: в `approveRegistration` и `rejectRegistration` письмо в RabbitMQ отправляется через `TransactionSynchronizationManager.registerSynchronization(... afterCommit())`. Это гарантирует, что письмо об одобрении не улетит раньше (или вместо) сохранения статуса в PostgreSQL, если транзакция вдруг откатится.

### Защита от самоблокировки и удаления/понижения последнего `ADMIN`
В `AdminUserService` вшиты две жёсткие проверки (обе возвращают `409 Conflict`):
1. **`SELF_ACTION_FORBIDDEN`**: в `blockUser`, `rejectRegistration` и при смене роли в `updateUserProfile` сравнивается `targetUser.getId().equals(currentAdmin.getId())`. Админ физически не может заблокировать сам себя или снять с себя роль `ADMIN`.
2. **`LAST_ADMIN_PROTECTION`**: метод `ensureNotLastActiveAdmin(User targetUser)` проверяет:
   ```java
   targetUser.getRole() == Role.ADMIN
       && targetUser.getStatus() == UserStatus.ACTIVE
       && userRepository.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE) <= 1
   ```
   Если в системе остался всего 1 активный администратор, его нельзя ни заблокировать (`blockUser`), ни понизить в роли (`updateUserProfile`), чтобы система не осталась без управления.
- Дополнительно в `unblockUser(id)` при разблокировке админ не только возвращает `UserStatus.ACTIVE`, но и сразу сбрасывает `lockedUntil = null` и `failedLoginAttempts = 0`.

---

## 6. Региональная маршрутизация тикетов по РК

### Почему `region_id` в `V3` — это `VARCHAR(2)`, а не `INT`
- Официальные коды регионов Казахстана двузначные с ведущим нулём: `'01'` (Астана), `'02'` (Алматы), ..., `'09'` (Карагандинская обл.), ..., `'20'` (область Ұлытау).
- Тип `INT` отбросил бы ведущий ноль (`01` превратилось бы в `1`), из-за чего ломалось бы отображение в UI/API и лексикографическая сортировка. `VARCHAR(2)` хранит точный строковый код `'01'..'20'`, а индекс `idx_tickets_region_status` в `V3` ускоряет выборку тикетов по региону и статусу.

### Как работает изоляция тикетов (`TicketService` и `TicketRepository`)
1. При создании тикета (`createTicket`): в тикет автоматически прописывается регион автора: `ticket.setRegion(currentUser.getRegion())`.
2. В списке обращений (`listTickets`):
   - **`ADMIN`** — видит все регионы, может передавать фильтр `?regionId=01&status=OPEN`.
   - **`SUPPORT`** — жёстко ограничен своим регионом (`currentUser.getRegion().getId()`). В БД летят методы `ticketRepository.findByRegionIdAndStatus(...)` или `findByRegionId(...)`. Чужой `regionId` из query-параметра саппорт подставить не может.
   - **`INVESTIGATOR`** — видит только свои тикеты (`findByAuthorIdAndStatus` / `findByAuthorId`).
3. При открытии карточки (`getTicketDetails`), скачивании файла (`downloadAttachment`), взятии в работу (`assignTicket`), ответе (`addReply`) и закрытии (`closeTicket`) вызывается **`checkSupportRegionAccess(currentUser, ticket)`**:
   - Если `ticket.getRegion().getId()` не совпадает с `currentUser.getRegion().getId()`, сразу летит `ApiException(ErrorCode.ACCESS_DENIED, ...)` -> **HTTP `403`**.

### Зачем в `TicketRepository` везде стоит `@EntityGraph`
- В `application.yml` отключён антипаттерн Open Session in View: **`spring.jpa.open-in-view: false`**.
- Это значит, что Hibernate-сессия закрывается ровно тогда, когда заканчивается `@Transactional` метод сервиса (а в `TicketController` или при маппинге ленивых полей вне транзакции её уже нет), плюс обычные запросы без fetch дали бы проблему **N+1** (отдельный `SELECT` в `regions` и `users` для каждого тикета на странице).
- Поэтому на методах `TicketRepository` (`findAll`, `findByRegionId`, `findByRegionIdAndStatus`, `findWithDetailsById` и др.) добавлено:
  ```java
  @EntityGraph(attributePaths = {"author", "assignee", "region"})
  ```
  Hibernate делает один `LEFT JOIN` и сразу вытаскивает тикет вместе с автором, исполнителем и названием региона. (Такой же `@EntityGraph` используется в `UserRepository` для `region`, `department`, `position`).

---

## 7. Восстановление пароля (Redis + RabbitMQ)

Реализовано в `PasswordResetController` (`/auth/password-reset/**`) и `PasswordResetService`. Логика защиты полностью повторяет регистрацию, но ключи в Redis привязаны к **12-значному ИИН**, так как пользователь при входе забыл пароль именно от своего ИИН:

- Ключи Redis: `pwd:reset:<iin>` (TTL 10 мин, объект `PasswordResetDraft`), `pwd:cooldown:<iin>` (TTL 60 сек), `pwd:ban:<iin>` (TTL 1 час). Лимиты те же: 5 неверных вводов кода (`verifyAttempts`) и 3 переотправки (`resendCount`).
- **Как маскируется Email (`maskEmail` в `PasswordResetService`):**
  Чтобы злоумышленник не мог узнать полную почту сотрудника, просто перебирая ИИН на `/init`, метод `maskEmail` оставляет **только первый символ** до `@` и домен:
  `trimmed.charAt(0) + "***" + trimmed.substring(atIndex)` (например, `konti@gmail.com` -> `k***@gmail.com`).
- **Снятие 1-часовой блокировки при смене пароля (`confirmPasswordReset`):**
  1. Проверяет OTP-код, совпадение `confirmPassword`, сложность нового пароля (`passwordValidator`) и запрещает ставить тот же пароль (`passwordEncoder.matches` -> `400 SAME_PASSWORD`).
  2. Обновляет `passwordHash`, **обнуляет `failedLoginAttempts = 0` и `lockedUntil = null`** и сохраняет пользователя. Если пользователь забыл пароль, 3 раза ошибся и получил бан на 1 час — после сброса пароля он может войти мгновенно, не дожидаясь окончания часа.
  3. Удаляет ключи в Redis (`clearDraftAndCooldown`) и через RabbitMQ отправляет письмо-уведомление (`email/password-changed`).

---

## 8. Топ-10 вопросов на защите и готовые короткие ответы

1. **«Зачем вы храните черновик регистрации в Redis, а не сразу в таблице `users` со статусом `UNVERIFIED`?»**
   > Чтобы не засорять PostgreSQL неподтверждёнными анкетами и не блокировать реальные ИИН/Email, если кто-то бросит регистрацию на шаге OTP. В `RegistrationService.saveDraft` черновик автоматически живёт 10 минут по TTL и исчезает сам, а в `users` попадает только после `verifyOtp`.

2. **«Что будет, если во время регистрации другой человек успеет зарегистрировать тот же ИИН за эти 10 минут?»**
   > В `RegistrationService.verifyOtp` перед созданием сущности `User` повторно вызывается `checkUserUniqueness(draft.iin(), draft.email())` (плюс в БД стоят `UNIQUE`-ограничения в `V3`). Второй пользователь получит `409 Conflict` (`IIN_ALREADY_EXISTS`).

3. **«Как вы валидируете ИИН? Просто регуляркой на 12 цифр?»**
   > Нет, в `IinValidator.getValidationError` проверяется не только формат `^\d{12}$`, но и реальность даты рождения в первых 6 цифрах (`LocalDate.of`), 7-я цифра века и пола (`1..6`), а также математическая 12-я контрольная цифра по двум весовым массивам (`calculateControlDigit`).

4. **«Что будет с приложением, если SMTP-сервер (Mailpit) упадёт во время регистрации?»**
   > Сама регистрация (`initRegistration`) отработает штатно и быстро, потому что `EmailNotificationProducer.publish` просто положит JSON-событие в RabbitMQ. Ошибка произойдёт только на стороне `EmailNotificationConsumer.handleEmailMessage`, при этом само сообщение останется в durable-очереди `tracedesk.email.queue`.

5. **«Почему при неверных паролях аккаунт блокируется прогрессивно (5 мин -> 1 час), а фоновой задачи для разблокировки у вас нет?»**
   > В `LoginAttemptService.onAuthenticationFailure` мы не сбрасываем `failedLoginAttempts` при бане: на 3-й ошибке ставим `lockedUntil` на 5 минут, далее каждые 2 ошибки после истечения срока (5, 7, 9...) — на 1 час. В `AppUserPrincipal.isAccountNonLocked()` просто проверяется `!user.getLockedUntil().isAfter(OffsetDateTime.now())`, поэтому блокировка снимается лениво по времени без `@Scheduled`-воркеров, а `onAuthenticationSuccess` обнуляет счётчик в БД.

6. **«В `AppUserPrincipal.isEnabled()` возвращается `false` для всех статусов кроме `ACTIVE`. Как клиент понимает, что его заявка ещё на проверке, а не заблокирована?»**
   > Spring Security действительно кидает одно и то же исключение `DisabledException`. Но в `SecurityConfig.securityFilterChain` внутри `authEntryPoint` мы вызываем `resolveUserFromBasicAuth`, достаём пользователя по ИИН из `UserRepository` и смотрим его статус: для `PENDING_APPROVAL` отдаём `403 ACCOUNT_PENDING_APPROVAL`, а для `BLOCKED`/`REJECTED` — `403 ACCOUNT_BLOCKED`.

7. **«Что мешает админу случайно заблокировать себя или снять с себя роль `ADMIN`?»**
   > В `AdminUserService` в методах `blockUser`, `rejectRegistration` и `updateUserProfile` проверяется совпадение ID цели и текущего админа (`SELF_ACTION_FORBIDDEN`), а также вызывается `ensureNotLastActiveAdmin`, который через `userRepository.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE) <= 1` запрещает трогать последнего активного администратора (`LAST_ADMIN_PROTECTION`).

8. **«Почему в `AdminUserService.approveRegistration` вы используете `publishAfterCommit`, а не вызываете `emailNotificationProducer` напрямую в конце метода?»**
   > Если вызвать `rabbitTemplate.convertAndSend` до коммита, а транзакция в PostgreSQL при коммите упадёт (например, из-за сбоя БД), пользователь получит письмо «Ваша заявка одобрена», хотя в базе он всё ещё `PENDING_APPROVAL`. Метод `publishAfterCommit` через `TransactionSynchronizationManager` отправляет событие в RabbitMQ строго после успешного коммита.

9. **«Как саппорт из Астаны (`01`) гарантированно не увидит и не закроет тикет из Алматы (`02`)?»**
   > В `TicketService.listTickets` для роли `SUPPORT` выборка идёт строго через `ticketRepository.findByRegionId(supportRegionId, ...)`, игнорируя любые параметры. А в методах `getTicketDetails`, `assignTicket`, `addReply`, `closeTicket` и `downloadAttachment` стоит проверка `checkSupportRegionAccess`, которая кидает `403 ACCESS_DENIED` при несовпадении `region.getId()`.

10. **«Что произойдёт, если пользователь забыл пароль после того, как получил 1-часовой бан за 3 ошибки входа?»**
    > В `PasswordResetService.confirmPasswordReset` после проверки 6-значного OTP из Redis мы не только обновляем `passwordHash`, но и явно сбрасываем `user.setFailedLoginAttempts(0)` и `user.setLockedUntil(null)`. Пользователь сразу сможет войти с новым паролем.
