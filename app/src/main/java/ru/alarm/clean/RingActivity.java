package ru.alarm.clean;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class RingActivity extends Activity {

    /* Палитра: подменяется в onCreate, если в настройках выбрана светлая тема. */
    private static int BG = Color.parseColor("#0B0B0E");
    private static int TEXT = Color.parseColor("#F4F3EF");
    private static int MUTED = Color.parseColor("#8B8B96");
    private static int ACCENT = Color.parseColor("#D8FF63");
    private static int DANGER = Color.parseColor("#FF7B72");
    /* Тёмные чернила на акцентных кнопках, фон и обводка клавиш «Отключить»/клавиатуры. */
    private static int ON_ACCENT = Color.parseColor("#0B0B0E");
    private static int KEY_BG = Color.parseColor("#1A1A21");
    private static int DISABLE_BG = Color.parseColor("#1A1A21");
    private static int DISABLE_STROKE = Color.parseColor("#33FF7B72");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView timeView;
    private TextView dateView;
    private View mainView;
    private final Random random = new Random();
    private int answer;
    /** Какой будильник звонит (id). */
    private int alarmId = 0;
    /** После решённого примера — показать выбор «на сутки или совсем», а не отключать сразу. */
    private boolean challengeThenChoose;
    private StringBuilder typed = new StringBuilder();
    private TextView answerView;
    private TextView questionView;
    private TextView challengeHint;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEEE, d MMMM", new Locale("ru"));

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!AlarmService.running) {
                finish();
                return;
            }
            updateClock();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* Какой будильник звонит: из extra, иначе из сервиса (при дозвоне/шторке). */
        int fromIntent = getIntent() != null
                ? getIntent().getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1) : -1;
        alarmId = fromIntent >= 0 ? fromIntent : AlarmService.currentAlarmId;
        if (alarmId < 0) {
            alarmId = 0;
        }

        applyPalette(AlarmScheduler.prefs(this).getBoolean("lightTheme", false));

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setVolumeControlStream(AudioManager.STREAM_ALARM);
        setFinishOnTouchOutside(false);

        mainView = buildInterface();
        setContentView(mainView);
        updateClock();
        AlarmScheduler.logLine(this, "Экран звонка открыт (будильник " + alarmId + ")");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        /* Экран одиночный (singleInstance): если пришёл другой будильник —
           обновляем id и подпись, заново показывая главный вид. */
        if (intent != null && intent.hasExtra(AlarmScheduler.EXTRA_ALARM_ID)) {
            int id = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId);
            if (id != alarmId) {
                alarmId = id;
                mainView = buildInterface();
                setContentView(mainView);
                updateClock();
            }
        }
    }

    private void applyPalette(boolean light) {
        if (light) {
            BG = Color.parseColor("#F4F4F0");
            TEXT = Color.parseColor("#1C1D22");
            MUTED = Color.parseColor("#66676F");
            ACCENT = Color.parseColor("#B5E332");
            DANGER = Color.parseColor("#C94B42");
        } else {
            BG = Color.parseColor("#0B0B0E");
            TEXT = Color.parseColor("#F4F3EF");
            MUTED = Color.parseColor("#8B8B96");
            ACCENT = Color.parseColor("#D8FF63");
            DANGER = Color.parseColor("#FF7B72");
        }
        ON_ACCENT = Color.parseColor("#17181C");
        KEY_BG = light ? Color.parseColor("#E7E8E1") : Color.parseColor("#1A1A21");
        DISABLE_BG = light ? Color.parseColor("#FDFCF8") : Color.parseColor("#1A1A21");
        DISABLE_STROKE = light ? Color.parseColor("#4DC94B42") : Color.parseColor("#33FF7B72");
    }

    private View buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(22), dp(18), dp(22), dp(18));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER);
        top.setWeightSum(1f);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f));

        ImageView bell = new ImageView(this);
        bell.setImageResource(R.drawable.ic_notification);
        bell.setColorFilter(ACCENT);
        LinearLayout.LayoutParams bellParams =
                new LinearLayout.LayoutParams(dp(28), dp(28));
        bellParams.bottomMargin = dp(14);
        top.addView(bell, bellParams);

        TextView label = new TextView(this);
        String custom = AlarmScheduler.prefs(this).getString(AlarmScheduler.key(alarmId, "label"), "");
        label.setText(custom.isEmpty() ? "БУДИЛЬНИК" : custom.toUpperCase(new Locale("ru")));
        label.setMaxLines(2);
        label.setTextSize(12);
        label.setLetterSpacing(0.22f);
        label.setTextColor(MUTED);
        label.setGravity(Gravity.CENTER);
        top.addView(label);

        timeView = new TextView(this);
        timeView.setTextColor(TEXT);
        timeView.setTextSize(78);
        timeView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        timeView.setLetterSpacing(-0.045f);
        timeView.setIncludeFontPadding(false);
        timeView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeParams.topMargin = dp(10);
        top.addView(timeView, timeParams);

        dateView = new TextView(this);
        dateView.setTextSize(14);
        dateView.setTextColor(MUTED);
        dateView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateParams.topMargin = dp(10);
        top.addView(dateView, dateParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(0, dp(10), 0, 0);
        root.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        SharedPreferences cfg = AlarmScheduler.prefs(this);
        int minutes = Math.max(1, Math.min(120, AlarmScheduler.alarmSnoozeMinutes(cfg, alarmId)));
        int max = AlarmScheduler.alarmMaxSnoozes(cfg, alarmId);
        int left = max - cfg.getInt(AlarmScheduler.key(alarmId, "snoozeCount"), 0);
        boolean limited = max > 0 && !AlarmService.testing;
        boolean snoozeAllowed = !limited || left > 0;
        String snoozeSub = "Отложить на " + minutes + " " + minutesWord(minutes);
        if (limited) {
            snoozeSub = snoozeAllowed
                    ? snoozeSub + " · осталось " + left
                    : "Лимит откладываний исчерпан";
        }
        View snooze = buildAction("Остановить", snoozeSub,
                ACCENT, ON_ACCENT, 0, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        snooze();
                    }
                });
        if (!snoozeAllowed) {
            snooze.setAlpha(0.35f);
            snooze.setClickable(false);
        }
        LinearLayout.LayoutParams snoozeParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        snoozeParams.rightMargin = dp(6);
        bottom.addView(snooze, snoozeParams);

        View disable = buildAction("Отключить", "На сутки или совсем",
                DISABLE_BG, DANGER,
                DISABLE_STROKE, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        disable();
                    }
                });
        LinearLayout.LayoutParams disableParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        disableParams.leftMargin = dp(6);
        bottom.addView(disable, disableParams);

        return root;
    }

    private View buildAction(String title, String subtitle, int background, int textColor,
                             int strokeColor, View.OnClickListener listener) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setPadding(dp(12), dp(14), dp(12), dp(14));
        container.setClickable(true);
        container.setFocusable(true);
        container.setContentDescription(title + ", " + subtitle);

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(28));
        if (strokeColor != 0) {
            shape.setStroke(dp(1), strokeColor);
        }
        container.setBackground(shape);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(21);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setTextColor(textColor);
        titleView.setGravity(Gravity.CENTER);
        container.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(11.5f);
        subtitleView.setTextColor(textColor);
        subtitleView.setAlpha(0.62f);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setMaxWidth(dp(150));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(6);
        container.addView(subtitleView, subtitleParams);

        container.setOnClickListener(listener);
        return container;
    }

    private void updateClock() {
        Date now = new Date();
        if (timeView != null) {
            timeView.setText(timeFormat.format(now));
        }
        if (dateView != null) {
            String text = dateFormat.format(now);
            if (!text.isEmpty()) {
                text = text.substring(0, 1).toUpperCase(new Locale("ru")) + text.substring(1);
            }
            dateView.setText(text);
        }
    }

    private static String minutesWord(int n) {
        int mod100 = n % 100;
        int mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return "минут";
        if (mod10 == 1) return "минуту";
        if (mod10 >= 2 && mod10 <= 4) return "минуты";
        return "минут";
    }

    /* ————— Пример для отключения ————— */
    private void newProblem() {
        int terms = 2;
        try {
            terms = Math.max(2, Math.min(4, AlarmScheduler
                    .alarmChallengeNumbers(AlarmScheduler.prefs(this), alarmId)));
        } catch (Exception ignored) {
        }
        String text;
        if (terms == 4) {
            int type = random.nextInt(4);
            if (type == 0) {
                /* a + b − c + d */
                int a = 20 + random.nextInt(50);
                int b = 10 + random.nextInt(40);
                int c = 5 + random.nextInt(20);
                int d = 10 + random.nextInt(50);
                answer = a + b - c + d;
                text = a + "+" + b + "−" + c + "+" + d;
            } else if (type == 1) {
                /* a − b + c − d */
                int a = 80 + random.nextInt(60);
                int b = 10 + random.nextInt(40);
                int c = 10 + random.nextInt(50);
                int d = 10 + random.nextInt(40);
                answer = a - b + c - d;
                text = a + "−" + b + "+" + c + "−" + d;
            } else if (type == 2) {
                /* a × b + c − d */
                int a = 2 + random.nextInt(5);
                int b = 3 + random.nextInt(7);
                int c = 5 + random.nextInt(25);
                int d = 5 + random.nextInt(20);
                answer = a * b + c - d;
                text = a + "×" + b + "+" + c + "−" + d;
            } else {
                /* a + b × c − d */
                int a = 10 + random.nextInt(40);
                int b = 2 + random.nextInt(4);
                int c = 3 + random.nextInt(8);
                int d = 5 + random.nextInt(25);
                answer = a + b * c - d;
                text = a + "+" + b + "×" + c + "−" + d;
            }
        } else if (terms == 3) {
            int type = random.nextInt(3);
            if (type == 0) {
                /* a + b − c */
                int a = 30 + random.nextInt(60);
                int b = 10 + random.nextInt(30);
                int c = 5 + random.nextInt(15);
                answer = a + b - c;
                text = a + "+" + b + "−" + c;
            } else if (type == 1) {
                /* a − b + c */
                int a = 60 + random.nextInt(30);
                int b = 10 + random.nextInt(30);
                int c = 10 + random.nextInt(40);
                answer = a - b + c;
                text = a + "−" + b + "+" + c;
            } else {
                /* a × b + c */
                int a = 2 + random.nextInt(5);
                int b = 3 + random.nextInt(6);
                int c = 5 + random.nextInt(20);
                answer = a * b + c;
                text = a + "×" + b + "+" + c;
            }
        } else {
            int type = random.nextInt(3);
            if (type == 0) {
                int a = 20 + random.nextInt(70);
                int b = 20 + random.nextInt(70);
                answer = a + b;
                text = a + "+" + b;
            } else if (type == 1) {
                int a = 60 + random.nextInt(40);
                int b = 15 + random.nextInt(40);
                answer = a - b;
                text = a + "−" + b;
            } else {
                int a = 3 + random.nextInt(7);
                int b = 12 + random.nextInt(8);
                answer = a * b;
                text = a + "×" + b;
            }
        }
        typed.setLength(0);
        String full = text + " = ?";
        if (questionView != null) {
            questionView.setText(full);
            fitQuestionSize(full);
        }
        if (answerView != null) answerView.setText("");
    }

    /* Подбор размера шрифта примера: берём крупнейший размер, при котором
       строка гарантированно влезает по ширине экрана. Никакого авторазмера:
       высота строки и её обрезка не зависят от позднего пересчёта — размер
       известен до отрисовки. */
    private void fitQuestionSize(String full) {
        float maxSp = 48f, minSp = 22f;
        questionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, maxSp);
        float textW = questionView.getPaint().measureText(full);
        if (textW <= 0) return;
        /* ширина экрана минус горизонтальные паддинги root и небольшой запас */
        int avail = getResources().getDisplayMetrics().widthPixels
                - dp(20) * 2 - dp(12);
        if (avail <= 0) return;
        float sp = Math.max(minSp, Math.min(maxSp, maxSp * avail / textW));
        questionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    /* Клавиша-контейнер с фиксированной высотой (компактная клавиатура) */
    private LinearLayout makeKeyRow(String[] keys) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(86));
        lp.setMargins(dp(5), dp(5), dp(5), dp(5));
        for (int c = 0; c < keys.length; c++) {
            final String key = keys[c];
            TextView button = new TextView(this);
            button.setText(key);
            button.setTextSize(28);
            button.setGravity(Gravity.CENTER);
            boolean ok = key.equals("✓");
            button.setTextColor(ok ? ON_ACCENT : TEXT);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(ok ? ACCENT : KEY_BG);
            shape.setCornerRadius(dp(20));
            button.setBackground(shape);
            button.setClickable(true);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onKey(key);
                }
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            bp.setMargins(dp(5), 0, dp(5), 0);
            button.setPadding(0, 0, 0, 0);
            line.addView(button, bp);
        }
        line.setLayoutParams(lp);
        return line;
    }

    private void showChallenge() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(10));

        challengeHint = new TextView(this);
        challengeHint.setText("Решите пример, чтобы отключить");
        challengeHint.setTextSize(13);
        challengeHint.setTextColor(MUTED);
        challengeHint.setGravity(Gravity.CENTER);
        root.addView(challengeHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* «Красная» зона — только для примера: занимает всё свободное место,
           пример (и вводимый ответ) строго в её центре; клавиатура — целиком ниже */
        LinearLayout red = new LinearLayout(this);
        red.setOrientation(LinearLayout.VERTICAL);
        red.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams redLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        redLp.topMargin = dp(8);
        root.addView(red, redLp);

        questionView = new TextView(this);
        questionView.setTextColor(TEXT);
        questionView.setSingleLine(true); /* переносов на другую строку не бывает */
        questionView.setGravity(Gravity.CENTER);
        questionView.setIncludeFontPadding(false);
        questionView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        red.addView(questionView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        answerView = new TextView(this);
        answerView.setTextSize(36);
        answerView.setTextColor(ACCENT);
        answerView.setGravity(Gravity.CENTER);
        answerView.setMinHeight(dp(52));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(6);
        red.addView(answerView, ap);

        /* Клавиатура целиком в нижней зоне: четыре ряда фиксированной высоты */
        LinearLayout blue = new LinearLayout(this);
        blue.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blueLp.topMargin = dp(6);
        root.addView(blue, blueLp);
        blue.addView(makeKeyRow(new String[]{"1", "2", "3"}));
        blue.addView(makeKeyRow(new String[]{"4", "5", "6"}));
        blue.addView(makeKeyRow(new String[]{"7", "8", "9"}));
        blue.addView(makeKeyRow(new String[]{"←", "0", "✓"}));

        TextView back = new TextView(this);
        back.setText("Назад");
        back.setTextSize(15);
        back.setTextColor(MUTED);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(12), dp(10), dp(12), dp(4));
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setContentView(mainView);
                updateClock();
            }
        });
        root.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        newProblem();
        setContentView(root);
    }

    private void onKey(String key) {
        if (key.equals("←")) {
            if (typed.length() > 0) typed.setLength(typed.length() - 1);
        } else if (key.equals("✓")) {
            checkAnswer();
            return;
        } else if (typed.length() < 4) {
            typed.append(key);
        }
        answerView.setText(typed.toString());
    }

    private void checkAnswer() {
        int value = -1;
        try {
            value = Integer.parseInt(typed.toString());
        } catch (NumberFormatException ignored) {
        }
        if (typed.length() > 0 && value == answer) {
            if (challengeThenChoose) {
                challengeThenChoose = false;
                showDisableChoice();
            } else {
                finishDisable();
            }
            return;
        }
        challengeHint.setText("Неверно, попробуйте новый пример");
        challengeHint.setTextColor(DANGER);
        newProblem();
    }

    private void snooze() {
        try {
            Intent intent = new Intent(this, AlarmService.class);
            intent.setAction(AlarmService.ACTION_SNOOZE);
            intent.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId);
            startService(intent);
        } catch (Exception ignored) {
        }
        finish();
    }

    private void disable() {
        boolean challenge = AlarmScheduler.alarmChallenge(AlarmScheduler.prefs(this), alarmId)
                && !AlarmService.testing;
        if (challenge) {
            /* Сначала решаем пример (доказываем, что не спим), потом выбираем способ. */
            challengeThenChoose = true;
            showChallenge();
            return;
        }
        showDisableChoice();
    }

    /** Выбор: отключить только сейчас (до следующего срабатывания) или полностью. */
    private void showDisableChoice() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(22), dp(18), dp(22), dp(18));

        TextView hint = new TextView(this);
        hint.setText("Отключить будильник");
        hint.setTextSize(17);
        hint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hint.setTextColor(TEXT);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint);

        SharedPreferences cfg = AlarmScheduler.prefs(this);
        boolean repeat = cfg.getBoolean(AlarmScheduler.key(alarmId, "repeat"), true);

        if (repeat) {
            View day = buildAction("На сутки",
                    "Отключить до завтра, потом как обычно",
                    KEY_BG, TEXT, 0, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            stopRingingOnly();
                        }
                    });
            LinearLayout.LayoutParams dayParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dayParams.topMargin = dp(20);
            root.addView(day, dayParams);
        }

        View forever = buildAction("Совсем",
                "Выключить, пока не включите снова",
                DISABLE_BG, DANGER, DISABLE_STROKE, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finishDisable();
                    }
                });
        LinearLayout.LayoutParams foreverParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        foreverParams.topMargin = dp(12);
        root.addView(forever, foreverParams);

        TextView back = new TextView(this);
        back.setText("Назад");
        back.setTextSize(15);
        back.setTextColor(MUTED);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(12), dp(16), dp(12), dp(4));
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setContentView(mainView);
                updateClock();
            }
        });
        root.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    /** Гасит звонок, расписание будильника не трогает. */
    private void stopRingingOnly() {
        try {
            AlarmScheduler.logLine(this, "будильник " + alarmId + " погашен («на сутки»), расписание сохранено");
            Intent intent = new Intent(this, AlarmService.class);
            intent.setAction(AlarmService.ACTION_SILENCE);
            startService(intent);
        } catch (Exception ignored) {
        }
        finish();
    }

    private void finishDisable() {
        /* Гарантированно выключаем этот будильник: enabled=false ДО остановки сервиса,
           чтобы опоздавшие доставки обоих каналов больше не звонили. */
        try {
            AlarmScheduler.disableForever(this, alarmId);
            AlarmScheduler.logLine(this, "Отключено кнопкой «Совсем» (будильник " + alarmId + ")");
        } catch (Exception ignored) {
        }
        try {
            Intent intent = new Intent(this, AlarmService.class);
            intent.setAction(AlarmService.ACTION_DISABLE);
            intent.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId);
            startService(intent);
        } catch (Exception ignored) {
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        /* Будильник нельзя случайно закрыть кнопкой «назад». */
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(ticker);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        super.onDestroy();
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
