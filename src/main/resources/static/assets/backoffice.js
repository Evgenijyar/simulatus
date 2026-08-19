const state = {
    csrf: '', users: [], roles: [], credentials: [], sessions: [], system: null,
    dashboard: null, audit: [], view: 'overview', selectedUserId: null, selectedUser: null
};

const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const attr = value => esc(value).replace(/`/g, '&#96;');
const fmtDate = value => value ? new Intl.DateTimeFormat('ru-RU',{dateStyle:'short',timeStyle:'medium'}).format(new Date(value)) : '—';
const fmtAgo = value => {
    if (!value) return '—';
    const sec = Math.max(0, Math.round((Date.now()-new Date(value).getTime())/1000));
    if (sec < 60) return `${sec} сек назад`;
    if (sec < 3600) return `${Math.floor(sec/60)} мин назад`;
    if (sec < 86400) return `${Math.floor(sec/3600)} ч назад`;
    return `${Math.floor(sec/86400)} дн назад`;
};

let pending = 0;
document.addEventListener('DOMContentLoaded', init);

async function init() {
    bindShell();
    try {
        const session = await rawApi('/api/admin/auth/session');
        state.csrf = session.csrf || '';
        await reloadAll();
        setStatus('Готово');
    } catch (error) {
        if (error.status === 401 || error.status === 403) location.replace('/login.html');
        else renderFatal(error.message);
    }
}

function bindShell() {
    $('logout').onclick = async () => {
        try { await api('/api/admin/auth/logout',{method:'POST'}); } catch (_) {}
        location.replace('/login.html');
    };

    $('btn-add-user').onclick = () => openUserModal();

    $('user-list').onclick = event => {
        const button = event.target.closest('[data-user-id]');
        if (!button) return;
        state.selectedUserId = Number(button.dataset.userId);
        state.view = 'user';
        void loadSelectedUser();
    };

    $('system-nav').onclick = event => {
        const button = event.target.closest('[data-view]');
        if (!button) return;
        state.view = button.dataset.view;
        state.selectedUserId = null;
        state.selectedUser = null;
        syncNav();
        renderWorkspace();
    };

    $('workspace').addEventListener('click', workspaceClick);
    $('workspace').addEventListener('submit', workspaceSubmit);
    $('modal-root').addEventListener('click', modalClick);
    $('modal-root').addEventListener('submit', modalSubmit);
}

async function reloadAll() {
    const [dashboard, users, roles, credentials, sessions, system, audit] = await Promise.all([
        api('/api/admin/dashboard'), api('/api/admin/users'), api('/api/admin/roles'),
        api('/api/admin/credentials'), api('/api/admin/sessions'), api('/api/admin/system'), api('/api/admin/audit')
    ]);
    Object.assign(state,{dashboard,users,roles,credentials,sessions,system,audit});
    renderUserList();
    syncNav();
    renderWorkspace();
}

async function refreshCore() {
    const [dashboard, users, credentials, sessions] = await Promise.all([
        api('/api/admin/dashboard'), api('/api/admin/users'), api('/api/admin/credentials'), api('/api/admin/sessions')
    ]);
    Object.assign(state,{dashboard,users,credentials,sessions});
    renderUserList();
}

async function loadSelectedUser() {
    if (!state.selectedUserId) return;
    try {
        setStatus('Загрузка…');
        state.selectedUser = await api(`/api/admin/users/${state.selectedUserId}`);
        syncNav();
        renderUserList();
        renderWorkspace();
    } catch (error) {
        toast(error.message,'error');
    } finally {
        setStatus('Готово');
    }
}

async function rawApi(url, options={}) {
    pending++;
    setStatus('Работаю…');
    try {
        const response = await fetch(url,{...options,cache:'no-store',credentials:'same-origin'});
        const text = await response.text();
        let data = {};
        if (text) {
            try { data = JSON.parse(text); }
            catch (_) { data = {message:text}; }
        }
        if (!response.ok) {
            const error = new Error(data.message || `HTTP ${response.status}`);
            error.status = response.status;
            error.code = data.code;
            throw error;
        }
        return data;
    } finally {
        pending = Math.max(0,pending-1);
        if (!pending) setStatus('Готово');
    }
}

async function api(url, options={}) {
    const method = String(options.method || 'GET').toUpperCase();
    const headers = {...(options.headers || {})};
    if (!(options.body instanceof FormData) && !['GET','HEAD'].includes(method)) {
        headers['Content-Type'] = headers['Content-Type'] || 'application/json';
    }
    if (!['GET','HEAD','OPTIONS'].includes(method) && state.csrf) headers['X-Backoffice-CSRF'] = state.csrf;
    try {
        return await rawApi(url,{...options,method,headers});
    } catch (error) {
        if (error.status === 401 || error.status === 403) location.replace('/login.html');
        throw error;
    }
}

function setStatus(text) {
    const el = $('global-status');
    if (el) el.textContent = text;
}

function renderFatal(message) {
    $('workspace').innerHTML = `<div class="empty-state"><div class="eyebrow">SIMULATUS</div><h2>Не удалось загрузить back-office</h2><p>${esc(message)}</p><button class="btn" onclick="location.reload()">Повторить</button></div>`;
}

function renderUserList() {
    const root = $('user-list');
    if (!state.users.length) {
        root.innerHTML = '<div class="history-empty">Пользователей пока нет.</div>';
        return;
    }
    root.innerHTML = state.users.map(user => `
        <button class="tenant-nav ${state.selectedUserId===user.id?'is-active':''}" type="button" data-user-id="${user.id}">
            <div class="prodamus-user-nav-line"><b>${esc(user.firstName)} ${esc(user.lastName)}</b><span class="mini-dot ${user.enabled?'ok':'off'}"></span></div>
            <small>${esc(user.company)} · @${esc(user.login)}</small>
            <span class="nav-meta"><small>${user.roleCount} рол.</small><small>${user.activeSessions?`● ${user.activeSessions} актив.`:'нет сессий'}</small></span>
        </button>`).join('');
}

function syncNav() {
    document.querySelectorAll('.system-nav-btn').forEach(button =>
        button.classList.toggle('is-active',state.selectedUserId==null && button.dataset.view===state.view));
}

function renderWorkspace() {
    if (state.view === 'user' && state.selectedUser) return renderUser();
    const renderers = {
        overview: renderOverview,
        roles: renderRoles,
        credentials: renderCredentials,
        sessions: renderSessions,
        system: renderSystem,
        audit: renderAudit
    };
    (renderers[state.view] || renderOverview)();
}

function heading(eyebrow,title,meta='',actions='') {
    return `<div class="workspace-heading"><div><div class="eyebrow">${esc(eyebrow)}</div><h1 class="workspace-title">${esc(title)}</h1>${meta?`<div class="workspace-meta">${esc(meta)}</div>`:''}</div><div class="workspace-actions">${actions}</div></div>`;
}

function renderOverview() {
    const d = state.dashboard || {};
    $('workspace').innerHTML = `
      ${heading('SIMULATUS CONTROL CENTER','Обзор системы','Централизованное управление тренажёром продаж')}
      <div class="metrics-grid">
        ${metric('Пользователи',d.enabledUsers||0,'активных учётных записей')}
        ${metric('Роли',d.enabledRoles||0,'доступных тренировочных сценариев')}
        ${metric('Gemini-ключи',d.enabledCredentials||0,`суммарная ёмкость: ${d.totalCapacity||0}`)}
        ${metric('Live сейчас',d.activeSessions||0,'активных / резервируемых тренировок')}
      </div>
      <div class="settings-grid overview-grid">
        <article class="settings-card">
          <div class="settings-card-head"><div><div class="eyebrow">ГОТОВНОСТЬ</div><h3>Контур тренажёра</h3><p>Все основные компоненты Simulatus управляются из этого back-office.</p></div></div>
          <div class="prodamus-check-list">
            ${check('Авторизация менеджеров','Логин, пароль, имя, фамилия и компания хранятся централизованно.')}
            ${check('Роли и два промпта','Для каждой роли отдельно задаются голосовой сценарий клиента и финальная оценка.')}
            ${check('Пул Gemini-ключей','Постоянные API keys шифруются; нативный клиент получает только ephemeral token.')}
            ${check('История тренировок','Результат, оценка, транскрипт и JSON анализа сохраняются на сервере.')}
          </div>
        </article>
        <article class="settings-card">
          <div class="settings-card-head"><div><div class="eyebrow">СОСТОЯНИЕ</div><h3>Подготовка к тренировке</h3></div></div>
          <div class="status-stack">
            ${statusLine(state.users.some(u=>u.enabled),'Пользователь','Добавьте активную учётную запись менеджера')}
            ${statusLine(state.roles.some(r=>r.enabled),'Роль','Создайте и активируйте тренировочный сценарий')}
            ${statusLine(state.credentials.some(c=>c.enabled && c.healthStatus==='OK'),'Gemini','Добавьте API key и выполните проверку')}
          </div>
          <div class="setup-note mt"><b>Архитектура Live-сессии</b><ol><li>Backend резервирует ключ и выдаёт ephemeral token.</li><li>Windows-клиент подключается напрямую к Gemini Live.</li><li>После завершения backend сохраняет транскрипт и запускает отдельную оценку.</li></ol></div>
        </article>
      </div>`;
}

function metric(label,value,hint) {
    return `<article class="metric-card"><div class="metric-label">${esc(label)}</div><div class="metric-value">${esc(value)}</div><div class="metric-hint">${esc(hint)}</div></article>`;
}

function check(title,text) {
    return `<div class="check-row"><span>✓</span><div><b>${esc(title)}</b><small>${esc(text)}</small></div></div>`;
}

function statusLine(ok,title,text) {
    return `<div class="status-line"><span class="status-light ${ok?'ok':'warn'}"></span><div><b>${esc(title)}</b><small>${esc(ok?'Готово':text)}</small></div></div>`;
}

function renderUser() {
    const u = state.selectedUser;
    const roles = state.roles;
    $('workspace').innerHTML = `
      ${heading('ПОЛЬЗОВАТЕЛЬ',`${u.firstName} ${u.lastName}`,`${u.company} · @${u.login} · создан ${fmtDate(u.createdAt)}`,`<span class="status-pill ${u.enabled?'active':'disabled'}">${u.enabled?'ACTIVE':'DISABLED'}</span>`)}
      <form class="settings-grid" data-user-form>
        <article class="settings-card">
          <div class="settings-card-head"><div><div class="eyebrow">УЧЁТНАЯ ЗАПИСЬ</div><h3>Профиль менеджера</h3><p>Эти данные отображаются в Windows-приложении и используются для входа.</p></div></div>
          <div class="two-col-form">
            <label>Имя<input class="custom-input" name="firstName" value="${attr(u.firstName)}" required></label>
            <label>Фамилия<input class="custom-input" name="lastName" value="${attr(u.lastName)}" required></label>
          </div>
          <div class="two-col-form">
            <label>Компания<input class="custom-input" name="company" value="${attr(u.company)}" required></label>
            <label>Логин<input class="custom-input" name="login" value="${attr(u.login)}" required></label>
          </div>
          <label>Email<input class="custom-input" name="email" type="email" value="${attr(u.email||'')}"></label>
          <label>Новый пароль<input class="custom-input" name="password" type="password" autocomplete="new-password" placeholder="Оставьте пустым, чтобы не менять"><small>При смене пароля текущие сохранённые сессии входа могут быть отозваны.</small></label>
          <label class="toggle-card"><input name="enabled" type="checkbox" ${u.enabled?'checked':''}><span><b>Доступ разрешён</b><small>Если выключить, пользователь не сможет начать новую тренировку.</small></span></label>
        </article>
        <article class="settings-card">
          <div class="settings-card-head"><div><div class="eyebrow">РОЛИ</div><h3>Доступные сценарии</h3><p>В Windows-клиенте менеджер увидит только отмеченные роли.</p></div></div>
          <div class="role-checkbox-list">${roles.map(r=>roleCheckbox(r,u.roleIds.includes(r.id))).join('')||'<div class="history-empty">Сначала создайте хотя бы одну роль.</div>'}</div>
        </article>
        <article class="settings-card full-card">
          <div class="settings-actions"><button class="btn btn-save prodamus-primary" type="submit">Сохранить пользователя</button><button class="btn btn-danger" type="button" data-disable-user="${u.id}" ${!u.enabled?'disabled':''}>Отключить доступ</button></div>
        </article>
      </form>
      <article class="settings-card mt">
        <div class="settings-card-head"><div><div class="eyebrow">УСТРОЙСТВА</div><h3>Сохранённые входы</h3><p>Refresh-сессии Windows-клиента. Можно отозвать отдельный компьютер без смены пароля.</p></div></div></div>
        <div class="device-list">${(u.devices||[]).map(d=>`<div class="device-row"><div class="device-icon">▣</div><div><b>${esc(d.deviceName||'Windows устройство')}</b><small class="mono">${esc(d.deviceId)}</small><small>${d.persistent?'Запомнить меня · ':''}действует до ${fmtDate(d.expiresAt)}</small></div><button class="btn btn-sm btn-danger" type="button" data-revoke-device="${attr(d.deviceId)}">Отозвать</button></div>`).join('')||'<div class="history-empty">Сохранённых устройств пока нет.</div>'}</div>
      </article>
      <article class="settings-card mt">
        <div class="settings-card-head"><div><div class="eyebrow">ИСТОРИЯ</div><h3>Последние тренировки</h3><p>Результаты этого менеджера: статус, роль, оценка и итог.</p></div></div></div>
        ${sessionTable(u.recentSessions,false)}
      </article>`;
}

function roleCheckbox(r,checked) {
    return `<label class="role-check ${!r.enabled?'is-disabled':''}"><input type="checkbox" name="roleIds" value="${r.id}" ${checked?'checked':''} ${!r.enabled?'disabled':''}><span><b>${esc(r.name)}</b><small>${esc(r.description||r.liveModel)}</small></span><em>${r.enabled?'ACTIVE':'OFF'}</em></label>`;
}

function renderRoles() {
    $('workspace').innerHTML = `${heading('РОЛИ И ПРОМПТЫ','Тренировочные сценарии','У каждой роли отдельно задаются поведение голосового клиента и правила финальной оценки',`<button class="btn btn-save prodamus-primary" data-add-role>＋ Добавить роль</button>`)}
    <div class="prodamus-card-list">${state.roles.map(r=>`<article class="settings-card prompt-card">
      <div class="settings-card-head"><div><div class="eyebrow">${esc(r.liveModel)}</div><h3>${esc(r.name)}</h3><p>${esc(r.description||'Без описания')}</p></div><span class="status-pill ${r.enabled?'active':'disabled'}">${r.enabled?'ACTIVE':'OFF'}</span></div>
      <div class="modal-section-title">Сценарий клиента</div>
      <div class="prompt-preview">${esc(short(r.livePrompt,520)||'Сценарий пока пуст.')}</div>
      <div class="modal-section-title">Промпт оценки</div>
      <div class="prompt-preview">${esc(short(r.evaluationPrompt,520)||'Промпт оценки пока пуст.')}</div>
      <div class="card-footer-meta"><span>Live: ${esc(r.liveModel)}</span><span>Eval: ${esc(r.evaluationModel)}</span><span>Версия: v${r.version}</span><span>Порядок: ${r.sortOrder}</span><span>Изменён: ${fmtAgo(r.updatedAt)}</span></div>
      <div class="settings-actions"><button class="btn btn-sm" data-edit-role="${r.id}">Редактировать</button>${r.enabled?`<button class="btn btn-sm btn-danger" data-disable-role="${r.id}">Отключить</button>`:''}</div>
    </article>`).join('')||emptyCard('Ролей пока нет','Добавьте первый тренировочный сценарий.')}</div>`;
}

function renderCredentials() {
    $('workspace').innerHTML = `${heading('GEMINI CREDENTIALS','Пул Gemini-ключей','Постоянные API keys зашифрованы в PostgreSQL и никогда не выдаются Windows-клиенту',`<button class="btn btn-save prodamus-primary" data-add-credential>＋ Добавить ключ</button>`)}
    <div class="prodamus-card-list">${state.credentials.map(c=>`<article class="settings-card credential-card">
      <div class="settings-card-head"><div><div class="eyebrow">${esc(c.provider)}</div><h3>${esc(c.name)}</h3><p class="mono">${esc(c.keyHint||'—')}</p></div>${credentialHealth(c)}</div>
      <div class="credential-capacity"><div><span>Активно</span><b>${c.activeSessions} / ${c.maxConcurrentSessions}</b></div><div class="capacity-track"><span style="width:${Math.min(100,c.maxConcurrentSessions?c.activeSessions*100/c.maxConcurrentSessions:0)}%"></span></div></div>
      ${c.lastError?`<div class="credential-error">${esc(short(c.lastError,900))}</div>`:''}
      <div class="card-footer-meta"><span>${c.enabled?'Разрешён':'Отключён'}</span><span>Проверка: ${c.lastCheckedAt?fmtAgo(c.lastCheckedAt):'не выполнялась'}</span></div>
      <div class="settings-actions"><button class="btn btn-sm" data-test-credential="${c.id}">Проверить</button><button class="btn btn-sm" data-edit-credential="${c.id}">Настройки</button>${c.enabled?`<button class="btn btn-sm btn-danger" data-disable-credential="${c.id}">Отключить</button>`:''}</div>
    </article>`).join('')||emptyCard('Gemini-ключи не добавлены','Добавьте Gemini API key и выполните проверку подключения.')}</div>`;
}

function credentialHealth(c) {
    const cls = c.healthStatus==='OK'?'active':c.healthStatus==='ERROR'?'disabled':'pending';
    return `<span class="status-pill ${cls}">${esc(c.healthStatus||'UNKNOWN')}</span>`;
}

function renderSessions() {
    const active = state.sessions.filter(s=>['ACTIVE','PROVISIONING','EVALUATING'].includes(s.status)).length;
    $('workspace').innerHTML = `${heading('TRAINING HISTORY','Тренировки',`${active} активно сейчас`,`<button class="btn" data-refresh>Обновить</button>`)}
    <article class="settings-card"><div class="settings-card-head"><div><div class="eyebrow">ИСТОРИЯ</div><h3>Последние 200 тренировок</h3><p>Здесь сохраняются технический статус, результат, оценка и разбор разговора.</p></div></div>${sessionTable(state.sessions,true)}</article>`;
}

function sessionTable(items,adminActions=true) {
    if (!items || !items.length) return '<div class="history-empty table-empty">Тренировок пока нет.</div>';
    return `<div class="data-table-wrap"><table class="data-table"><thead><tr><th>Статус</th><th>Менеджер</th><th>Роль</th><th>Оценка</th><th>Старт</th><th>Итог</th>${adminActions?'<th></th>':''}</tr></thead><tbody>${items.map(s=>`<tr>
      <td>${sessionStatus(s.status)}</td>
      <td><b>${esc(s.userName||'—')}</b><br><span class="muted-cell">${esc(s.company||'')}</span></td>
      <td>${esc(s.roleName||'—')}</td>
      <td>${scoreBadge(s.score)}</td>
      <td title="${esc(fmtDate(s.startedAt))}">${esc(fmtAgo(s.startedAt))}</td>
      <td class="muted-cell">${esc(short(s.evaluationSummary||s.closeReason||'—',110))}</td>
      ${adminActions?`<td class="right-cell"><button class="btn btn-sm" data-open-session="${s.id}">Открыть</button>${['ACTIVE','PROVISIONING','EVALUATING'].includes(s.status)?` <button class="btn btn-sm btn-danger" data-terminate-session="${s.id}">Стоп</button>`:''}</td>`:''}
    </tr>`).join('')}</tbody></table></div>`;
}

function sessionStatus(status) {
    const active = ['ACTIVE','COMPLETED'].includes(status);
    const pending = ['PROVISIONING','EVALUATING'].includes(status);
    const bad = ['FAILED','EXPIRED','ABORTED','TERMINATED','EVALUATION_FAILED'].includes(status);
    return `<span class="status-pill ${active?'active':pending?'pending':bad?'disabled':''}">${esc(status||'—')}</span>`;
}

function scoreBadge(score) {
    if (score == null) return '<span class="muted-cell">—</span>';
    const cls = score >= 80 ? 'active' : score >= 60 ? 'pending' : 'disabled';
    return `<span class="status-pill ${cls}">${score}/100</span>`;
}

function renderSystem() {
    const c = state.system || {};
    $('workspace').innerHTML = `${heading('SYSTEM CONFIG','Система','Центральные параметры, которые Windows-клиент получает при bootstrap')}
    <form class="settings-grid" data-system-form>
      <article class="settings-card"><div class="settings-card-head"><div><div class="eyebrow">CLIENT VERSION</div><h3>Версии Windows-приложения</h3></div></div><label>Минимально допустимая версия<input class="custom-input" name="minimumClientVersion" value="${attr(c.minimumClientVersion||'0.1.0')}" required></label><label>Актуальная версия<input class="custom-input" name="latestClientVersion" value="${attr(c.latestClientVersion||'0.1.0')}" required></label><label>Ссылка на установщик / обновление<input class="custom-input" name="clientDownloadUrl" type="url" value="${attr(c.clientDownloadUrl||'')}" placeholder="https://.../SimulatusSetup.exe"><small>Windows-клиент сможет использовать эту ссылку для обновления.</small></label></article>
      <article class="settings-card"><div class="settings-card-head"><div><div class="eyebrow">GEMINI LIVE</div><h3>Live-модель по умолчанию</h3><p>Эта модель используется при проверке Gemini-ключа и как значение по умолчанию для ролей.</p></div></div><label>Model ID<input class="custom-input mono" name="defaultModel" value="${attr(c.defaultModel||'gemini-3.1-flash-live-preview')}" required><small>Для голосового режима Simulatus используется Gemini Live.</small></label></article>
      <article class="settings-card full-card"><div class="settings-actions"><button class="btn btn-save prodamus-primary" type="submit">Сохранить систему</button></div></article>
    </form>`;
}

function renderAudit() {
    $('workspace').innerHTML = `${heading('AUDIT','Журнал действий','Последние 100 административных и клиентских событий',`<button class="btn" data-refresh>Обновить</button>`)}
    <article class="settings-card"><div class="data-table-wrap"><table class="data-table"><thead><tr><th>Время</th><th>Событие</th><th>Кто</th><th>Объект</th><th>Детали</th></tr></thead><tbody>${state.audit.map(a=>`<tr><td>${esc(fmtDate(a.createdAt))}</td><td><b>${esc(a.eventType)}</b></td><td>${esc(a.actor||'—')}</td><td>${esc(a.subject||'—')}</td><td class="muted-cell">${esc(short(a.detail,160)||'—')}</td></tr>`).join('')||'<tr><td colspan="5" class="table-empty">Журнал пуст.</td></tr>'}</tbody></table></div></article>`;
}

async function workspaceClick(event) {
    try {
        if (event.target.closest('[data-add-role]')) return openRoleModal();

        const editRole = event.target.closest('[data-edit-role]');
        if (editRole) return openRoleModal(Number(editRole.dataset.editRole));

        const disableRole = event.target.closest('[data-disable-role]');
        if (disableRole && confirm('Отключить эту роль? Уже идущие тренировки не прерываются.')) {
            await api(`/api/admin/roles/${disableRole.dataset.disableRole}`,{method:'DELETE'});
            state.roles = await api('/api/admin/roles');
            await refreshCore();
            toast('Роль отключена');
            renderWorkspace();
            return;
        }

        if (event.target.closest('[data-add-credential]')) return openCredentialModal();

        const editCredential = event.target.closest('[data-edit-credential]');
        if (editCredential) return openCredentialModal(Number(editCredential.dataset.editCredential));

        const testCredential = event.target.closest('[data-test-credential]');
        if (testCredential) {
            const button = testCredential;
            button.disabled = true;
            button.textContent = 'Проверяем…';
            try {
                const result = await api(`/api/admin/credentials/${button.dataset.testCredential}/test`,{method:'POST'});
                toast(result.message);
            } catch (error) {
                toast(error.message,'error');
            } finally {
                state.credentials = await api('/api/admin/credentials');
                await refreshCore();
                renderWorkspace();
            }
            return;
        }

        const disableCredential = event.target.closest('[data-disable-credential]');
        if (disableCredential && confirm('Отключить этот Gemini-ключ? Новые тренировки на него назначаться не будут.')) {
            await api(`/api/admin/credentials/${disableCredential.dataset.disableCredential}`,{method:'DELETE'});
            state.credentials = await api('/api/admin/credentials');
            await refreshCore();
            renderWorkspace();
            toast('Gemini-ключ отключён');
            return;
        }

        const terminate = event.target.closest('[data-terminate-session]');
        if (terminate && confirm('Принудительно завершить эту тренировку?')) {
            await api(`/api/admin/sessions/${terminate.dataset.terminateSession}/terminate`,{method:'POST'});
            await reloadAll();
            toast('Тренировка остановлена');
            return;
        }

        const openSession = event.target.closest('[data-open-session]');
        if (openSession) {
            const session = state.sessions.find(s=>s.id===openSession.dataset.openSession);
            if (session) openSessionModal(session);
            return;
        }

        const disableUser = event.target.closest('[data-disable-user]');
        if (disableUser && confirm('Отключить пользователя и его активные тренировки?')) {
            await api(`/api/admin/users/${disableUser.dataset.disableUser}`,{method:'DELETE'});
            await refreshCore();
            await loadSelectedUser();
            toast('Пользователь отключён');
            return;
        }

        const revokeDevice = event.target.closest('[data-revoke-device]');
        if (revokeDevice && confirm('Отозвать вход на этом устройстве?')) {
            await api(`/api/admin/users/${state.selectedUserId}/devices/revoke`,{method:'POST',body:JSON.stringify({deviceId:revokeDevice.dataset.revokeDevice})});
            await loadSelectedUser();
            toast('Устройство отозвано');
            return;
        }

        if (event.target.closest('[data-refresh]')) {
            await reloadAll();
            toast('Данные обновлены');
        }
    } catch (error) {
        toast(error.message,'error');
    }
}

async function workspaceSubmit(event) {
    event.preventDefault();
    const form = event.target;
    try {
        if (form.matches('[data-user-form]')) {
            const body = {
                login: form.login.value.trim(),
                firstName: form.firstName.value.trim(),
                lastName: form.lastName.value.trim(),
                company: form.company.value.trim(),
                email: form.email.value.trim(),
                password: form.password.value,
                enabled: form.enabled.checked,
                roleIds: [...form.querySelectorAll('input[name="roleIds"]:checked')].map(x=>Number(x.value))
            };
            state.selectedUser = await api(`/api/admin/users/${state.selectedUserId}`,{method:'PUT',body:JSON.stringify(body)});
            await refreshCore();
            renderWorkspace();
            toast('Пользователь сохранён');
            return;
        }

        if (form.matches('[data-system-form]')) {
            const body = {
                minimumClientVersion: form.minimumClientVersion.value.trim(),
                latestClientVersion: form.latestClientVersion.value.trim(),
                clientDownloadUrl: form.clientDownloadUrl.value.trim(),
                defaultModel: form.defaultModel.value.trim()
            };
            state.system = await api('/api/admin/system',{method:'PUT',body:JSON.stringify(body)});
            renderWorkspace();
            toast('Системные настройки сохранены');
        }
    } catch (error) {
        toast(error.message,'error');
    }
}

function openUserModal() {
    const roleHtml = state.roles.filter(r=>r.enabled).map(r=>roleCheckbox(r,false)).join('') || '<div class="history-empty">Активных ролей пока нет.</div>';
    openModal('Новый пользователь','ДОСТУП WINDOWS-КЛИЕНТА',`<form class="modal-form" data-create-user-form>
      <div class="two-col-form"><label>Имя<input class="custom-input" name="firstName" required autofocus placeholder="Иван"></label><label>Фамилия<input class="custom-input" name="lastName" required placeholder="Петров"></label></div>
      <div class="two-col-form"><label>Компания<input class="custom-input" name="company" required placeholder="ООО Компания"></label><label>Логин<input class="custom-input" name="login" required autocomplete="off" placeholder="ivan"></label></div>
      <div class="two-col-form"><label>Email<input class="custom-input" name="email" type="email" placeholder="ivan@company.ru"></label><label>Пароль<input class="custom-input" name="password" type="password" minlength="6" required autocomplete="new-password"></label></div>
      <label class="toggle-card"><input name="enabled" type="checkbox" checked><span><b>Сразу разрешить вход</b><small>Пользователь сможет авторизоваться после сохранения.</small></span></label>
      <div class="modal-section-title">Доступные роли</div><div class="role-checkbox-list modal-role-list">${roleHtml}</div>
      <div id="modal-error" class="form-error d-none"></div><div class="modal-actions"><button class="btn" type="button" data-close-modal>Отмена</button><button class="btn btn-save prodamus-primary" type="submit">Добавить пользователя</button></div>
    </form>`,'wide');
}

function openRoleModal(id=null) {
    const r = id ? state.roles.find(x=>x.id===id) : null;
    const liveModel = r?.liveModel || state.system?.defaultModel || 'gemini-3.1-flash-live-preview';
    const evaluationModel = r?.evaluationModel || 'gemini-3.1-flash-lite';
    openModal(r?'Редактировать роль':'Новая роль','TRAINING ROLE',`<form class="modal-form" data-role-form data-id="${r?.id||''}">
      <div class="two-col-form"><label>Название<input class="custom-input" name="name" value="${attr(r?.name||'')}" required autofocus placeholder="Холодный звонок"></label><label>Порядок<input class="custom-input" name="sortOrder" type="number" value="${r?.sortOrder??100}"></label></div>
      <label>Описание<input class="custom-input" name="description" value="${attr(r?.description||'')}" placeholder="Короткое описание сценария"></label>
      <div class="two-col-form"><label>Live model<input class="custom-input mono" name="liveModel" value="${attr(liveModel)}" required></label><label>Evaluation model<input class="custom-input mono" name="evaluationModel" value="${attr(evaluationModel)}" required></label></div>
      <label>Сценарий / system prompt голосового клиента<textarea class="custom-input code-textarea" name="livePrompt" rows="9" required placeholder="Роль клиента, контекст компании, характер, возражения, цели…">${esc(r?.livePrompt||'')}</textarea><small>Этот prompt получает голосовая Live-модель во время тренировки.</small></label>
      <label>Промпт финальной оценки<textarea class="custom-input code-textarea" name="evaluationPrompt" rows="8" required placeholder="Критерии оценки менеджера, цели, обязательные действия…">${esc(r?.evaluationPrompt||'')}</textarea><small>Сервер требует JSON с score 0–100, summary, strengths и improvements.</small></label>
      <label class="toggle-card"><input name="enabled" type="checkbox" ${r?.enabled!==false?'checked':''}><span><b>Роль активна</b><small>Неактивная роль не отображается назначенным пользователям.</small></span></label>
      <div id="modal-error" class="form-error d-none"></div><div class="modal-actions"><button class="btn" type="button" data-close-modal>Отмена</button><button class="btn btn-save prodamus-primary" type="submit">Сохранить</button></div>
    </form>`,'wide');
}

function openCredentialModal(id=null) {
    const c = id ? state.credentials.find(x=>x.id===id) : null;
    openModal(c?'Настройки Gemini-ключа':'Новый Gemini-ключ','GEMINI CREDENTIAL',`<form class="modal-form" data-credential-form data-id="${c?.id||''}">
      <div class="two-col-form"><label>Название<input class="custom-input" name="name" value="${attr(c?.name||'')}" required autofocus placeholder="Gemini Key 01"></label><label>Лимит одновременных тренировок<input class="custom-input" name="maxConcurrentSessions" type="number" min="1" max="100" value="${c?.maxConcurrentSessions??1}" required></label></div>
      <label>Gemini API key<input class="custom-input mono" name="apiKey" type="password" autocomplete="off" ${c?'':'required'} placeholder="${c?'Оставьте пустым, чтобы не менять':'AIza…'}"><small>${c?`Текущий ключ: ${esc(c.keyHint)}. Новый ключ будет зашифрован до записи в PostgreSQL.`:'Постоянный ключ никогда не выдаётся Windows-клиенту.'}</small></label>
      <label class="toggle-card"><input name="enabled" type="checkbox" ${c?.enabled!==false?'checked':''}><span><b>Разрешить выдачу тренировок</b><small>Сервер будет учитывать этот ключ при распределении свободной ёмкости.</small></span></label>
      <div id="modal-error" class="form-error d-none"></div><div class="modal-actions"><button class="btn" type="button" data-close-modal>Отмена</button><button class="btn btn-save prodamus-primary" type="submit">Сохранить</button></div>
    </form>`);
}

function openSessionModal(s) {
    openModal('Тренировка','TRAINING RESULT',`<div class="modal-form">
      <div class="settings-card-head"><div><div class="eyebrow">${esc(s.status)}</div><h3>${esc(s.userName)} · ${esc(s.company)}</h3><p>${esc(s.roleName)} · ${fmtDate(s.startedAt)}</p></div>${scoreBadge(s.score)}</div>
      <div class="modal-section-title">Разбор</div><div class="prompt-preview">${esc(s.evaluationSummary||s.closeReason||'Разбор отсутствует')}</div>
      <div class="modal-section-title">Транскрипт</div><div class="prompt-preview" style="max-height:320px;overflow:auto">${esc(s.transcript||'Транскрипт отсутствует')}</div>
      <div class="modal-section-title">JSON оценки</div><div class="prompt-preview mono" style="max-height:260px;overflow:auto">${esc(s.evaluationJson||'—')}</div>
      <div class="card-footer-meta"><span>Gemini key: ${esc(s.credentialName||'—')}</span><span>Device: ${esc(s.deviceId||'—')}</span><span>Client: ${esc(s.clientVersion||'—')}</span><span>Финиш: ${fmtDate(s.closedAt)}</span><span>Источник: ${esc(s.completionSource||'—')}</span></div>
      <div class="modal-actions"><button class="btn" type="button" data-close-modal>Закрыть</button></div>
    </div>`,'wide');
}

function openModal(title,eyebrow,body,size='') {
    $('modal-root').innerHTML = `<div class="modal-backdrop-custom"><div class="modal-card modal-custom ${size==='wide'?'modal-card-wide':''}"><div class="modal-header-custom"><div><div class="modal-step-label">${esc(eyebrow)}</div><h2>${esc(title)}</h2></div><button class="btn-close-custom" type="button" data-close-modal>✕</button></div>${body}</div></div>`;
}

function closeModal() {
    $('modal-root').innerHTML = '';
}

function modalClick(event) {
    if (event.target.matches('.modal-backdrop-custom') || event.target.closest('[data-close-modal]')) closeModal();
}

async function modalSubmit(event) {
    event.preventDefault();
    const form = event.target;
    const error = $('modal-error');
    if (error) error.classList.add('d-none');
    const submit = form.querySelector('[type="submit"]');
    if (submit) submit.disabled = true;

    try {
        if (form.matches('[data-create-user-form]')) {
            const body = {
                login: form.login.value.trim(),
                firstName: form.firstName.value.trim(),
                lastName: form.lastName.value.trim(),
                company: form.company.value.trim(),
                email: form.email.value.trim(),
                password: form.password.value,
                enabled: form.enabled.checked,
                roleIds: [...form.querySelectorAll('input[name="roleIds"]:checked')].map(x=>Number(x.value))
            };
            const created = await api('/api/admin/users',{method:'POST',body:JSON.stringify(body)});
            closeModal();
            await refreshCore();
            state.selectedUserId = created.id;
            state.selectedUser = created;
            state.view = 'user';
            renderUserList();
            syncNav();
            renderWorkspace();
            toast('Пользователь добавлен');
            return;
        }

        if (form.matches('[data-role-form]')) {
            const id = form.dataset.id;
            const body = {
                name: form.name.value.trim(),
                description: form.description.value.trim(),
                livePrompt: form.livePrompt.value,
                evaluationPrompt: form.evaluationPrompt.value,
                liveModel: form.liveModel.value.trim(),
                evaluationModel: form.evaluationModel.value.trim(),
                enabled: form.enabled.checked,
                sortOrder: Number(form.sortOrder.value || 100)
            };
            await api(id?`/api/admin/roles/${id}`:'/api/admin/roles',{method:id?'PUT':'POST',body:JSON.stringify(body)});
            closeModal();
            state.roles = await api('/api/admin/roles');
            await refreshCore();
            renderWorkspace();
            toast('Роль сохранена');
            return;
        }

        if (form.matches('[data-credential-form]')) {
            const id = form.dataset.id;
            const body = {
                name: form.name.value.trim(),
                apiKey: form.apiKey.value,
                enabled: form.enabled.checked,
                maxConcurrentSessions: Number(form.maxConcurrentSessions.value || 1)
            };
            await api(id?`/api/admin/credentials/${id}`:'/api/admin/credentials',{method:id?'PUT':'POST',body:JSON.stringify(body)});
            closeModal();
            state.credentials = await api('/api/admin/credentials');
            await refreshCore();
            renderWorkspace();
            toast('Gemini-ключ сохранён');
        }
    } catch (ex) {
        if (error) {
            error.textContent = ex.message;
            error.classList.remove('d-none');
        } else {
            toast(ex.message,'error');
        }
        if (submit) submit.disabled = false;
    }
}

function emptyCard(title,text) {
    return `<article class="settings-card"><div class="empty-inline"><b>${esc(title)}</b><span>${esc(text)}</span></div></article>`;
}

function short(value,max=120) {
    const s = String(value ?? '').replace(/\s+/g,' ').trim();
    return s.length <= max ? s : s.slice(0,max-1)+'…';
}

function toast(message,type='ok') {
    const root = $('toast-root');
    const el = document.createElement('div');
    el.className = `toast ${type==='error'?'is-error':''}`;
    el.textContent = message;
    root.appendChild(el);
    requestAnimationFrame(()=>el.classList.add('is-visible'));
    setTimeout(()=>{
        el.classList.remove('is-visible');
        setTimeout(()=>el.remove(),250);
    },3200);
}
