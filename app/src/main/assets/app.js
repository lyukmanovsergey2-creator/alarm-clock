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
  var soundValue = $('soundValue');
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
    return {
      id: id,
      enabled: !!a.enabled,
      hour: clamp(parseInt(num(a.hour, 7), 10), 0, 23),
      minute: clamp(parseInt(num(a.minute, 30), 10), 0, 59),
      repeat: a.repeat !== false,
      days: (parseInt(num(a.days, ALL_DAYS), 10) & ALL_DAYS) || ALL_DAYS,
      label: typeof a.label === 'string' ? a.label.slice(0, 24) : ''
    };
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
      statusText.textContent = enabledCount + ' будильника включено';
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
      nextText.textContent = state.alarms.length === 0 ? 'нажмите «+», чтобы создать' : 'все выключены';
    }

    soundValue.textContent = state.soundTitle || 'По умолчанию';

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
    alarmList.innerHTML = '';
    emptyHint.hidden = state.alarms.length > 0;
    for (var i = 0; i < state.alarms.length; i++) {
      alarmList.appendChild(buildAlarmRow(state.alarms[i]));
    }
  }

  function buildAlarmRow(a) {
    var row = document.createElement('div');
    row.className = 'arow' + (a.enabled ? ' on' : '');
    row.setAttribute('data-id', String(a.id));

    var time = document.createElement('span');
    time.className = 'atime';
    time.textContent = formatHM(a.hour, a.minute);
    row.appendChild(time);

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
    row.appendChild(info);

    var sw = document.createElement('button');
    sw.type = 'button';
    sw.className = 'switch';
    sw.setAttribute('role', 'switch');
    sw.setAttribute('aria-checked', a.enabled ? 'true' : 'false');
    sw.setAttribute('aria-label', 'Включить будильник ' + formatHM(a.hour, a.minute));
    sw.appendChild(document.createElement('span'));
    sw.addEventListener('click', function (event) {
      event.stopPropagation();
      toggleAlarm(a.id);
    });
    row.appendChild(sw);

    row.addEventListener('click', function () {
      openEditor(a.id);
    });
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

  function renderSettings() {
    var groups = moreOverlay.querySelectorAll('.chips');
    for (var g = 0; g < groups.length; g++) {
      var key = groups[g].getAttribute('data-key');
      var chips = groups[g].querySelectorAll('.chip');
      for (var i = 0; i < chips.length; i++) {
        chips[i].classList.toggle('on', Number(chips[i].getAttribute('data-v')) === state[key]);
      }
    }
    $('vibrateSwitch').setAttribute('aria-checked', state.vibrate ? 'true' : 'false');
    $('challengeSwitch').setAttribute('aria-checked', state.challenge ? 'true' : 'false');
    $('updateSwitch').setAttribute('aria-checked', state.updateCheck ? 'true' : 'false');
    var challengeNumbers = $('challengeNumbersSet');
    if (challengeNumbers) challengeNumbers.hidden = !state.challenge;
    var vr = $('volumeRange');
    if (document.activeElement !== vr) vr.value = state.volume;
    $('volumeValue').textContent = state.volume ? state.volume + '%' : 'Как в системе';
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
    state.soundTitle = title;
    state.soundUri = uri;
    saveState();
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
    if (state.soundUri === uri) { state.soundUri = ''; state.soundTitle = 'По умолчанию'; }
    saveState();
    loadSoundList();
    render();
    pushSettings();
    buildSoundList();
  }

  function selectSound(title, uri) {
    state.soundTitle = title;
    state.soundUri = uri || '';
    saveState();
    render();
    pushSettings();
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
    var sheetOpen = moreOverlay.classList.contains('open') || soundOverlay.classList.contains('open')
      || alarmOverlay.classList.contains('open') || disableOverlay.classList.contains('open')
      || permOverlay.classList.contains('open') || updateOverlay.classList.contains('open');
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
    if (overlay === alarmOverlay) setTimeout(function () { timeInput.blur(); labelInput.blur(); }, 250);
    if (overlay === moreOverlay && document.activeElement) document.activeElement.blur();
    if (overlay === alarmOverlay) editing = false;
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
    row.className = 'sound' + (sound.uri === (state.soundUri || '') ? ' selected' : '');
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
  function openEditor(id) {
    var isNew = id < 0;
    if (isNew && state.alarms.length >= MAX_ALARMS) {
      toast('Не больше ' + MAX_ALARMS + ' будильников');
      return;
    }
    editing = true;
    editingId = isNew ? nextId() : id;
    var a = isNew
      ? { id: editingId, enabled: true, hour: 7, minute: 30, repeat: true, days: ALL_DAYS, label: '' }
      : alarmById(id);
    if (!a) { editing = false; return; }

    if (isNew) {
      /* Новому будильнику подсказываем время: через 8 часов от текущего или 07:30. */
      var suggest = new Date(Date.now() + 8 * 3600 * 1000);
      a.hour = suggest.getHours();
      a.minute = suggest.getMinutes();
    }

    alarmTitle.textContent = isNew ? 'Новый будильник' : 'Будильник';
    timeInput.value = formatHM(a.hour, a.minute);
    repeatSwitch.setAttribute('aria-checked', a.repeat ? 'true' : 'false');
    updateDaysRow(a.days);
    labelInput.value = a.label || '';
    deleteAlarmBtn.hidden = isNew;
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
      label: labelInput.value.slice(0, 24)
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
    var idx = -1;
    for (var i = 0; i < state.alarms.length; i++) {
      if (state.alarms[i].id === editingId) { idx = i; break; }
    }
    if (idx >= 0) {
      state.alarms.splice(idx, 1);
      saveState();
      render();
      pushState();
      toast('Будильник удалён');
    }
    editing = false;
    closeSheet(alarmOverlay);
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
  var chipGroups = moreOverlay.querySelectorAll('.chips');
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
      if (key === 'challenge') renderSettings();
    });
  }

  $('vibrateSwitch').addEventListener('click', function () {
    state.vibrate = !state.vibrate;
    saveState();
    render();
    pushSettings();
  });

  $('challengeSwitch').addEventListener('click', function () {
    state.challenge = !state.challenge;
    saveState();
    render();
    pushSettings();
    toast(state.challenge ? 'Для отключения нужно решить пример' : 'Пример для отключения выключен');
  });

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
    toast('Предложим при следующей проверке');
  });

  $('updateNow').addEventListener('click', function () {
    closeSheet(updateOverlay);
    if (bridge && typeof bridge.openUpdatePage === 'function') {
      try { bridge.openUpdatePage(); } catch (e) { toast('Не удалось открыть браузер'); }
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

  $('volumeRange').addEventListener('input', function () {
    state.volume = clamp(parseInt(this.value, 10) || 0, 0, 100);
    $('volumeValue').textContent = state.volume ? state.volume + '%' : 'Как в системе';
  });
  $('volumeRange').addEventListener('change', function () { saveState(); render(); pushSettings(); });

  /* ————— События ————— */
  createBtn.addEventListener('click', function () { openEditor(-1); });

  timeBtn.addEventListener('click', function () {
    /* Крупные часы: редактируем ближайший включённый, либо открываем создание. */
    var nearest = nearestEnabled();
    openEditor(nearest.alarm ? nearest.alarm.id : -1);
  });

  gearBtn.addEventListener('click', function () { openSheet(moreOverlay); });

  $('soundRow').addEventListener('click', function () { openSheet(soundOverlay); });

  $('addFileBtn').addEventListener('click', function () {
    if (bridge && typeof bridge.pickSoundFile === 'function') {
      try { bridge.pickSoundFile(); } catch (e) { toast('Не удалось открыть выбор файла'); }
    } else {
      toast('Выбор файла доступен в приложении');
    }
  });

  $('testBtn').addEventListener('click', function () {
    if (bridge && typeof bridge.testAlarm === 'function') {
      try { bridge.testAlarm(); } catch (e) { toast('Не удалось запустить'); }
    } else {
      playPreview(state.soundUri || '');
      toast('Пробный звук');
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
