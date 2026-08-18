# Simulatus Backend 0.1.0

Серверная часть нативного тренажёра менеджеров по продажам. Это самостоятельный Spring Boot / PostgreSQL back-office: он управляет пользователями и ролями, выдаёт клиенту краткоживущие Gemini Live credentials, сохраняет тренировки и после звонка выполняет отдельную LLM-оценку.

## Стек

- Java 21
- Spring Boot 4.1
- Spring MVC / RestClient
- Spring Data JPA / Hibernate
- PostgreSQL + Flyway
- статический HTML/CSS/JS back-office без Thymeleaf
- Docker multi-stage build
- `application.properties`, без YAML
- без Lombok

## Модель тренировки

У каждой роли два независимых промпта:

1. `system_prompt` — сценарий голосового клиента для Gemini Live.
2. `evaluation_prompt` — правила финальной оценки разговора.

Live-модель по умолчанию: `gemini-3.1-flash-live-preview`.
Модель оценки по умолчанию: `gemini-3.1-flash-lite`.

Во время Live-сессии доступен только один function tool: `finish_training`. Модель должна вызвать его только при естественном логическом завершении разговора. Причина/качество завершения оцениваются уже после звонка отдельным анализатором.

Финальная оценка сохраняется как:

- `score` — 0..100;
- `evaluation_summary` — текст разбора;
- `evaluation_json` — исходный structured JSON (`score`, `summary`, `strengths`, `improvements`);
- полный транскрипт менеджер/клиент.

## Пользователь

Для каждого пользователя обязательны:

- логин;
- пароль;
- имя;
- фамилия;
- компания.

В back-office администратор назначает набор доступных ролей. Windows-клиент получает только назначенные активные роли.

## База данных

По умолчанию используется тот же PostgreSQL host/port, что и в предоставленном референсном сервере, но отдельная БД:

```properties
spring.datasource.url=jdbc:postgresql://${SIMULATUS_DB_HOST:45.11.92.142}:${SIMULATUS_DB_PORT:5433}/${SIMULATUS_DB_NAME:simulatus}
spring.datasource.username=${SIMULATUS_DB_USER:simulatus_app}
```

Пароль в репозитории не хранится и передаётся только через `SIMULATUS_DB_PASSWORD`.

Для production используется отдельная БД `simulatus` и отдельный пользователь `simulatus_app`. Если окружение разворачивается с нуля, пример создания от имени PostgreSQL-администратора:

```sql
CREATE USER simulatus_app WITH PASSWORD 'CHANGE_ME';
CREATE DATABASE simulatus OWNER simulatus_app;
\c simulatus
GRANT ALL ON SCHEMA public TO simulatus_app;
```

Flyway создаст таблицы при первом старте приложения.

## Переменные production

Рекомендуемый файл на сервере: `/etc/simulatus/backend.env` с правами `0600`.

```dotenv
SIMULATUS_DB_HOST=45.11.92.142
SIMULATUS_DB_PORT=5433
SIMULATUS_DB_NAME=simulatus
SIMULATUS_DB_USER=simulatus_app
SIMULATUS_DB_PASSWORD=CHANGE_ME

SIMULATUS_ADMIN_LOGIN=admin
SIMULATUS_ADMIN_PASSWORD=CHANGE_ME_LONG_PASSWORD
SIMULATUS_MASTER_KEY=PASTE_BASE64_32_BYTE_KEY_HERE
SIMULATUS_SESSION_SECURE=true
```

`SIMULATUS_MASTER_KEY` должен быть Base64-строкой, которая декодируется ровно в 32 байта (например, результат `openssl rand -base64 32`). Он используется для AES-256-GCM шифрования постоянных Gemini API keys в PostgreSQL. Сами API keys добавляются через back-office.

## Локальный запуск

```bash
./mvnw spring-boot:run
```

На Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

Back-office: `http://localhost:8080/backoffice`.

## GitHub: три команды после изменений

```bash
git add .
git commit -m "Update Simulatus backend"
git push origin main
```

## Деплой на сервер

Клонировать репозиторий один раз, создать env-файл и затем из корня репозитория выполнять:

```bash
python3 deploy.py
```

`deploy.py` делает:

1. `git fetch` + hard reset на `origin/main`;
2. Docker build;
3. замену контейнера `simulatus-backend`;
4. health-check `/actuator/health`;
5. автоматический rollback предыдущего контейнера при неудачном запуске.

По умолчанию контейнер публикуется только на `127.0.0.1:8083`, чтобы наружу его отдавал Nginx/Reverse Proxy. Настройки можно переопределять env-переменными `SIMULATUS_HTTP_PORT`, `SIMULATUS_CONTAINER_NAME`, `SIMULATUS_IMAGE_NAME`, `SIMULATUS_ENV_FILE` и т.д.

## Основные API клиента

- `POST /api/client/auth/login`
- `POST /api/client/auth/refresh`
- `GET /api/client/bootstrap`
- `POST /api/client/training-sessions`
- `POST /api/client/training-sessions/{id}/heartbeat`
- `POST /api/client/training-sessions/{id}/finish`
- `POST /api/client/training-sessions/{id}/abandon`

Постоянный Gemini API key никогда не передаётся Windows-клиенту. Backend создаёт ephemeral token для Live API.

## Что нужно сделать перед production

- убедиться, что БД `simulatus` и пользователь `simulatus_app` созданы на PostgreSQL `45.11.92.142:5433`;
- задать production env;
- добавить хотя бы один Gemini API key;
- создать роли с двумя промптами;
- создать пользователей и назначить роли;
- выставить URL production backend в Windows-клиенте;
- настроить Nginx/TLS на домен backend.
