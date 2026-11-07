(function () {
  'use strict';

  var STORAGE_KEY = 'cleanAlarm.state.v2';
  var OLD_STORAGE_KEY = 'cleanAlarm.state.v1';
  var DAY_NAMES = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];
  var ALL_DAYS = 127;
  var WEEKDAYS = 31;   /* пн–пт */
  var WEEKEND = 96;    /* сб, вс */
  var MAX_ALARMS = 15;

  var DEFAULT_STATE = {
    soundUri: '',
    soundTitle: 'По умолчанию',
    customSounds: [],
    snoozeMinutes: 10,
    maxSnoozes: 0,
    fadeSeconds: 0,
    volume: 0,
    vibrate: true,
    autoStopMinutes: 10,
    challenge: false,
    challengeNumbers: 2,
    theme: 0,
    updateCheck: false,
    alarms: []   /* [{id, enabled, hour, minute, repeat, days, label, skipBefore}] */
  };

  var $ = function (id) { return document.getElementById(id); };
  var app = $('app');
  var card = $('card');
  var timeBtn = $('timeBtn');
  var gearBtn = $('gearBtn');
  var statusText = $('statusText');
  var nextText = $('nextText');
  var nowText = $('nowText');
  var metaMid = $('metaMid');
  var alarmList = $('alarmList');
  var emptyHint = $('emptyHint');
  var snoozeBtn = $('snoozeBtn');
  var snoozeSub = $('snoozeSub');
  var disableBtn = $('disableBtn');
  var createBtn = $('createBtn');
  var createSub = $('createSub');
  var toastEl = $('toast');

  var soundOverlay = $('soundOverlay');
  var soundList = $('soundList');
  var moreOverlay = $('moreOverlay');
  var disableOverlay = $('disableOverlay');
  var alarmOverlay = $('alarmOverlay');
  var permOverlay = $('permOverlay');
  var updateOverlay = $('updateOverlay');
  var timeInput = $('timeInput');
  var repeatSwitch = $('repeatSwitch');
  var daysRow = $('daysRow');
  var labelInput = $('labelInput');
  var alarmSaveBtn = $('alarmSave');
  var deleteAlarmBtn = $('deleteAlarmBtn');
  var alarmTitle = $('alarmTitle');
  /* Куда пишется выбор мелодии: 'global' (по умолчанию) или 'alarm' (редактор будильника) */
  var picking = 'global';
  /* Рабочая копия сигнализации в редакторе */
  var editAlarm = null;
  var openSwipe = null; /* открытая строка (свайп-удаление) */
  var swiping = false;  /* активный жест свайпа — рендер списка отложен */

  var bridge = window.NativeAlarm || null;
  var state = loadState();
  var sounds = [{ title: 'По умолчанию', uri: '' }];
  var previewTimer = 0;
  var audioCtx = null;
  var toastTimer = 0;
  /* Редактируемый будильник: true — показывается шторка редактора. */
  var editing = false;
  var editingId = -1;
  var ringingId = -1;

  /* ————— Утилиты ————— */
  function clamp(n, min, max) { return Math.min(max, Math.max(min, n)); }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function formatHM(h, m) { return pad(h) + ':' + pad(m); }
  function num(v, def) { v = Number(v); return isFinite(v) ? v : def; }
  function alarmById(id) {
    for (var i = 0; i < state.alarms.length; i++) {
      if (state.alarms[i].id === id) return state.alarms[i];
    }
    return null;
  }

  /* ————— Состояние (localStorage: только общие настройки; список — нативный) ————— */
  function loadState() {
    var result = { alarms: [] };
    var key;
    for (key in DEFAULT_STATE) {
      if (key !== 'alarms' && Object.prototype.hasOwnProperty.call(DEFAULT_STATE, key)) {
        result[key] = DEFAULT_STATE[key];
      }
    }
    result.customSounds = [];
    try {
      var raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) {
        /* Первый запуск после обновления: переносим старые настройки (v1). */
        raw = localStorage.getItem(OLD_STORAGE_KEY);
        if (!raw) return result;
        var oldJson = JSON.parse(raw);
        if (!oldJson || typeof oldJson !== 'object') return result;
        localStorage.setItem(STORAGE_KEY, JSON.stringify(migrateOld(oldJson)));
        raw = localStorage.getItem(STORAGE_KEY);
      }
      var s = JSON.parse(raw);
      if (!s || typeof s !== 'object') return result;

      result.soundUri = typeof s.soundUri === 'string' ? s.soundUri : '';
      result.soundTitle = typeof s.soundTitle === 'string' && s.soundTitle ? s.soundTitle : 'По умолчанию';
      if (Array.isArray(s.customSounds)) {
        for (var i = 0; i < s.customSounds.length && result.customSounds.length < 8; i++) {
          var c = s.customSounds[i];
          if (c && typeof c.uri === 'string' && c.uri && typeof c.title === 'string') {
            result.customSounds.push({ title: c.title, uri: c.uri });
          }
        }
      }
      result.snoozeMinutes = clamp(parseInt(num(s.snoozeMinutes, 10), 10), 1, 120);
      result.maxSnoozes = clamp(parseInt(num(s.maxSnoozes, 0), 10), 0, 20);
      result.fadeSeconds = clamp(parseInt(num(s.fadeSeconds, 0), 10), 0, 300);
      result.volume = clamp(parseInt(num(s.volume, 0), 10), 0, 100);
      result.vibrate = s.vibrate !== false;
      result.autoStopMinutes = clamp(parseInt(num(s.autoStopMinutes, 10), 10), 0, 120);
      result.challenge = !!s.challenge;
      result.challengeNumbers = clamp(parseInt(num(s.challengeNumbers, 2), 10), 2, 3);
      result.theme = num(s.theme, 0) === 1 ? 1 : 0;
      result.updateCheck = !!s.updateCheck;
      if (Array.isArray(s.alarms)) {
        for (var a = 0; a < s.alarms.length && result.alarms.length < MAX_ALARMS; a++) {
          var al = normalizeAlarm(s.alarms[a]);
          if (al) result.alarms.push(al);
        }
      }
      /* Перенос с 2.9: у будильников, созданных раньше, персональных
         настроек ещё нет — наливаем их из прежних общих значений. */
      var needSeed = false;
      for (var si = 0; si < result.alarms.length; si++) {
        if (typeof result.alarms[si].soundUri !== 'string') { needSeed = true; break; }
      }
      if (needSeed) {
        for (var sj = 0; sj < result.alarms.length; sj++) {
          var alarmSeed = result.alarms[sj];
          if (typeof alarmSeed.soundUri !== 'string') {
            alarmSeed.soundUri = typeof s.soundUri === 'string' ? s.soundUri : '';
            alarmSeed.volume = result.volume;
            alarmSeed.fadeSeconds = result.fadeSeconds;
            alarmSeed.autoStopMinutes = result.autoStopMinutes;
            alarmSeed.snoozeMinutes = result.snoozeMinutes;
            alarmSeed.maxSnoozes = result.maxSnoozes;
            alarmSeed.vibrate = result.vibrate;
            alarmSeed.challenge = result.challenge;
            alarmSeed.challengeNumbers = result.challengeNumbers;
          }
        }
      }
    } catch (e) {
      /* повреждённые данные — значения по умолчанию */
    }
    return result;
  }

  /** Старое состояние (один будильник) — в новый формат общих настроек. */
  function migrateOld(s) {
    var m = {
      soundUri: typeof s.soundUri === 'string' ? s.soundUri : '',
      soundTitle: typeof s.soundTitle === 'string' && s.soundTitle ? s.soundTitle : 'По умолчанию',
      customSounds: Array.isArray(s.customSounds) ? s.customSounds.slice(0, 8) : [],
      snoozeMinutes: clamp(parseInt(num(s.snoozeMinutes, 10), 10), 1, 120),
      maxSnoozes: clamp(parseInt(num(s.maxSnoozes, 0), 10), 0, 20),
      fadeSeconds: clamp(parseInt(num(s.fadeSeconds, 0), 10), 0, 300),
      volume: clamp(parseInt(num(s.volume, 0), 10), 0, 100),
      vibrate: s.vibrate !== false,
      autoStopMinutes: clamp(parseInt(num(s.autoStopMinutes, 10), 10), 0, 120),
      challenge: !!s.challenge,
      challengeNumbers: clamp(parseInt(num(s.challengeNumbers, 2), 10), 2, 3),
      theme: num(s.theme, 0) === 1 ? 1 : 0,
      alarms: []
    };
    /* Старый будильник мигрирует как id 1 — нативная сторона делает то же самое. */
    m.alarms = [{
      id: 1,
      enabled: !!s.enabled,
      hour: clamp(parseInt(num(s.hour, 7), 10), 0, 23),
      minute: clamp(parseInt(num(s.minute, 30), 10), 0, 59),
      repeat: s.repeat !== false,
      days: (parseInt(num(s.days, ALL_DAYS), 10) & ALL_DAYS) || ALL_DAYS,
      label: typeof s.label === 'string' ? s.label.slice(0, 24) : ''
    }];
    return m;
  }

  function normalizeAlarm(a) {
    if (!a || typeof a !== 'object') return null;
    var id = parseInt(num(a.id, -1), 10);
    if (!(id >= 0)) return null;
    var out = {
      id: id,
      enabled: !!a.enabled,
      hour: clamp(parseInt(num(a.hour, 7), 10), 0, 23),
      minute: clamp(parseInt(num(a.minute, 30), 10), 0, 59),
      repeat: a.repeat !== false,
      days: (parseInt(num(a.days, ALL_DAYS), 10) & ALL_DAYS) || ALL_DAYS,
      label: typeof a.label === 'string' ? a.label.slice(0, 24) : ''
    };
    /* Персональные настройки (после 2.10 — у каждого будильника свои) */
    if (typeof a.soundUri === 'string') {
      out.soundUri = a.soundUri;
      out.volume = clamp(parseInt(num(a.volume, 0), 10), 0, 100);
      out.fadeSeconds = clamp(parseInt(num(a.fadeSeconds, 0), 10), 0, 300);
      out.autoStopMinutes = clamp(parseInt(num(a.autoStopMinutes, 10), 10), 0, 120);
      out.snoozeMinutes = clamp(parseInt(num(a.snoozeMinutes, 10), 10), 1, 120);
      out.maxSnoozes = clamp(parseInt(num(a.maxSnoozes, 0), 10), 0, 20);
      out.vibrate = a.vibrate !== false;
      out.challenge = !!a.challenge;
      out.challengeNumbers = clamp(parseInt(num(a.challengeNumbers, 2), 10), 2, 3);
    }
    return out;
  }

  function saveState() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch (e) { /* хранилище недоступно */ }
  }

  /* ————— Расчёт ближайшего срабатывания (тот же алгоритм, что в NextAlarm.java) ————— */
  function nextOccurrence(alarm, from) {
    var now = from === undefined ? Date.now() : from;
    var after = now; /* skipBefore входит в native triggerAt, на клиенте не важен */
    var mask = (alarm.days & ALL_DAYS) || ALL_DAYS;
    for (var offset = 0; offset <= 8; offset++) {
      var d = new Date(after);
      d.setDate(d.getDate() + offset);
      d.setHours(alarm.hour, alarm.minute, 0, 0);
      if (d.getTime() <= after) continue;
      var bit = 1 << ((d.getDay() + 6) % 7); /* пн = бит 0 */
      if (!alarm.repeat || (mask & bit)) return d.getTime();
    }
    return after + 86400000;
  }

  function daysSummary(alarm) {
    var m = alarm.days & ALL_DAYS;
    if (m === ALL_DAYS || m === 0) return 'Каждый день';
    if (m === WEEKDAYS) return 'Будни';
    if (m === WEEKEND) return 'Выходные';
    var names = [];
    for (var i = 0; i < 7; i++) if (m & (1 << i)) names.push(DAY_NAMES[i]);
    return names.join(', ');
  }

  function humanDuration(ms) {
    var minutes = Math.max(1, Math.round(ms / 60000));
    if (minutes < 60) return 'через ' + minutes + ' мин';
    var days = Math.floor(minutes / 1440);
    var h = Math.floor((minutes % 1440) / 60);
    var m = minutes % 60;
    if (days > 0) return 'через ' + days + ' д' + (h ? ' ' + h + ' ч' : '');
    return m ? 'через ' + h + ' ч ' + m + ' мин' : 'через ' + h + ' ч';
  }

  function minutesWord(n) {
    var m100 = n % 100, m10 = n % 10;
    if (m100 >= 11 && m100 <= 14) return 'минут';
    if (m10 === 1) return 'минуту';
    if (m10 >= 2 && m10 <= 4) return 'минуты';
    return 'минут';
  }

  function alarmsWord(n) {
    var m100 = n % 100, m10 = n % 10;
    if (m100 >= 11 && m100 <= 14) return 'будильников';
    if (m10 === 1) return 'будильник';
    if (m10 >= 2 && m10 <= 4) return 'будильника';
    return 'будильников';
  }

  /* Ближайший включённый будильник (время, время срабатывания). */
  function nearestEnabled() {
    var best = null, bestTs = 0;
    for (var i = 0; i < state.alarms.length; i++) {
      var a = state.alarms[i];
      if (!a.enabled) continue;
      var ts = nextOccurrence(a);
      if (!best || ts < bestTs) { best = a; bestTs = ts; }
    }
    return { alarm: best, ts: bestTs };
  }

  function nextId() {
    var maxId = 0;
    for (var i = 0; i < state.alarms.length; i++) {
      if (state.alarms[i].id > maxId) maxId = state.alarms[i].id;
    }
    return maxId + 1;
  }

  /* ————— Тема ————— */
  function applyTheme() {
    var light = state.theme === 1;
    document.body.classList.toggle('light', light);
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', light ? '#f4f4f0' : '#0b0b0e');
    var scheme = document.querySelector('meta[name="color-scheme"]');
    if (scheme) scheme.setAttribute('content', light ? 'light' : 'dark');
    if (bridge && typeof bridge.applyTheme === 'function') {
      try { bridge.applyTheme(light); } catch (e) { /* нет моста */ }
    }
  }

  /* ————— Отрисовка ————— */
  function render() {
    var nearest = nearestEnabled();
    var enabledCount = 0;
    for (var i = 0; i < state.alarms.length; i++) {
      if (state.alarms[i].enabled) enabledCount++;
    }

    app.classList.toggle('active', enabledCount > 0);

    /* Статусная строка */
    if (state.alarms.length === 0) {
      statusText.textContent = 'Будильников нет';
    } else if (enabledCount === 0) {
      statusText.textContent = 'Все будильники выключены';
    } else if (enabledCount === 1) {
      statusText.textContent = '1 будильник включён';
    } else {
      statusText.textContent = enabledCount + ' ' + alarmsWord(enabledCount) + ' включено';
    }

    /* Верхние часы: ближайший включённый будильник */
    if (nearest.alarm) {
      timeBtn.textContent = formatHM(nearest.alarm.hour, nearest.alarm.minute);
      nowText.hidden = false;
      metaMid.hidden = false;
      nextText.textContent = (nearest.alarm.label || 'Будильник') + ' · ' + humanDuration(nearest.ts - Date.now());
    } else {
      timeBtn.textContent = '--:--';
      nowText.hidden = true;
      metaMid.hidden = true;
      nextText.textContent = state.alarms.length === 0 ? 'создайте будильник кнопкой ниже' : 'все выключены';
    }

    /* Нижняя секция: звонок или создание */
    var ringing = AlarmServiceRunning();
    createBtn.hidden = ringing;
    snoozeBtn.hidden = !ringing;
    disableBtn.hidden = !ringing;
    if (ringing) {
      snoozeSub.textContent = 'Отложить на ' + state.snoozeMinutes + ' ' + minutesWord(state.snoozeMinutes);
    } else {
      createSub.textContent = state.alarms.length >= MAX_ALARMS
        ? 'максимум — ' + MAX_ALARMS
        : 'создано ' + state.alarms.length + ' из ' + MAX_ALARMS;
    }

    renderList();
    renderSettings();
  }

  /* Лёгкое обновление раз в секунду: только верхние часы и «через …».
     Список будильников не перерисовываем, чтобы не мешать касаниям. */
  function renderTop() {
    var nearest = nearestEnabled();
    if (nearest.alarm) {
      timeBtn.textContent = formatHM(nearest.alarm.hour, nearest.alarm.minute);
      nowText.hidden = false;
      metaMid.hidden = false;
      nowText.textContent = formatHM(new Date().getHours(), new Date().getMinutes());
      nextText.textContent = (nearest.alarm.label || 'Будильник') + ' · ' + humanDuration(nearest.ts - Date.now());
    }
  }

  function AlarmServiceRunning() {
    return ringingId >= 0;
  }

  function renderList() {
    if (swiping) return; /* свайп идёт — не перестраиваем список под пальцем */
    openSwipe = null;
    alarmList.innerHTML = '';
    emptyHint.hidden = state.alarms.length > 0;
    for (var i = 0; i < state.alarms.length; i++) {
      alarmList.appendChild(buildAlarmRow(state.alarms[i], i + 1));
    }
  }

  function buildAlarmRow(a, number) {
    var row = document.createElement('div');
    row.className = 'arow' + (a.enabled ? ' on' : '');
    row.setAttribute('data-id', String(a.id));
    row._offset = 0;

    /* Красная зона удаления — под содержимым строки */
    var del = document.createElement('button');
    del.type = 'button';
    del.className = 'rowdel';
    del.setAttribute('aria-label', 'Удалить будильник ' + formatHM(a.hour, a.minute));
    del.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
      + 'stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M8 6V4h8v2"/>'
      + '<path d="M6 6l1 14h10l1-14"/><path d="M10 11v6"/><path d="M14 11v6"/></svg>'
      + '<span class="rowdeltext">Удалить</span>';
    row.appendChild(del);
    row._del = del;

    /* Содержимое строки — оно и сдвигается при свайпе */
    var content = document.createElement('div');
    content.className = 'rowcontent';

    /* Порядковый номер в перечне */
    var num = document.createElement('span');
    num.className = 'anum';
    num.textContent = number;
    content.appendChild(num);

    var time = document.createElement('span');
    time.className = 'atime';
    time.textContent = formatHM(a.hour, a.minute);
    content.appendChild(time);

    var info = document.createElement('span');
    info.className = 'ainfo';
    var label = document.createElement('span');
    label.className = 'alabel';
    if (a.label) {
      label.textContent = a.label;
    } else {
      label.classList.add('dim');
      label.textContent = 'Будильник';
    }
    var sub = document.createElement('span');
    sub.className = 'asub';
    sub.textContent = a.repeat ? daysSummary(a) : 'Один раз';
    info.appendChild(label);
    info.appendChild(sub);
    content.appendChild(info);

    var sw = document.createElement('button');
    sw.type = 'button';
    sw.className = 'switch';
    sw.setAttribute('role', 'switch');
    sw.setAttribute('aria-checked', a.enabled ? 'true' : 'false');
    sw.setAttribute('aria-label', 'Включить будильник ' + formatHM(a.hour, a.minute));
    sw.appendChild(document.createElement('span'));
    sw.addEventListener('click', function (event) {
      event.stopPropagation();
      /* приоткрытая строка: любой тап по ней — отмена удаления */
      if (openSwipe && openSwipe.row === row) { closeOpenSwipe(); return; }
      if (row._suppress) return;
      toggleAlarm(a.id);
    });
    content.appendChild(sw);

    row.appendChild(content);
    row._content = content;

    /* Тап по корзине удаляет — если строка приоткрыта свайпом */
    del.addEventListener('click', function (event) {
      event.stopPropagation();
      if (openSwipe && openSwipe.row === row) removeAlarmById(a.id, row);
    });

    content.addEventListener('click', function () {
      if (row._suppress) return; /* сразу после драга — клики игнорируем */
      /* Тап по приоткрытой строке — отмена удаления */
      if (openSwipe && openSwipe.row === row) { closeOpenSwipe(); return; }
      openEditor(a.id);
    });

    attachSwipe(row, a.id);
    return row;
  }

  function toggleAlarm(id) {
    var a = alarmById(id);
    if (!a) return;
    a.enabled = !a.enabled;
    saveState();
    render();
    pushState();
    toast(a.enabled ? 'Включён на ' + formatHM(a.hour, a.minute) : 'Будильник ' + formatHM(a.hour, a.minute) + ' выключен');
  }

  /* ————— Свайп-удаление ————— */

  function anySheetOpen() {
    return moreOverlay.classList.contains('open') || soundOverlay.classList.contains('open')
      || alarmOverlay.classList.contains('open') || disableOverlay.classList.contains('open')
      || permOverlay.classList.contains('open') || updateOverlay.classList.contains('open');
  }

  /* Пороги свайпа — в долях ширины экрана (по опыту пользователя):
     • зона удаления проявляется, когда тумблер доходит до отметки (~0.16 W);
     • отпускание дальше отметки удаления (~0.53 W) — удаляет будильник;
     • отпускание раньше отметки — строка остаётся приоткрытой на этом месте;
     • отпускание до отметки появления — строка возвращается в исходное;
     • тап по корзине в приоткрытой строке — удалить, тап мимо — вернуть строку. */
  var SW_APPEAR = 0.16;  /* зона полностью проявилась */
  var SW_FADE_FROM = 0.08; /* начало проявления */
  var SW_DELETE = 0.53;  /* порог удаления */

  /* Сдвиг строки: animate — с плавным возвратом */
  function setRowOffset(row, offset, animate) {
    var content = row._content;
    if (!content) return;
    content.style.transition = animate ? 'transform .2s ease' : 'none';
    content.style.willChange = 'transform';
    content.style.transform = 'translateX(' + offset + 'px)';
    if (row._del) {
      var shifted = Math.abs(Math.min(offset, 0)) / window.innerWidth;
      var span = SW_APPEAR - SW_FADE_FROM;
      var reveal = Math.max(0, Math.min(1, (shifted - SW_FADE_FROM) / (span > 0 ? span : 0.08)));
      row._del.style.opacity = String(reveal);
    }
  }

  function closeOpenSwipe() {
    if (!openSwipe) return;
    var row = openSwipe.row;
    openSwipe = null;
    if (!row) return;
    row._offset = 0;
    setRowOffset(row, 0, true);
  }

  /* После драга гасим случайный click, чтобы не открылся редактор */
  function suppressClick(row) {
    row._suppress = true;
    setTimeout(function () { row._suppress = false; }, 350);
  }

  function attachSwipe(row, id) {
    var startX = 0, startY = 0, baseOffset = 0;
    var tracking = false, decided = false, horizontal = false, moved = 0;

    row.addEventListener('touchstart', function (event) {
      if (event.touches.length !== 1) return;
      if (anySheetOpen()) return;
      /* Свайп по другой строке — сначала убрать открытую */
      if (openSwipe && openSwipe.row !== row) closeOpenSwipe();
      startX = event.touches[0].clientX;
      startY = event.touches[0].clientY;
      baseOffset = row._offset || 0;
      tracking = true;
      swiping = true;
      decided = false;
      horizontal = false;
      moved = 0;
    }, { capture: true, passive: true });

    row.addEventListener('touchmove', function (event) {
      if (!tracking) return;
      var dx = event.touches[0].clientX - startX;
      var dy = event.touches[0].clientY - startY;
      if (!decided) {
        if (Math.abs(dx) < 8 && Math.abs(dy) < 8) return;
        decided = true;
        horizontal = Math.abs(dx) > Math.abs(dy);
        if (!horizontal) { tracking = false; return; }
      }
      if (!horizontal) return;
      moved = Math.max(moved, Math.abs(dx));
      /* Только влево; ширина — по экрану */
      var cap = -Math.round(window.innerWidth * 0.87);
      var target = baseOffset + dx;
      if (target > 0) target = 0;
      if (target < cap) target = cap;
      row._offset = target;
      setRowOffset(row, target, false);
      if (event.cancelable) event.preventDefault();
    }, { passive: false });

    row.addEventListener('touchend', function () {
      if (!tracking) return;
      tracking = false;
      /* с маленькой задержкой: даём анимациям отработать без перерисовки */
      setTimeout(function () { swiping = false; }, 260);
      if (moved > 10) suppressClick(row);
      var offset = row._offset || 0;
      var shifted = -Math.min(offset, 0) / window.innerWidth;
      if (shifted >= SW_DELETE) {
        /* доведён до отметки удаления или дальше — удаляем */
        openSwipe = null;
        removeAlarmById(id, row);
        return;
      }
      if (shifted >= SW_APPEAR) {
        /* остаётся на месте, где отпустили; корзина видна, решение за тапом */
        openSwipe = { row: row };
        return;
      }
      /* не дошёл до отметки появления — возвращается сам, открытая отмена */
      row._offset = 0;
      setRowOffset(row, 0, true);
      if (openSwipe && openSwipe.row === row) openSwipe = null;
    }, { capture: true, passive: true });

    /* Система прервала жест — вернуть строку и снять блокировку рендера */
    row.addEventListener('touchcancel', function () {
      if (!tracking) { swiping = false; return; }
      tracking = false;
      row._offset = 0;
      setRowOffset(row, 0, true);
      if (openSwipe && openSwipe.row === row) openSwipe = null;
      setTimeout(function () { swiping = false; }, 260);
    }, { capture: true, passive: true });
  }

  /* Тап/фокус мимо открытой строки — отменить удаление */
  document.addEventListener('touchstart', function (event) {
    if (!openSwipe) return;
    var target = event.target;
    if (openSwipe.row && !openSwipe.row.contains(target)) closeOpenSwipe();
  }, { capture: true, passive: true });

  /* Удаление будильника по id: из свайпа или из редактора.
     Если передана строка (rowEl) — перед перестройкой списка строка
     плавно уезжает за край и схлопывается по высоте. */
  function removeAlarmById(id, rowEl) {
    swiping = false; /* жест завершён — список можно перестроить сразу */
    var idx = -1;
    for (var i = 0; i < state.alarms.length; i++) {
      if (state.alarms[i].id === id) { idx = i; break; }
    }
    if (idx < 0) return;
    if (ringingId === id) {
      /* Удаляем будильник, который сейчас звонит — звонок прекращаем */
      if (bridge && typeof bridge.stopRinging === 'function') {
        try { bridge.stopRinging(); } catch (e) { /* ignore */ }
      }
      ringingId = -1;
    }
    state.alarms.splice(idx, 1);
    openSwipe = null;
    if (editing && editingId === id) {
      closeSheet(alarmOverlay);
    }
    saveState();
    pushState();

    /* Плавный уход строки: содержимое доезжает за край, высота схлопывается,
       только потом перестраиваем список */
    if (rowEl && rowEl.parentNode) {
      content = rowEl._content;
      if (content) {
        content.style.transition = 'transform .24s ease';
        content.style.transform = 'translateX(-' + Math.round(window.innerWidth * 1.05) + 'px)';
      }
      rowEl.style.transition = 'max-height .3s ease .12s, opacity .3s ease .12s';
      rowEl.style.maxHeight = rowEl.offsetHeight + 'px';
      void rowEl.offsetHeight; /* фиксируем текущую высоту, чтобы анимация пошла */
      rowEl.style.maxHeight = '0px';
      rowEl.style.opacity = '0';
      swiping = true; /* пока анимация — не мешаем лишней перестройкой списка */
      setTimeout(function () {
        swiping = false;
        render();
      }, 440);
    } else {
      render();
    }
    toast('Будильник удалён');
  }

  function renderSettings() {
    var groups = moreOverlay.querySelectorAll('.chips[data-key]');
    for (var g = 0; g < groups.length; g++) {
      var key = groups[g].getAttribute('data-key');
      var chips = groups[g].querySelectorAll('.chip');
      for (var i = 0; i < chips.length; i++) {
        chips[i].classList.toggle('on', Number(chips[i].getAttribute('data-v')) === state[key]);
      }
    }
    $('updateSwitch').setAttribute('aria-checked', state.updateCheck ? 'true' : 'false');
  }

  /* ————— Связь с Android ————— */
  function configObject() {
    return {
      alarms: state.alarms,
      soundUri: state.soundUri || '',
      snoozeMinutes: state.snoozeMinutes,
      maxSnoozes: state.maxSnoozes,
      fadeSeconds: state.fadeSeconds,
      volume: state.volume,
      vibrate: state.vibrate,
      autoStopMinutes: state.autoStopMinutes,
      challenge: state.challenge,
      challengeNumbers: state.challengeNumbers,
      updateCheck: state.updateCheck
    };
  }

  /* Полная синхронизация: список будильников + настройки. */
  function pushState() {
    if (!bridge || typeof bridge.applyConfig !== 'function') return;
    try { bridge.applyConfig(JSON.stringify(configObject())); } catch (e) { /* нет моста */ }
  }

  /* Только общие настройки — расписание не трогает. */
  function pushSettings() {
    if (!bridge || typeof bridge.saveSettings !== 'function') return;
    try { bridge.saveSettings(JSON.stringify(configObject())); } catch (e) { /* нет моста */ }
  }

  function syncFromNative() {
    if (!bridge || typeof bridge.getState !== 'function') return;
    var ns;
    try { ns = JSON.parse(bridge.getState()); } catch (e) { return; }
    if (!ns) return;

    ringingId = ns.ringing === undefined ? -1 : (ns.ringing === null ? -1 : Number(ns.ringing));

    var nativeAlarms = [];
    if (Array.isArray(ns.alarms)) {
      for (var i = 0; i < ns.alarms.length && nativeAlarms.length < MAX_ALARMS; i++) {
        var a = normalizeAlarm(ns.alarms[i]);
        if (a) nativeAlarms.push(a);
      }
    }

    /* Одноразовый будильник прозвенел или был отключён — натив — источник истины. */
    var changed = nativeAlarms.length !== state.alarms.length;
    if (!changed) {
      for (var j = 0; j < nativeAlarms.length; j++) {
        var na = nativeAlarms[j], sa = state.alarms[j];
        if (!sa || na.id !== sa.id || na.enabled !== sa.enabled
            || na.hour !== sa.hour || na.minute !== sa.minute
            || na.repeat !== sa.repeat || na.days !== sa.days || na.label !== sa.label) {
          changed = true;
          break;
        }
      }
    }
    if (changed) {
      state.alarms = nativeAlarms;
      saveState();
      if (!editing) render();
      else renderList();
    }
  }

  /* ————— Звуки ————— */
  function loadSoundList() {
    var system = [{ title: 'По умолчанию', uri: '' }];
    if (bridge && typeof bridge.getAlarmSounds === 'function') {
      try {
        var list = JSON.parse(bridge.getAlarmSounds());
        if (Array.isArray(list) && list.length) system = list;
      } catch (e) { /* остаётся по умолчанию */ }
    } else {
      system = [
        { title: 'По умолчанию', uri: '' },
        { title: 'Классический', uri: 'demo:classic' },
        { title: 'Мягкий', uri: 'demo:soft' },
        { title: 'Резкий', uri: 'demo:sharp' }
      ];
    }
    sounds = system.slice();
    for (var c = 0; c < state.customSounds.length; c++) {
      sounds.push({ title: state.customSounds[c].title, uri: state.customSounds[c].uri, custom: true });
    }
    if (state.soundUri) {
      for (var i2 = 0; i2 < sounds.length; i2++) {
        if (sounds[i2].uri === state.soundUri) { state.soundTitle = sounds[i2].title; break; }
      }
    }
  }

  window.__onSoundPicked = function (title, uri) {
    if (!uri) return;
    var list = [{ title: title, uri: uri }];
    for (var i = 0; i < state.customSounds.length; i++) {
      if (state.customSounds[i].uri !== uri) list.push(state.customSounds[i]);
    }
    state.customSounds = list.slice(0, 8);
    saveState();
    if (picking === 'alarm' && editAlarm) {
      /* Мелодия выбрана для конкретного будильника: общие не трогаем */
      editAlarm.soundUri = uri;
      editAlarm.soundTitle = title;
      loadSoundList();
      buildSoundList();
      updateEditorSound();
      if (soundOverlay.classList.contains('open')) closeSheet(soundOverlay);
      toast('Звук выбран: ' + title);
      return;
    }
    state.soundTitle = title;
    state.soundUri = uri;
    loadSoundList();
    render();
    pushSettings();
    if (soundOverlay.classList.contains('open')) buildSoundList();
    toast('Звук выбран: ' + title);
  };

  function removeCustomSound(uri) {
    var list = [];
    for (var i = 0; i < state.customSounds.length; i++) {
      if (state.customSounds[i].uri !== uri) list.push(state.customSounds[i]);
    }
    state.customSounds = list;
    if (picking === 'alarm' && editAlarm && editAlarm.soundUri === uri) {
      /* Удалённая мелодия была у этого будильника — откат на умолчание */
      editAlarm.soundUri = '';
      editAlarm.soundTitle = 'По умолчанию';
      updateEditorSound();
    }
    if (state.soundUri === uri) { state.soundUri = ''; state.soundTitle = 'По умолчанию'; }
    saveState();
    loadSoundList();
    if (picking === 'alarm' && editAlarm) {
      buildSoundList();
    } else {
      render();
      pushSettings();
      buildSoundList();
    }
  }

  function selectSound(title, uri) {
    if (picking === 'alarm' && editAlarm) {
      /* Выбор в пользу конкретного будильника — и список закрывается */
      editAlarm.soundUri = uri || '';
      editAlarm.soundTitle = title;
      buildSoundList();
      updateEditorSound();
      closeSheet(soundOverlay);
      return;
    }
    state.soundTitle = title;
    state.soundUri = uri || '';
    saveState();
    render();
    pushSettings();
  }

  /* Название мелодии по URI — для строки редактора */
  function soundTitleByUri(uri) {
    for (var i = 0; i < sounds.length; i++) {
      if (sounds[i].uri === (uri || '')) return sounds[i].title || 'По умолчанию';
    }
    return uri ? 'Своя мелодия' : 'По умолчанию';
  }

  function updateEditorSound() {
    var el = $('eSoundValue');
    if (!el || !editAlarm) return;
    el.textContent = soundTitleByUri(editAlarm.soundUri);
  }

  function playPreview(uri) {
    stopPreview();
    if (bridge && typeof bridge.playPreview === 'function') {
      try { bridge.playPreview(uri || ''); } catch (e) { /* ignore */ }
      return;
    }
    playDemoTone(uri);
  }

  function stopPreview() {
    clearTimeout(previewTimer);
    if (bridge && typeof bridge.stopPreview === 'function') {
      try { bridge.stopPreview(); } catch (e) { /* ignore */ }
    }
    if (audioCtx) {
      try { audioCtx.close(); } catch (e) { /* ignore */ }
      audioCtx = null;
    }
  }

  function playDemoTone(uri) {
    try {
      var Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) return;
      audioCtx = new Ctx();
      var base = uri === 'demo:sharp' ? 880 : (uri === 'demo:soft' ? 440 : 660);
      for (var i = 0; i < 3; i++) {
        var osc = audioCtx.createOscillator();
        var gain = audioCtx.createGain();
        var start = audioCtx.currentTime + i * 0.32;
        osc.type = uri === 'demo:sharp' ? 'square' : 'sine';
        osc.frequency.value = base;
        gain.gain.setValueAtTime(0.0001, start);
        gain.gain.exponentialRampToValueAtTime(0.18, start + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.22);
        osc.connect(gain).connect(audioCtx.destination);
        osc.start(start);
        osc.stop(start + 0.24);
      }
      previewTimer = setTimeout(stopPreview, 1400);
    } catch (e) { /* ignore */ }
  }

  /* ————— Тосты ————— */
  function toast(message) {
    toastEl.textContent = message;
    var sheetOpen = anySheetOpen();
    toastEl.classList.toggle('top', sheetOpen);
    toastEl.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.classList.remove('show'); }, 2200);
  }

  /* ————— Шторки ————— */
  function openSheet(overlay) {
    if (overlay === soundOverlay) buildSoundList();
    if (overlay === moreOverlay) { renderSettings(); refreshHealth(); }
    overlay.classList.add('open');
    overlay.setAttribute('aria-hidden', 'false');
  }

  function closeSheet(overlay) {
    var wasOpen = overlay.classList.contains('open');
    overlay.classList.remove('open');
    overlay.setAttribute('aria-hidden', 'true');
    stopPreview();
    if (overlay === alarmOverlay) {
      editing = false;
      editAlarm = null;
      picking = 'global';
      setTimeout(function () { timeInput.blur(); labelInput.blur(); }, 250);
    }
    if (overlay === moreOverlay && document.activeElement) document.activeElement.blur();
    return wasOpen;
  }

  function closeTopSheet() {
    var order = [updateOverlay, permOverlay, moreOverlay, soundOverlay, alarmOverlay, disableOverlay];
    for (var i = 0; i < order.length; i++) {
      if (order[i].classList.contains('open')) { closeSheet(order[i]); return true; }
    }
    return false;
  }

  /* Системная кнопка «назад»: если шторка открыта — закрываем её. */
  window.__nativeBack = function () { return closeTopSheet(); };

  function buildSoundList() {
    soundList.innerHTML = '';
    for (var i = 0; i < sounds.length; i++) soundList.appendChild(buildSoundRow(sounds[i]));
  }

  function buildSoundRow(sound) {
    var row = document.createElement('button');
    row.type = 'button';
    var currentUri = (picking === 'alarm' && editAlarm) ? (editAlarm.soundUri || '') : (state.soundUri || '');
    row.className = 'sound' + (sound.uri === currentUri ? ' selected' : '');
    row.setAttribute('data-uri', sound.uri || '');
    row.setAttribute('data-title', sound.title || 'По умолчанию');

    var title = document.createElement('span');
    title.className = 'sound-title';
    title.textContent = (sound.custom ? '♪ ' : '') + (sound.title || 'По умолчанию');
    row.appendChild(title);

    var del = null;
    if (sound.custom) {
      del = document.createElement('span');
      del.className = 'del';
      del.setAttribute('role', 'button');
      del.setAttribute('aria-label', 'Убрать из списка');
      del.textContent = '×';
      row.appendChild(del);
    }

    var play = document.createElement('span');
    play.className = 'play';
    play.setAttribute('role', 'button');
    play.setAttribute('aria-label', 'Прослушать');
    play.innerHTML = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.5v13a1 1 0 0 0 1.5.86l10-6.5a1 1 0 0 0 0-1.72l-10-6.5A1 1 0 0 0 8 5.5z"/></svg>';
    row.appendChild(play);

    var radio = document.createElement('span');
    radio.className = 'radio';
    row.appendChild(radio);

    row.addEventListener('click', function (event) {
      var uri = row.getAttribute('data-uri') || '';
      var titleText = row.getAttribute('data-title') || 'По умолчанию';
      if (del && (event.target === del)) { removeCustomSound(uri); return; }
      if (event.target === play || play.contains(event.target)) { playPreview(uri); return; }
      var nodes = soundList.querySelectorAll('.sound');
      for (var j = 0; j < nodes.length; j++) nodes[j].classList.remove('selected');
      row.classList.add('selected');
      selectSound(titleText, uri);
      playPreview(uri);
    });
    return row;
  }

  /* ————— Редактор будильника ————— */

  /* Персональные настройки по умолчанию: берём прежние общие значения */
  function defaultAlarmSettings() {
    return {
      soundUri: state.soundUri || '',
      soundTitle: state.soundTitle || 'По умолчанию',
      volume: state.volume,
      fadeSeconds: state.fadeSeconds,
      autoStopMinutes: state.autoStopMinutes,
      snoozeMinutes: state.snoozeMinutes,
      maxSnoozes: state.maxSnoozes,
      vibrate: state.vibrate,
      challenge: state.challenge,
      challengeNumbers: state.challengeNumbers
    };
  }

  function openEditor(id) {
    var isNew = id < 0;
    if (isNew && state.alarms.length >= MAX_ALARMS) {
      toast('Не больше ' + MAX_ALARMS + ' будильников');
      return;
    }
    editing = true;
    editingId = isNew ? nextId() : id;
    var a = isNew ? null : alarmById(id);
    if (!isNew && !a) { editing = false; return; }
    if (isNew) {
      /* Новому будильнику подсказываем время: через 8 часов от текущего или 07:30. */
      var suggest = new Date(Date.now() + 8 * 3600 * 1000);
      a = { id: editingId, enabled: true, hour: suggest.getHours(), minute: suggest.getMinutes(),
            repeat: true, days: ALL_DAYS, label: '' };
      var def = defaultAlarmSettings();
      for (var dk in def) { if (def.hasOwnProperty(dk)) a[dk] = def[dk]; }
    }
    /* Рабочая копия: изменения уходят в будильник только по кнопке «Готово» */
    editAlarm = {};
    for (var ek in a) { if (a.hasOwnProperty(ek)) editAlarm[ek] = a[ek]; }

    alarmTitle.textContent = isNew ? 'Новый будильник' : 'Будильник';
    timeInput.value = formatHM(a.hour, a.minute);
    repeatSwitch.setAttribute('aria-checked', a.repeat ? 'true' : 'false');
    updateDaysRow(a.days);
    labelInput.value = a.label || '';
    deleteAlarmBtn.hidden = isNew;

    /* Персональные настройки этого будильника */
    updateEditorSound();
    var evr = $('eVolumeRange');
    evr.value = editAlarm.volume;
    $('eVolumeValue').textContent = editAlarm.volume ? editAlarm.volume + '%' : 'Как в системе';
    renderEditorChips();
    $('eVibrateSwitch').setAttribute('aria-checked', editAlarm.vibrate ? 'true' : 'false');
    $('eChallengeSwitch').setAttribute('aria-checked', editAlarm.challenge ? 'true' : 'false');
    $('eChallengeNumbersSet').hidden = !editAlarm.challenge;

    openSheet(alarmOverlay);
  }

  function updateDaysRow(days) {
    var btns = daysRow.querySelectorAll('.day');
    for (var i = 0; i < btns.length; i++) {
      btns[i].classList.toggle('on', !!(days & (1 << i)));
    }
    var mask = days & ALL_DAYS;
    daysRow.classList.toggle('disabled', !editingRepeat());
  }

  function editingRepeat() {
    return repeatSwitch.getAttribute('aria-checked') === 'true';
  }

  /* Подсветка выбранных чипов персональных настроек */
  function renderEditorChips() {
    if (!editAlarm) return;
    var groups = alarmOverlay.querySelectorAll('.chips[data-ekey]');
    for (var g = 0; g < groups.length; g++) {
      var key = groups[g].getAttribute('data-ekey');
      var chips = groups[g].querySelectorAll('.chip');
      for (var i = 0; i < chips.length; i++) {
        chips[i].classList.toggle('on', Number(chips[i].getAttribute('data-v')) === editAlarm[key]);
      }
    }
  }

  function editorAlarm() {
    var parts = (timeInput.value || '').split(':');
    var hour = parseInt(parts[0], 10);
    var minute = parseInt(parts[1], 10);
    var days = 0;
    var btns = daysRow.querySelectorAll('.day');
    for (var i = 0; i < btns.length; i++) {
      if (btns[i].classList.contains('on')) days |= (1 << parseInt(btns[i].getAttribute('data-day'), 10));
    }
    return {
      id: editingId,
      enabled: true,
      hour: isFinite(hour) ? clamp(hour, 0, 23) : 7,
      minute: isFinite(minute) ? clamp(minute, 0, 59) : 30,
      repeat: editingRepeat(),
      days: days || ALL_DAYS,
      label: labelInput.value.slice(0, 24),
      /* Персональные настройки — как отредактировано */
      soundUri: editAlarm ? (editAlarm.soundUri || '') : '',
      volume: editAlarm ? editAlarm.volume : state.volume,
      fadeSeconds: editAlarm ? editAlarm.fadeSeconds : state.fadeSeconds,
      autoStopMinutes: editAlarm ? editAlarm.autoStopMinutes : state.autoStopMinutes,
      snoozeMinutes: editAlarm ? editAlarm.snoozeMinutes : state.snoozeMinutes,
      maxSnoozes: editAlarm ? editAlarm.maxSnoozes : state.maxSnoozes,
      vibrate: editAlarm ? editAlarm.vibrate : state.vibrate,
      challenge: editAlarm ? editAlarm.challenge : state.challenge,
      challengeNumbers: editAlarm ? editAlarm.challengeNumbers : state.challengeNumbers
    };
  }

  alarmSaveBtn.addEventListener('click', function () {
    if (!editing) return;
    var a = editorAlarm();
    if (repeatSwitch.getAttribute('aria-checked') === 'true' && (a.days & ALL_DAYS) === 0) {
      toast('Выберите хотя бы один день');
      return;
    }
    if (!alarmById(a.id)) {
      if (state.alarms.length >= MAX_ALARMS) {
        toast('Не больше ' + MAX_ALARMS + ' будильников');
        return;
      }
      state.alarms.push(a);
    } else {
      for (var i = 0; i < state.alarms.length; i++) {
        if (state.alarms[i].id === a.id) { state.alarms[i] = a; break; }
      }
    }
    editing = false;
    closeSheet(alarmOverlay);
    saveState();
    render();
    pushState();
    toast('Будильник на ' + formatHM(a.hour, a.minute));
  });

  deleteAlarmBtn.addEventListener('click', function () {
    if (!editing) return;
    removeAlarmById(editingId);
  });

  /* ————— Персональные настройки в редакторе ————— */
  $('eSoundRow').addEventListener('click', function () {
    picking = 'alarm';
    openSheet(soundOverlay);
  });

  $('eVolumeRange').addEventListener('input', function () {
    if (!editAlarm) return;
    editAlarm.volume = clamp(parseInt(this.value, 10) || 0, 0, 100);
    $('eVolumeValue').textContent = editAlarm.volume ? editAlarm.volume + '%' : 'Как в системе';
  });

  $('eVibrateSwitch').addEventListener('click', function () {
    if (!editAlarm) return;
    editAlarm.vibrate = !editAlarm.vibrate;
    this.setAttribute('aria-checked', editAlarm.vibrate ? 'true' : 'false');
  });

  $('eChallengeSwitch').addEventListener('click', function () {
    if (!editAlarm) return;
    editAlarm.challenge = !editAlarm.challenge;
    this.setAttribute('aria-checked', editAlarm.challenge ? 'true' : 'false');
    $('eChallengeNumbersSet').hidden = !editAlarm.challenge;
    toast(editAlarm.challenge ? 'Для отключения нужно решить пример' : 'Пример для отключения выключен');
  });

  var eChips = alarmOverlay.querySelectorAll('.chips[data-ekey]');
  for (var eg = 0; eg < eChips.length; eg++) {
    eChips[eg].addEventListener('click', function (event) {
      if (!editAlarm) return;
      var chip = event.target.closest ? event.target.closest('.chip') : null;
      if (!chip) return;
      var key = event.currentTarget.getAttribute('data-ekey');
      editAlarm[key] = Number(chip.getAttribute('data-v'));
      renderEditorChips();
    });
  }

  $('eTestBtn').addEventListener('click', function () {
    if (bridge && typeof bridge.testAlarm === 'function') {
      /* Настоящий пробный звонок настройками этого будильника */
      try { bridge.testAlarm(editingId); } catch (e) { toast('Не удалось запустить'); }
    } else {
      playPreview(editAlarm ? editAlarm.soundUri : '');
      toast('Пробный звук');
    }
  });

  repeatSwitch.addEventListener('click', function () {
    repeatSwitch.setAttribute('aria-checked', editingRepeat() ? 'false' : 'true');
    daysRow.classList.toggle('disabled', !editingRepeat());
  });

  daysRow.addEventListener('click', function (event) {
    if (!editingRepeat()) return;
    var btn = event.target.closest ? event.target.closest('.day') : null;
    if (!btn) return;
    var days = 0, next;
    var btns = daysRow.querySelectorAll('.day');
    for (var i = 0; i < btns.length; i++) {
      if (btns[i].classList.contains('on')) days |= (1 << parseInt(btns[i].getAttribute('data-day'), 10));
    }
    next = days ^ (1 << parseInt(btn.getAttribute('data-day'), 10));
    if ((next & ALL_DAYS) === 0) { toast('Выберите хотя бы один день'); return; }
    updateDaysRow(next);
  });

  /* ————— Надёжность ————— */
  function refreshHealth() {
    var group = $('healthGroup');
    if (!bridge || typeof bridge.getHealth !== 'function') { group.style.display = 'none'; return; }
    var h;
    try { h = JSON.parse(bridge.getHealth()); } catch (e) { group.style.display = 'none'; return; }
    var items = [
      { ok: h.notifications, key: 'notifications', title: 'Уведомления',
        good: 'Разрешены', bad: 'Выключены — звонок может не показаться' },
      { ok: h.battery, key: 'battery', title: 'Работа в фоне',
        good: 'Без ограничений батареи', bad: 'Система может ограничить будильник' },
      { ok: h.fullScreen, key: 'fullscreen', title: 'Экран поверх блокировки',
        good: 'Разрешён', bad: 'Запрещён — звонок будет только в шторке' }
    ];
    var list = $('healthList');
    list.innerHTML = '';
    items.forEach(function (it) {
      var row = document.createElement('div');
      row.className = 'health';
      var dot = document.createElement('span');
      dot.className = 'hdot' + (it.ok ? '' : ' bad');
      var text = document.createElement('span');
      text.className = 'htext';
      text.textContent = it.title;
      var small = document.createElement('small');
      small.textContent = it.ok ? it.good : it.bad;
      text.appendChild(small);
      row.appendChild(dot);
      row.appendChild(text);
      if (!it.ok) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn-sm accent';
        btn.textContent = 'Исправить';
        btn.addEventListener('click', function () {
          try { bridge.openSettings(it.key); } catch (e) { /* ignore */ }
        });
        row.appendChild(btn);
      }
      list.appendChild(row);
    });
  }

  /* ————— Звонок с главного экрана ————— */
  snoozeBtn.addEventListener('click', function () {
    var minutes = state.snoozeMinutes;
    if (bridge && typeof bridge.snooze === 'function') {
      try {
        bridge.stopRinging();
        bridge.snooze(minutes, state.soundUri || '');
      } catch (e) { /* ignore */ }
    }
    ringingId = -1;
    render();
    toast('Отложено на ' + minutes + ' ' + minutesWord(minutes));
    setTimeout(syncFromNative, 700);
  });

  disableBtn.addEventListener('click', function () {
    openSheet(disableOverlay);
  });

  $('disableDayBtn').addEventListener('click', function () {
    if (bridge && typeof bridge.stopAlarm === 'function') {
      try { bridge.stopAlarm('skip'); } catch (e) { /* ignore */ }
    } else if (bridge && typeof bridge.stopRinging === 'function') {
      try { bridge.stopRinging(); } catch (e) { /* ignore */ }
    }
    ringingId = -1;
    render();
    closeSheet(disableOverlay);
    toast('Звонок остановлен, расписание сохранено');
    setTimeout(syncFromNative, 700);
  });

  $('disableForeverBtn').addEventListener('click', function () {
    if (bridge && typeof bridge.stopAlarm === 'function') {
      try { bridge.stopAlarm('forever'); } catch (e) { /* ignore */ }
    } else if (bridge && typeof bridge.stopRinging === 'function') {
      try { bridge.stopRinging(); } catch (e) { /* ignore */ }
    }
    ringingId = -1;
    render();
    closeSheet(disableOverlay);
    toast('Будильник отключён полностью');
    setTimeout(syncFromNative, 700);
  });

  /* ————— Настройки в шторке ————— */
  var chipGroups = moreOverlay.querySelectorAll('.chips[data-key]');
  for (var g = 0; g < chipGroups.length; g++) {
    chipGroups[g].addEventListener('click', function (event) {
      var chip = event.target.closest ? event.target.closest('.chip') : null;
      if (!chip) return;
      var key = event.currentTarget.getAttribute('data-key');
      state[key] = Number(chip.getAttribute('data-v'));
      saveState();
      render();
      pushSettings();
      if (key === 'theme') applyTheme();
    });
  }

  /* ————— Обновления ————— */
  $('updateSwitch').addEventListener('click', function () {
    if (state.updateCheck) {
      state.updateCheck = false;
      saveState();
      render();
      pushSettings();
      toast('Проверка обновлений выключена');
    } else {
      /* Включаем только после явного согласия на доступ в интернет. */
      openSheet(permOverlay);
    }
  });

  $('permAllow').addEventListener('click', function () {
    closeSheet(permOverlay);
    state.updateCheck = true;
    saveState();
    render();
    pushSettings();
    toast('Проверка обновлений включена');
    /* Сразу первая проверка — она же подтвердит, что всё работает. */
    if (bridge && typeof bridge.checkUpdateNow === 'function') {
      try { bridge.checkUpdateNow(); } catch (e) { /* ignore */ }
    }
  });

  $('permDeny').addEventListener('click', function () {
    closeSheet(permOverlay);
    state.updateCheck = false;
    saveState();
    render();
    pushSettings();
    toast('Проверка останется выключенной');
  });

  $('updateCheckBtn').addEventListener('click', function () {
    if (!state.updateCheck) {
      /* Без согласия интернет не доступен — сначала спрашиваем. */
      openSheet(permOverlay);
      return;
    }
    if (bridge && typeof bridge.checkUpdateNow === 'function') {
      toast('Проверяю…');
      try { bridge.checkUpdateNow(); } catch (e) { toast('Не удалось начать проверку'); }
    } else {
      toast('Проверка доступна в приложении');
    }
  });

  $('updateLater').addEventListener('click', function () {
    closeSheet(updateOverlay);
    if (bridge && typeof bridge.discardUpdate === 'function') {
      try { bridge.discardUpdate(); } catch (e) { /* ignore */ }
    }
    toast('Предложим при следующей проверке');
  });

  $('updateNow').addEventListener('click', function () {
    closeSheet(updateOverlay);
    if (bridge && typeof bridge.downloadUpdate === 'function') {
      try { bridge.downloadUpdate(); } catch (e) { toast('Не удалось начать скачивание'); }
    } else {
      toast('Обновление доступно в приложении');
    }
  });

  /* Ответ нативной стороны: {available, version, manual, error} */
  window.__onUpdateCheck = function (json) {
    var data;
    try { data = JSON.parse(json); } catch (e) { return; }
    if (!data) return;
    if (data.error) {
      toast(data.manual ? 'Нет сети — проверьте подключение' : 'Проверка не удалась');
      return;
    }
    if (ringingId >= 0) {
      /* Будильник звонит — предложение покажется при следующей проверке. */
      if (data.manual) toast('Новая версия ' + data.version + ' — не сейчас, звонит будильник');
      return;
    }
    if (data.available) {
      var v = $('updateVersion');
      if (v) v.textContent = data.version || '—';
      openSheet(updateOverlay);
    } else if (data.manual) {
      toast('У вас последняя версия');
    }
  };

  /* Прогресс фонового скачивания APK (проценты, приходят каждые ~10%). */
  window.__onUpdateProgress = function (pct) {
    var n = parseInt(pct, 10);
    if (isFinite(n) && n > 0 && n < 100) toast('Скачивание обновления: ' + n + '%');
  };

  /* ————— События ————— */
  createBtn.addEventListener('click', function () { openEditor(-1); });

  timeBtn.addEventListener('click', function () {
    /* Крупные часы: редактируем ближайший включённый, либо открываем создание. */
    var nearest = nearestEnabled();
    openEditor(nearest.alarm ? nearest.alarm.id : -1);
  });

  gearBtn.addEventListener('click', function () { openSheet(moreOverlay); });

  $('addFileBtn').addEventListener('click', function () {
    if (bridge && typeof bridge.pickSoundFile === 'function') {
      try { bridge.pickSoundFile(); } catch (e) { toast('Не удалось открыть выбор файла'); }
    } else {
      toast('Выбор файла доступен в приложении');
    }
  });

  $('diagClear').addEventListener('click', function () {
    if (bridge && typeof bridge.clearDiagLog === 'function') {
      try { bridge.clearDiagLog(); } catch (e) { /* ignore */ }
      toast('Журнал очищен');
    }
  });

  $('diagExport').addEventListener('click', function () {
    if (bridge && typeof bridge.exportDiagLog === 'function') {
      try { bridge.exportDiagLog(); } catch (e) { toast('Не удалось открыть отправку'); }
    } else {
      toast('Доступно в приложении');
    }
  });

  var closes = document.querySelectorAll('[data-close]');
  for (var c = 0; c < closes.length; c++) {
    closes[c].addEventListener('click', function (event) {
      var target = event.currentTarget.getAttribute('data-close');
      closeSheet(target === 'more' ? moreOverlay
        : target === 'disable' ? disableOverlay
        : target === 'alarm' ? alarmOverlay
        : target === 'perm' ? permOverlay
        : target === 'update' ? updateOverlay
        : soundOverlay);
    });
  }

  [soundOverlay, moreOverlay, alarmOverlay, disableOverlay, permOverlay, updateOverlay].forEach(function (overlay) {
    overlay.addEventListener('click', function (event) {
      if (event.target === overlay) closeSheet(overlay);
    });
  });

  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') closeTopSheet();
  });

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) { syncFromNative(); render(); } else { stopPreview(); }
  });

  window.__nativeResume = function () {
    syncFromNative();
    render();
    if (moreOverlay.classList.contains('open')) refreshHealth();
  };

  /* ————— Запуск ————— */
  loadSoundList();
  applyTheme();
  syncFromNative();
  /* Если в v1 был включён будильник, а нативная миграция не создала его — пушим. */
  if (bridge && state.alarms.length === 0 && hadOldAlarm()) {
    var restored = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
    if (restored && Array.isArray(restored.alarms) && restored.alarms.length) {
      state.alarms = restored.alarms;
    }
  }
  render();
  pushState();
  pushSettings();
  setInterval(function () {
    if (!editing) renderTop();
  }, 1000);
  setTimeout(syncFromNative, 600);

  function hadOldAlarm() {
    /* v1-данные уже перенесены в v2 при loadState: миграционный будильник
       попадает в state через loadState только если натив вернул пусто. */
    try {
      var s = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
      return Array.isArray(s.alarms) && s.alarms.length > 0;
    } catch (e) {
      return false;
    }
  }
})();
