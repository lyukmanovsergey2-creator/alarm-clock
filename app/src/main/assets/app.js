(function () {
  'use strict';

  var STORAGE_KEY = 'cleanAlarm.state.v1';
  var DAY_NAMES = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];
  var ALL_DAYS = 127;
  var WEEKDAYS = 31;   /* пн–пт */
  var WEEKEND = 96;    /* сб, вс */

  var DEFAULT_STATE = {
    hour: 7,
    minute: 30,
    enabled: false,
    repeat: true,
    days: ALL_DAYS,
    soundUri: '',
    soundTitle: 'По умолчанию',
    customSounds: [],
    label: '',
    snoozeMinutes: 10,
    maxSnoozes: 0,
    fadeSeconds: 0,
    volume: 0,
    vibrate: true,
    autoStopMinutes: 10,
    challenge: false,
    challengeNumbers: 2,
    theme: 0,
    snoozeUntil: 0,
    skipBefore: 0,
    savedAt: 0
  };

  var $ = function (id) { return document.getElementById(id); };
  var app = $('app');
  var card = $('card');
  var timeBtn = $('timeBtn');
  var powerBtn = $('powerBtn');
  var gearBtn = $('gearBtn');
  var statusText = $('statusText');
  var nowText = $('nowText');
  var nextText = $('nextText');
  var soundValue = $('soundValue');
  var repeatValue = $('repeatValue');
  var repeatSwitch = $('repeatSwitch');
  var snoozeBtn = $('snoozeBtn');
  var snoozeSub = $('snoozeSub');
  var disableBtn = $('disableBtn');
  var toastEl = $('toast');
  var daysRow = $('daysRow');

  var timeOverlay = $('timeOverlay');
  var timeInput = $('timeInput');
  var timeSave = $('timeSave');
  var soundOverlay = $('soundOverlay');
  var soundList = $('soundList');
  var moreOverlay = $('moreOverlay');
  var disableOverlay = $('disableOverlay');

  var bridge = window.NativeAlarm || null;
  var state = loadState();
  var sounds = [{ title: 'По умолчанию', uri: '' }];
  var previewTimer = 0;
  var audioCtx = null;
  var toastTimer = 0;

  /* ————— Утилиты ————— */
  function clamp(n, min, max) { return Math.min(max, Math.max(min, n)); }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function formatHM(h, m) { return pad(h) + ':' + pad(m); }
  function formatDateHM(ts) { var d = new Date(ts); return formatHM(d.getHours(), d.getMinutes()); }
  function snoozeActive() { return state.snoozeUntil > Date.now(); }
  function num(v, def) { v = Number(v); return isFinite(v) ? v : def; }

  /* ————— Состояние (localStorage) ————— */
  function loadState() {
    var result = {};
    var key;
    for (key in DEFAULT_STATE) {
      if (Object.prototype.hasOwnProperty.call(DEFAULT_STATE, key)) {
        result[key] = DEFAULT_STATE[key];
      }
    }
    result.customSounds = [];
    try {
      var raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return result;
      var s = JSON.parse(raw);
      if (!s || typeof s !== 'object') return result;

      result.hour = clamp(parseInt(num(s.hour, 7), 10), 0, 23);
      result.minute = clamp(parseInt(num(s.minute, 30), 10), 0, 59);
      result.enabled = !!s.enabled;
      result.repeat = s.repeat !== false;
      var days = parseInt(num(s.days, ALL_DAYS), 10) & ALL_DAYS;
      result.days = days || ALL_DAYS;
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
      result.label = typeof s.label === 'string' ? s.label.slice(0, 24) : '';
      result.snoozeMinutes = clamp(parseInt(num(s.snoozeMinutes, 10), 10), 1, 120);
      result.maxSnoozes = clamp(parseInt(num(s.maxSnoozes, 0), 10), 0, 20);
      result.fadeSeconds = clamp(parseInt(num(s.fadeSeconds, 0), 10), 0, 300);
      result.volume = clamp(parseInt(num(s.volume, 0), 10), 0, 100);
      result.vibrate = s.vibrate !== false;
      result.autoStopMinutes = clamp(parseInt(num(s.autoStopMinutes, 10), 10), 0, 120);
      result.challenge = !!s.challenge;
      result.challengeNumbers = clamp(parseInt(num(s.challengeNumbers, 2), 10), 2, 3);
      result.theme = num(s.theme, 0) === 1 ? 1 : 0;
      result.snoozeUntil = Math.max(0, num(s.snoozeUntil, 0));
      result.skipBefore = Math.max(0, num(s.skipBefore, 0));
      result.savedAt = num(s.savedAt, 0);
    } catch (e) {
      /* повреждённые данные — значения по умолчанию */
    }
    return result;
  }

  function saveState() {
    state.savedAt = Date.now();
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch (e) { /* хранилище недоступно */ }
  }

  /* ————— Расчёт ближайшего срабатывания (тот же алгоритм, что в NextAlarm.java) ————— */
  function nextOccurrence(from) {
    var now = from === undefined ? Date.now() : from;
    var after = Math.max(now, state.skipBefore || 0);
    var mask = (state.days & ALL_DAYS) || ALL_DAYS;
    for (var offset = 0; offset <= 8; offset++) {
      var d = new Date(after);
      d.setDate(d.getDate() + offset);
      d.setHours(state.hour, state.minute, 0, 0);
      if (d.getTime() <= after) continue;
      var bit = 1 << ((d.getDay() + 6) % 7); /* пн = бит 0 */
      if (!state.repeat || (mask & bit)) return d.getTime();
    }
    return after + 86400000;
  }

  function daysSummary() {
    var m = state.days & ALL_DAYS;
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
  function statusString() {
    if (snoozeActive()) return 'Отложен до ' + formatDateHM(state.snoozeUntil);
    var name = state.label || 'Будильник';
    if (state.enabled) {
      var parts = [state.label || 'Будильник включён'];
      if (state.repeat) {
        parts.push(daysSummary() === 'Каждый день' ? 'ежедневно' : daysSummary());
      }
      if (state.repeat && state.skipBefore > Date.now()) parts.push('пропуск');
      return parts.join(' · ');
    }
    return state.label ? name + ' · выключен' : 'Будильник выключен';
  }

  function render() {
    var active = state.enabled || snoozeActive();
    app.classList.toggle('active', active);
    card.classList.toggle('norepeat', !state.repeat);

    timeBtn.textContent = formatHM(state.hour, state.minute);
    powerBtn.classList.toggle('on', state.enabled);
    powerBtn.setAttribute('aria-pressed', state.enabled ? 'true' : 'false');
    statusText.textContent = statusString();

    var d = new Date();
    nowText.textContent = formatHM(d.getHours(), d.getMinutes());
    if (snoozeActive()) {
      nextText.textContent = 'отложен на ' + state.snoozeMinutes + ' ' + minutesWord(state.snoozeMinutes);
    } else if (state.enabled) {
      nextText.textContent = humanDuration(nextOccurrence() - Date.now());
    } else {
      nextText.textContent = 'нажмите, чтобы настроить';
    }

    soundValue.textContent = state.soundTitle || 'По умолчанию';
    repeatValue.textContent = state.repeat ? daysSummary() : 'Один раз';
    repeatSwitch.setAttribute('aria-checked', state.repeat ? 'true' : 'false');
    snoozeSub.textContent = 'Отложить на ' + state.snoozeMinutes + ' ' + minutesWord(state.snoozeMinutes);

    var dayBtns = daysRow.querySelectorAll('.day');
    for (var i = 0; i < dayBtns.length; i++) {
      dayBtns[i].classList.toggle('on', !!(state.days & (1 << i)));
    }
    renderSettings();
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
    var challengeNumbers = $('challengeNumbersSet');
    if (challengeNumbers) challengeNumbers.hidden = !state.challenge;
    var vr = $('volumeRange');
    if (document.activeElement !== vr) vr.value = state.volume;
    $('volumeValue').textContent = state.volume ? state.volume + '%' : 'Как в системе';
    if (document.activeElement !== $('labelInput')) $('labelInput').value = state.label;

    var canSkip = state.enabled && state.repeat && !snoozeActive();
    var skipActive = state.skipBefore > Date.now();
    var skipBtn = $('skipBtn');
    skipBtn.disabled = !(canSkip || skipActive);
    skipBtn.textContent = skipActive ? 'Вернуть ближайший' : 'Пропустить ближайший';
    var hint = '';
    if (skipActive) hint = 'Ближайший звонок пропущен. Следующий: ' + describeTs(nextOccurrence()) + '.';
    else if (canSkip) hint = 'Ближайший звонок: ' + describeTs(nextOccurrence()) + '.';
    else hint = 'Доступно, когда включён повторяющийся будильник.';
    $('skipHint').textContent = hint;
  }

  function describeTs(ts) {
    var d = new Date(ts);
    var today = new Date();
    today.setHours(0, 0, 0, 0);
    var diff = Math.round((new Date(ts).setHours(0, 0, 0, 0) - today.getTime()) / 86400000);
    var day = diff === 0 ? 'сегодня' : (diff === 1 ? 'завтра' : DAY_NAMES[(d.getDay() + 6) % 7].toLowerCase() + ', ' + d.getDate() + '.' + pad(d.getMonth() + 1));
    return day + ' в ' + formatHM(d.getHours(), d.getMinutes());
  }

  /* ————— Связь с Android ————— */
  function configObject() {
    return {
      enabled: state.enabled,
      hour: state.hour,
      minute: state.minute,
      repeat: state.repeat,
      days: state.days,
      skipBefore: state.skipBefore,
      soundUri: state.soundUri || '',
      label: state.label || '',
      snoozeMinutes: state.snoozeMinutes,
      maxSnoozes: state.maxSnoozes,
      fadeSeconds: state.fadeSeconds,
      volume: state.volume,
      vibrate: state.vibrate,
      autoStopMinutes: state.autoStopMinutes,
      challenge: state.challenge,
      challengeNumbers: state.challengeNumbers
    };
  }

  /* Меняется время, дни, повтор или вкл/выкл — переустанавливаем будильник. */
  function pushSchedule() {
    if (!bridge || typeof bridge.applyConfig !== 'function') return;
    try { bridge.applyConfig(JSON.stringify(configObject())); } catch (e) { /* нет моста */ }
  }

  /* Остальные настройки не трогают расписание и отложенный звонок. */
  function pushSettings() {
    if (!bridge || typeof bridge.saveSettings !== 'function') return;
    try { bridge.saveSettings(JSON.stringify(configObject())); } catch (e) { /* нет моста */ }
  }

  function syncFromNative() {
    if (!bridge || typeof bridge.getState !== 'function') return;
    var ns;
    try { ns = JSON.parse(bridge.getState()); } catch (e) { return; }
    if (!ns) return;

    var now = Date.now();
    var changed = false;

    if (ns.snoozeUntil > now) {
      if (Math.abs(state.snoozeUntil - ns.snoozeUntil) > 1500) {
        state.snoozeUntil = ns.snoozeUntil;
        changed = true;
      }
    } else if (state.snoozeUntil && state.snoozeUntil <= now) {
      state.snoozeUntil = 0;
      changed = true;
    }

    if (state.enabled && !ns.enabled) {
      if (ns.lastFiredAt > 0 && ns.lastFiredAt > state.savedAt) {
        /* Одноразовый прозвенел или будильник отключили на экране звонка. */
        state.enabled = false;
        state.skipBefore = 0;
        if (!(ns.snoozeUntil > now)) state.snoozeUntil = 0;
        changed = true;
      } else if (!(ns.snoozeUntil > now)) {
        /* Нативные данные потеряны — восстанавливаем расписание. */
        pushSchedule();
      }
    }

    if (changed) { saveState(); render(); }
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
      for (var i = 0; i < sounds.length; i++) {
        if (sounds[i].uri === state.soundUri) { state.soundTitle = sounds[i].title; break; }
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
      || timeOverlay.classList.contains('open');
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
    if (overlay === timeOverlay) setTimeout(function () { timeInput.blur(); }, 250);
    if (overlay === moreOverlay && document.activeElement) document.activeElement.blur();
    return wasOpen;
  }

  function closeTopSheet() {
    var order = [moreOverlay, soundOverlay, timeOverlay, disableOverlay];
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

  /* ————— Действия ————— */
  function enableAlarm(enabled) {
    state.enabled = enabled;
    state.snoozeUntil = 0;
    state.skipBefore = 0;
    saveState();
    render();
    pushSchedule();
  }

  function setTime(hour, minute) {
    state.hour = hour;
    state.minute = minute;
    state.enabled = true;
    state.snoozeUntil = 0;
    state.skipBefore = 0;
    saveState();
    render();
    pushSchedule();
    toast('Будильник на ' + formatHM(hour, minute));
  }

  function scheduleChanged() {
    state.snoozeUntil = 0;
    state.skipBefore = 0;
    saveState();
    render();
    pushSchedule();
  }

  function settingChanged() {
    saveState();
    render();
    pushSettings();
  }

  /* ————— События ————— */
  timeBtn.addEventListener('click', function () {
    timeInput.value = formatHM(state.hour, state.minute);
    openSheet(timeOverlay);
  });

  timeSave.addEventListener('click', function () {
    var parts = (timeInput.value || '').split(':');
    if (parts.length < 2) { toast('Укажите время'); return; }
    var hour = parseInt(parts[0], 10);
    var minute = parseInt(parts[1], 10);
    if (!isFinite(hour) || !isFinite(minute)) { toast('Укажите время'); return; }
    setTime(clamp(hour, 0, 23), clamp(minute, 0, 59));
    closeSheet(timeOverlay);
  });

  powerBtn.addEventListener('click', function () {
    enableAlarm(!state.enabled);
    toast(state.enabled ? 'Будильник включён' : 'Будильник отключён');
  });

  gearBtn.addEventListener('click', function () { openSheet(moreOverlay); });

  repeatSwitch.addEventListener('click', function () {
    state.repeat = !state.repeat;
    scheduleChanged();
    toast(state.repeat ? 'Повтор: ' + daysSummary().toLowerCase() : 'Без повтора');
  });

  daysRow.addEventListener('click', function (event) {
    var btn = event.target.closest ? event.target.closest('.day') : null;
    if (!btn) return;
    var bit = 1 << parseInt(btn.getAttribute('data-day'), 10);
    var next = state.days ^ bit;
    if ((next & ALL_DAYS) === 0) { toast('Выберите хотя бы один день'); return; }
    state.days = next;
    scheduleChanged();
  });

  $('soundRow').addEventListener('click', function () { openSheet(soundOverlay); });

  $('addFileBtn').addEventListener('click', function () {
    if (bridge && typeof bridge.pickSoundFile === 'function') {
      try { bridge.pickSoundFile(); } catch (e) { toast('Не удалось открыть выбор файла'); }
    } else {
      toast('Выбор файла доступен в приложении');
    }
  });

  snoozeBtn.addEventListener('click', function () {
    var minutes = state.snoozeMinutes;
    state.snoozeUntil = Date.now() + minutes * 60000;
    saveState();
    render();
    if (bridge) {
      try {
        if (typeof bridge.stopRinging === 'function') bridge.stopRinging();
        if (typeof bridge.snooze === 'function') bridge.snooze(minutes, state.soundUri || '');
      } catch (e) { /* ignore */ }
    }
    toast('Отложено на ' + minutes + ' ' + minutesWord(minutes));
  });

  /* Отключить: выбор «на сутки или совсем». */
  function openDisableSheet() {
    var canSkip = state.enabled && state.repeat;
    $('disableDayBtn').hidden = !canSkip;
    openSheet(disableOverlay);
  }

  disableBtn.addEventListener('click', function () {
    if (!state.enabled && !snoozeActive()) {
      if (bridge) {
        try {
          if (typeof bridge.stopRinging === 'function') bridge.stopRinging();
          bridge.cancelAlarm();
        } catch (e) { /* ignore */ }
      }
      toast('Будильник уже выключен');
      return;
    }
    openDisableSheet();
  });

  $('disableDayBtn').addEventListener('click', function () {
    var now = Date.now();
    var skipped = snoozeActive() || !state.enabled ? 0 : nextOccurrence(now);
    if (skipped) {
      state.skipBefore = skipped;
      toast('Пропущен звонок ' + describeTs(skipped));
    }
    if (snoozeActive()) state.snoozeUntil = 0;
    saveState();
    render();
    pushSchedule();
    closeSheet(disableOverlay);
  });

  $('disableForeverBtn').addEventListener('click', function () {
    state.enabled = false;
    state.snoozeUntil = 0;
    state.skipBefore = 0;
    saveState();
    render();
    if (bridge) {
      try {
        if (typeof bridge.stopRinging === 'function') bridge.stopRinging();
      } catch (e) { /* ignore */ }
    }
    pushSchedule();
    toast('Будильник отключён полностью');
    closeSheet(disableOverlay);
  });

  /* Настройки в шторке */
  var chipGroups = moreOverlay.querySelectorAll('.chips');
  for (var g = 0; g < chipGroups.length; g++) {
    chipGroups[g].addEventListener('click', function (event) {
      var chip = event.target.closest ? event.target.closest('.chip') : null;
      if (!chip) return;
      var key = event.currentTarget.getAttribute('data-key');
      state[key] = Number(chip.getAttribute('data-v'));
      settingChanged();
      if (key === 'theme') applyTheme();
      if (key === 'challenge') renderSettings();
    });
  }

  $('vibrateSwitch').addEventListener('click', function () {
    state.vibrate = !state.vibrate;
    settingChanged();
  });

  $('challengeSwitch').addEventListener('click', function () {
    state.challenge = !state.challenge;
    settingChanged();
    toast(state.challenge ? 'Для отключения нужно решить пример' : 'Пример для отключения выключен');
  });

  $('volumeRange').addEventListener('input', function () {
    state.volume = clamp(parseInt(this.value, 10) || 0, 0, 100);
    $('volumeValue').textContent = state.volume ? state.volume + '%' : 'Как в системе';
  });
  $('volumeRange').addEventListener('change', function () { settingChanged(); });

  $('labelInput').addEventListener('input', function () {
    state.label = this.value.slice(0, 24);
    saveState();
    statusText.textContent = statusString();
  });
  $('labelInput').addEventListener('change', function () { settingChanged(); });
  $('labelInput').addEventListener('blur', function () { settingChanged(); });

  $('skipBtn').addEventListener('click', function () {
    if (state.skipBefore > Date.now()) {
      state.skipBefore = 0;
      toast('Ближайший звонок возвращён');
    } else if (state.enabled && state.repeat) {
      state.skipBefore = nextOccurrence();
      toast('Пропускаем: ' + describeTs(nextOccurrence()));
    }
    saveState();
    render();
    pushSchedule();
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
      closeSheet(target === 'time' ? timeOverlay
        : target === 'more' ? moreOverlay
        : target === 'disable' ? disableOverlay
        : soundOverlay);
    });
  }

  [timeOverlay, soundOverlay, moreOverlay, disableOverlay].forEach(function (overlay) {
    overlay.addEventListener('click', function (event) {
      if (event.target === overlay) closeSheet(overlay);
    });
  });

  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') closeTopSheet();
  });

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) { render(); syncFromNative(); } else { stopPreview(); }
  });

  window.__nativeResume = function () {
    render();
    syncFromNative();
    if (moreOverlay.classList.contains('open')) refreshHealth();
  };

  /* ————— Запуск ————— */
  loadSoundList();
  applyTheme();
  syncFromNative();
  render();
  pushSettings();
  setInterval(render, 1000);
  setTimeout(syncFromNative, 600);
})();
