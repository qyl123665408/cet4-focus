package com.cet4.focus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int INK = Color.rgb(29, 39, 34);
    private static final int MUTED = Color.rgb(100, 112, 104);
    private static final int GREEN = Color.rgb(42, 116, 79);
    private static final int GREEN_PALE = Color.rgb(230, 242, 234);
    private static final int ORANGE = Color.rgb(194, 106, 48);
    private static final int BG = Color.rgb(244, 247, 244);
    private static final int LINE = Color.rgb(222, 229, 223);
    private static final String RELEASE_URL = "https://github.com/qyl123665408/cet4-focus/releases/latest";
    private static final long UPDATE_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private StudyRepository repository;
    private SharedPreferences preferences;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private boolean checkingUpdates;
    private boolean downloadingReading;
    private String readingDownloadStatus = "";
    private JSONObject readingSources = new JSONObject();
    private android.window.OnBackInvokedCallback backCallback;

    private LinearLayout root;
    private LinearLayout header;
    private LinearLayout body;
    private LinearLayout bottomBar;
    private ScrollView scrollView;
    private TextView titleView;

    private JSONArray modules = new JSONArray();
    private JSONArray questionItems = new JSONArray();
    private JSONArray questionTypes = new JSONArray();
    private JSONArray examLinks = new JSONArray();
    private JSONArray vocabulary = new JSONArray();
    private JSONObject questionData = new JSONObject();
    private JSONObject activeCourse = new JSONObject();
    private String activeCoursePath = "";
    private String activeCourseTitle = "";
    private String page = "modules";
    private String selectedTopic = "全部主题";
    private String selectedVocabFilter = "全部";
    private String vocabQuery = "";
    private int vocabPage = 1;
    private int selectedQuestionCount = 10;
    private boolean shuffleQuestions = true;
    private boolean resetPracticeTypeOnOpen;
    private final Set<String> selectedTypes = new HashSet<>();
    private final Set<String> completedLessons = new HashSet<>();
    private final Set<String> wrongQuestionIds = new HashSet<>();
    private final Set<String> starredWords = new HashSet<>();
    private final Set<String> masteredWords = new HashSet<>();
    private int totalObjectiveAnswers;
    private int correctObjectiveAnswers;
    private int studyStreak;
    private String lastStudyDate = "";
    private JSONArray history = new JSONArray();

    private ArrayList<JSONObject> quizItems = new ArrayList<>();
    private String quizMode = "practice";
    private int quizIndex;
    private int quizScore;
    private int quizObjectiveCount;
    private boolean quizLocked;
    private Boolean quizAnswerCorrect;
    private String quizFeedback = "";
    private String quizExplanation = "";
    private String quizInputValue = "";
    private String selectedOption = "";
    private final Map<String, String> selectedMatches = new LinkedHashMap<>();
    private RadioGroup activeOptionGroup;
    private EditText activeAnswerInput;
    private final Map<String, Spinner> activeMatchSpinners = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(BG);
        int systemUi = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().setNavigationBarColor(BG);
            systemUi |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            getWindow().setNavigationBarColor(INK);
        }
        getWindow().getDecorView().setSystemUiVisibility(systemUi);
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::navigateBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        repository = new StudyRepository(this);
        preferences = getSharedPreferences("study-native-state-v1", MODE_PRIVATE);
        loadProgress();
        loadContent();
        textToSpeech = new TextToSpeech(this, status -> ttsReady = status == TextToSpeech.SUCCESS);
        createLayout();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (repository != null) checkUpdates(false);
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }

    @Override
    @android.annotation.SuppressLint("GestureBackNavigation") // API 33+ uses the callback registered in onCreate.
    public void onBackPressed() {
        navigateBack();
    }

    private void navigateBack() {
        if ("quiz".equals(page)) {
            page = "practice";
            render();
        } else if ("lesson".equals(page)) {
            page = "course";
            render();
        } else if ("course".equals(page)) {
            page = "modules";
            render();
        } else if (!"modules".equals(page)) {
            page = "modules";
            render();
        } else {
            finish();
        }
    }

    private void createLayout() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets systemBars = insets.getInsets(WindowInsets.Type.systemBars());
                top = systemBars.top;
                bottom = systemBars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });

        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(8), dp(12), dp(8));
        header.setBackgroundColor(BG);
        titleView = text("学习模块", 21, INK, true);
        header.addView(titleView, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button update = smallButton("更新", false, () -> checkUpdates(true));
        header.addView(update);
        Button more = smallButton("更多", false, this::showMoreMenu);
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        moreParams.leftMargin = dp(6);
        header.addView(more, moreParams);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(20));
        scrollView.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(Color.WHITE);
        bottomBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(bottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        setContentView(root);
    }

    private void loadContent() {
        try {
            JSONObject registry = repository.readJson("data/modules/index.json");
            modules = registry.optJSONArray("modules") == null ? new JSONArray() : registry.optJSONArray("modules");
            questionData = repository.readJson("data/questions.json");
            questionItems = questionData.optJSONArray("items") == null ? new JSONArray() : questionData.optJSONArray("items");
            questionTypes = questionData.optJSONArray("types") == null ? new JSONArray() : questionData.optJSONArray("types");
            examLinks = questionData.optJSONArray("examLinks") == null ? new JSONArray() : questionData.optJSONArray("examLinks");
            vocabulary = repository.readArray("data/vocab.json");
            try {
                readingSources = repository.readJson("data/reading-sources.json");
                JSONArray downloaded = repository.savedReadingQuestions();
                for (int i = 0; i < downloaded.length(); i++) questionItems.put(downloaded.getJSONObject(i));
            } catch (Exception error) {
                readingSources = new JSONObject();
                toast("阅读资料目录暂时无法读取，可检查更新后重试。");
            }
        } catch (Exception error) {
            toast("课程文件暂时无法读取：" + error.getMessage());
        }
    }

    private void loadProgress() {
        selectedTypes.addAll(preferences.getStringSet("selectedTypes", new HashSet<>()));
        completedLessons.addAll(preferences.getStringSet("completedLessons", new HashSet<>()));
        wrongQuestionIds.addAll(preferences.getStringSet("wrongQuestions", new HashSet<>()));
        starredWords.addAll(preferences.getStringSet("starredWords", new HashSet<>()));
        masteredWords.addAll(preferences.getStringSet("masteredWords", new HashSet<>()));
        totalObjectiveAnswers = preferences.getInt("totalObjectiveAnswers", 0);
        correctObjectiveAnswers = preferences.getInt("correctObjectiveAnswers", 0);
        studyStreak = preferences.getInt("studyStreak", 0);
        lastStudyDate = preferences.getString("lastStudyDate", "");
        try { history = new JSONArray(preferences.getString("history", "[]")); }
        catch (JSONException ignored) { history = new JSONArray(); }
    }

    private void saveProgress() {
        preferences.edit()
                .putStringSet("selectedTypes", new HashSet<>(selectedTypes))
                .putStringSet("completedLessons", new HashSet<>(completedLessons))
                .putStringSet("wrongQuestions", new HashSet<>(wrongQuestionIds))
                .putStringSet("starredWords", new HashSet<>(starredWords))
                .putStringSet("masteredWords", new HashSet<>(masteredWords))
                .putInt("totalObjectiveAnswers", totalObjectiveAnswers)
                .putInt("correctObjectiveAnswers", correctObjectiveAnswers)
                .putInt("studyStreak", studyStreak)
                .putString("lastStudyDate", lastStudyDate)
                .putString("history", history.toString())
                .apply();
    }

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        String title = "学习模块";
        if ("home".equals(page)) title = "四级首页";
        else if ("practice".equals(page)) title = "专项训练";
        else if ("real".equals(page)) title = "真题中心";
        else if ("vocab".equals(page)) title = "四级词汇";
        else if ("course".equals(page)) title = activeCourseTitle;
        else if ("lesson".equals(page)) title = "章节学习";
        else if ("quiz".equals(page)) title = "正在练习";
        titleView.setText(title);
        renderBottomNavigation();
        switch (page) {
            case "home": renderHome(); break;
            case "practice": renderPractice(); break;
            case "real": renderReal(); break;
            case "vocab": renderVocabulary(); break;
            case "course": renderCourse(); break;
            case "lesson": renderLesson(); break;
            case "quiz": renderQuiz(); break;
            default: renderModules(); break;
        }
        scrollView.scrollTo(0, 0);
    }

    private void renderBottomNavigation() {
        bottomBar.removeAllViews();
        String[] labels = {"模块", "首页", "训练", "真题", "词汇"};
        String[] pages = {"modules", "home", "practice", "real", "vocab"};
        String selected = page;
        if ("course".equals(page) || "lesson".equals(page)) selected = "modules";
        if ("quiz".equals(page)) selected = "practice";
        for (int i = 0; i < labels.length; i++) {
            final String target = pages[i];
            Button button = new Button(this);
            button.setText(labels[i]);
            button.setTextSize(12);
            button.setAllCaps(false);
            button.setMinHeight(dp(44));
            button.setPadding(dp(2), 0, dp(2), 0);
            button.setTextColor(target.equals(selected) ? GREEN : MUTED);
            button.setTypeface(Typeface.DEFAULT, target.equals(selected) ? Typeface.BOLD : Typeface.NORMAL);
            button.setBackgroundColor(Color.TRANSPARENT);
            button.setOnClickListener(view -> {
                if ("quiz".equals(page)) {
                    new AlertDialog.Builder(this).setMessage("退出当前练习？")
                            .setNegativeButton("继续练习", null)
                            .setPositiveButton("退出", (dialog, which) -> { page = target; render(); }).show();
                } else {
                    page = target;
                    render();
                }
            });
            bottomBar.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    private void renderModules() {
        addText(body, "选择学习方向", 23, INK, true);
        addText(body, "课程内容保存在手机中，没网也能继续学习。", 14, MUTED, false);
        addSpace(body, 12);
        for (int i = 0; i < modules.length(); i++) {
            JSONObject module = modules.optJSONObject(i);
            if (module == null) continue;
            String title = module.optString("title", "学习模块");
            String description = module.optString("description", "");
            String id = module.optString("id", "");
            String actionLabel = module.optString("actionLabel", "开始学习");
            LinearLayout card = card(body);
            addText(card, title, 19, INK, true);
            addText(card, description, 14, MUTED, false);
            Button open = primaryButton(actionLabel, () -> openModule(module));
            addViewWithTopMargin(card, open, 10);
        }
        if (modules.length() == 0) addEmpty(body, "暂时没有可用课程。联网后可以检查内容更新。");
    }

    private void openModule(JSONObject module) {
        if ("course".equals(module.optString("kind"))) {
            activeCoursePath = module.optString("file", "");
            activeCourseTitle = module.optString("title", "课程");
            try {
                activeCourse = repository.readJson(activeCoursePath);
                selectedTopic = "全部主题";
                page = "course";
                render();
            } catch (Exception error) {
                toast("课程读取失败：" + error.getMessage());
            }
        } else {
            page = "home";
            render();
        }
    }

    private void renderHome() {
        addText(body, "今天，先完成一小组", 23, INK, true);
        addText(body, "从词汇、错题或真题开始，按自己的节奏练习。", 14, MUTED, false);
        addSpace(body, 14);

        LinearLayout stats = card(body);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        addStat(stats, "连续学习", String.valueOf(studyStreak), "天");
        addStat(stats, "词汇掌握", vocabulary.length() == 0 ? "0" : String.valueOf(Math.round(masteredWords.size() * 100f / vocabulary.length())), "%");
        addStat(stats, "正确率", totalObjectiveAnswers == 0 ? "--" : String.valueOf(Math.round(correctObjectiveAnswers * 100f / totalObjectiveAnswers)), totalObjectiveAnswers == 0 ? "" : "%");

        addSectionTitle(body, "快速开始");
        addActionCard(body, "真题阅读 · 240道", "8套阅读，先获取后离线；按套或题型练习", "进入", () -> { page = "real"; render(); });
        addActionCard(body, "今日 5 题", "从四级练习题中抽取一组", "开始", () -> startDailyQuiz());
        addActionCard(body, "复习错题", wrongQuestionIds.size() + " 道待复习题", "复习", () -> startWrongQuiz());
        addActionCard(body, "四级词汇", "搜索、收藏并标记已掌握", "打开", () -> { selectedVocabFilter = "待复习"; vocabPage = 1; page = "vocab"; render(); });
        addSectionTitle(body, "最近练习");
        if (history.length() == 0) addEmpty(body, "完成一次练习后，这里会显示记录。");
        for (int i = 0; i < Math.min(history.length(), 5); i++) {
            JSONObject entry = history.optJSONObject(i);
            if (entry == null) continue;
            LinearLayout row = card(body);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView summary = text(entry.optString("title", "练习") + "\n" + entry.optString("meta", ""), 14, INK, false);
            row.addView(summary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView score = text(entry.optString("score", "--"), 17, GREEN, true);
            row.addView(score, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void addStat(LinearLayout parent, String label, String value, String unit) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(4), dp(4), dp(4), dp(4));
        cell.setGravity(Gravity.CENTER);
        addText(cell, label, 12, MUTED, false);
        TextView number = text(value + unit, 21, GREEN, true);
        cell.addView(number);
        parent.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    private void renderCourse() {
        addActionCard(body, "原书阅读 · 重点与讲解", "导入完整 PDF 后离线逐页阅读；框选重点、写批注，并查看对应章节讲解。", "打开原书", () -> startActivity(new Intent(this, BookReaderActivity.class)));
        String subtitle = activeCourse.optString("subtitle", "");
        if (!subtitle.isEmpty()) addText(body, subtitle, 14, MUTED, false);
        int completedCount = 0;
        JSONArray lessons = activeCourse.optJSONArray("lessons");
        if (lessons != null) {
            for (int i = 0; i < lessons.length(); i++) {
                JSONObject lesson = lessons.optJSONObject(i);
                if (lesson != null && completedLessons.contains(lesson.optString("id"))) completedCount++;
            }
        }
        addText(body, completedCount + " / " + (lessons == null ? 0 : lessons.length()) + " 章已完成", 13, GREEN, true);
        EditText search = editText("搜索章节标题或知识点", InputType.TYPE_CLASS_TEXT);
        addViewWithTopMargin(body, search, 10);
        Spinner topics = spinner(topicOptions());
        int selectedIndex = topicOptions().indexOf(selectedTopic);
        if (selectedIndex >= 0) topics.setSelection(selectedIndex);
        addViewWithTopMargin(body, topics, 8);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        addViewWithTopMargin(body, list, 10);
        Runnable updateList = () -> populateLessonList(list, search.getText().toString(), selectedTopic);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateList.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        topics.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectedTopic = topicOptions().get(position);
                updateList.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        updateList.run();
    }

    private List<String> topicOptions() {
        ArrayList<String> options = new ArrayList<>();
        options.add("全部主题");
        JSONArray topics = activeCourse.optJSONArray("topics");
        if (topics != null) for (int i = 0; i < topics.length(); i++) options.add(topics.optString(i));
        return options;
    }

    private void populateLessonList(LinearLayout list, String query, String topic) {
        list.removeAllViews();
        JSONArray lessons = activeCourse.optJSONArray("lessons");
        if (lessons == null) return;
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < lessons.length(); i++) {
            JSONObject lesson = lessons.optJSONObject(i);
            if (lesson == null) continue;
            String text = (lesson.optString("title") + " " + lesson.optString("summary") + " " + lesson.optString("englishTitle")).toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && !text.contains(needle)) continue;
            if (!"全部主题".equals(topic) && !topic.equals(lesson.optString("topic"))) continue;
            String id = lesson.optString("id");
            String status = completedLessons.contains(id) ? "已完成" : lesson.optString("topic", "课程");
            LinearLayout item = card(list);
            item.setOrientation(LinearLayout.HORIZONTAL);
            TextView number = text(String.format(Locale.ROOT, "%02d", lesson.optInt("number")), 17, GREEN, true);
            number.setGravity(Gravity.CENTER);
            item.addView(number, new LinearLayout.LayoutParams(dp(42), dp(44)));
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setPadding(dp(8), 0, dp(6), 0);
            addText(details, lesson.optString("title"), 16, INK, true);
            addText(details, status + (lesson.optString("pages").isEmpty() ? "" : " · 教材 " + lesson.optString("pages") + " 页"), 12, MUTED, false);
            item.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            item.setOnClickListener(view -> {
                activeLessonId = id;
                page = "lesson";
                render();
            });
        }
        if (list.getChildCount() == 0) addEmpty(list, "没有符合条件的章节。");
    }

    private String activeLessonId = "";

    private void renderLesson() {
        JSONObject lesson = findLesson(activeLessonId);
        if (lesson == null) { page = "course"; render(); return; }
        Button back = smallButton("‹ 章节目录", false, () -> { page = "course"; render(); });
        body.addView(back);
        addText(body, "第 " + lesson.optInt("number") + " 章", 13, GREEN, true);
        addText(body, lesson.optString("title"), 25, INK, true);
        addText(body, lesson.optString("englishTitle"), 14, MUTED, false);
        addText(body, "教材页码：" + lesson.optString("pages", "未标注") + " · " + lesson.optString("topic", ""), 12, MUTED, false);
        Button readBook = secondaryButton("阅读本章原书 / 标记重点", () -> startActivity(new Intent(this, BookReaderActivity.class).putExtra("lessonId", activeLessonId)));
        addViewWithTopMargin(body, readBook, 8);
        addSectionTitle(body, "本章目标");
        addText(body, lesson.optString("summary"), 15, INK, false);
        addSectionTitle(body, "知识讲解");
        JSONArray points = lesson.optJSONArray("points");
        if (points != null) for (int i = 0; i < points.length(); i++) addBullet(body, points.optString(i));

        JSONObject guide = activeCourse.optJSONObject("lessonGuides");
        JSONObject lessonGuide = guide == null ? null : guide.optJSONObject(activeLessonId);
        if (lessonGuide != null) {
            JSONArray explain = lessonGuide.optJSONArray("explain");
            if (explain != null && explain.length() > 0) {
                addSectionTitle(body, "理解重点");
                for (int i = 0; i < explain.length(); i++) addText(body, explain.optString(i), 14, INK, false);
            }
            JSONArray steps = lessonGuide.optJSONArray("steps");
            if (steps != null && steps.length() > 0) {
                addSectionTitle(body, "例题推演");
                for (int i = 0; i < steps.length(); i++) addNumberedText(body, i + 1, steps.optString(i));
            }
        }

        JSONObject example = lesson.optJSONObject("example");
        if (example != null) {
            addSectionTitle(body, "代码示例");
            String code = example.optString("code", "");
            if (!code.isEmpty()) {
                TextView codeView = text(code, 12, Color.rgb(239, 245, 241), false);
                codeView.setTypeface(Typeface.MONOSPACE);
                codeView.setPadding(dp(12), dp(12), dp(12), dp(12));
                codeView.setBackground(roundRect(Color.rgb(35, 47, 40), 7));
                HorizontalScrollViewCompat.addTo(body, codeView);
            }
            addText(body, example.optString("explanation", ""), 14, INK, false);
        }

        JSONObject exercise = lesson.optJSONObject("exercise");
        if (exercise != null) {
            addSectionTitle(body, "章节自练");
            addPracticeCard(body, exercise.optString("prompt"), exercise.optString("answer"), exercise.optString("analysis"));
        }
        if (lessonGuide != null) {
            JSONArray practice = lessonGuide.optJSONArray("practice");
            if (practice != null) for (int i = 0; i < practice.length(); i++) {
                JSONObject item = practice.optJSONObject(i);
                if (item != null) addPracticeCard(body, item.optString("prompt"), item.optString("answer"), item.optString("analysis"));
            }
        }

        JSONArray questions = activeCourse.optJSONArray("questions");
        int questionCount = 0;
        if (questions != null) for (int i = 0; i < questions.length(); i++) {
            JSONObject question = questions.optJSONObject(i);
            if (question != null && activeLessonId.equals(question.optString("lessonId"))) questionCount++;
        }
        Button quiz = primaryButton("本章选择题 · " + questionCount + " 题", () -> startCourseQuiz(activeLessonId));
        addViewWithTopMargin(body, quiz, 10);
        Button completed = secondaryButton(completedLessons.contains(activeLessonId) ? "取消完成标记" : "标记本章完成", () -> {
            if (completedLessons.contains(activeLessonId)) completedLessons.remove(activeLessonId); else completedLessons.add(activeLessonId);
            saveProgress();
            render();
        });
        addViewWithTopMargin(body, completed, 8);
        String takeaway = lesson.optString("takeaway", "");
        if (!takeaway.isEmpty()) {
            addSectionTitle(body, "记住这一点");
            addText(body, takeaway, 15, ORANGE, true);
        }
    }

    private void addPracticeCard(LinearLayout parent, String prompt, String answer, String analysis) {
        LinearLayout practice = card(parent);
        addText(practice, prompt, 14, INK, true);
        Button reveal = smallButton("查看答案与解析", false, () -> new AlertDialog.Builder(this)
                .setTitle("参考答案")
                .setMessage(answer + (analysis.isEmpty() ? "" : "\n\n解析：" + analysis))
                .setPositiveButton("知道了", null).show());
        addViewWithTopMargin(practice, reveal, 8);
    }

    private JSONObject findLesson(String id) {
        JSONArray lessons = activeCourse.optJSONArray("lessons");
        if (lessons == null) return null;
        for (int i = 0; i < lessons.length(); i++) {
            JSONObject lesson = lessons.optJSONObject(i);
            if (lesson != null && id.equals(lesson.optString("id"))) return lesson;
        }
        return null;
    }

    private void renderPractice() {
        addText(body, "按题型选择训练范围", 22, INK, true);
        addText(body, "当前有本地练习题；写作和翻译可提交后查看参考内容。", 14, MUTED, false);
        LinearLayout mode = new LinearLayout(this);
        mode.setOrientation(LinearLayout.HORIZONTAL);
        Button practice = smallButton("专项题", "practice".equals(preferredQuizMode), () -> { preferredQuizMode = "practice"; render(); });
        Button real = smallButton("真题精选", "real".equals(preferredQuizMode), () -> { preferredQuizMode = "real"; render(); });
        mode.addView(practice, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams realParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        realParams.leftMargin = dp(8);
        mode.addView(real, realParams);
        addViewWithTopMargin(body, mode, 12);

        String[] groups = {"听力", "阅读", "写作", "翻译"};
        for (String group : groups) {
            LinearLayout groupCard = card(body);
            addText(groupCard, group, 16, GREEN, true);
            for (int i = 0; i < questionTypes.length(); i++) {
                JSONObject type = questionTypes.optJSONObject(i);
                if (type == null || !group.equals(type.optString("group"))) continue;
                String id = type.optString("id");
                String label = type.optString("label");
                CheckBox box = new CheckBox(this);
                box.setText(label + "   " + type.optString("weight") + " · " + type.optString("minutes") + " 分钟");
                box.setTextColor(INK);
                box.setTextSize(14);
                box.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));
                box.setChecked(selectedTypes.contains(id));
                box.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) selectedTypes.add(id); else selectedTypes.remove(id);
                    saveProgress();
                });
                groupCard.addView(box);
            }
        }

        LinearLayout settings = card(body);
        addText(settings, "题量与顺序", 16, INK, true);
        Spinner count = spinner(Arrays.asList("5 题", "10 题", "20 题"));
        count.setSelection(selectedQuestionCount == 5 ? 0 : selectedQuestionCount == 20 ? 2 : 1);
        count.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectedQuestionCount = position == 0 ? 5 : position == 2 ? 20 : 10;
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        settings.addView(count);
        CheckBox shuffle = new CheckBox(this);
        shuffle.setText("随机抽题");
        shuffle.setChecked(shuffleQuestions);
        shuffle.setTextColor(INK);
        shuffle.setOnCheckedChangeListener((button, checked) -> shuffleQuestions = checked);
        settings.addView(shuffle);
        Button start = primaryButton("开始训练", this::startSelectedQuiz);
        addViewWithTopMargin(body, start, 12);
    }

    private String preferredQuizMode = "practice";

    private void startSelectedQuiz() {
        ArrayList<JSONObject> choices = new ArrayList<>();
        for (int i = 0; i < questionItems.length(); i++) {
            JSONObject item = questionItems.optJSONObject(i);
            if (item == null || !preferredQuizMode.equals(item.optString("mode"))) continue;
            if (!selectedTypes.isEmpty() && !selectedTypes.contains(item.optString("type"))) continue;
            choices.add(item);
        }
        if (choices.isEmpty()) {
            toast(selectedTypes.isEmpty() ? "请先选择一个题型。" : "所选题型暂时没有题目，请调整选择。");
            return;
        }
        launchQuiz(choices, preferredQuizMode, selectedQuestionCount, shuffleQuestions);
    }

    private void startDailyQuiz() {
        ArrayList<JSONObject> choices = new ArrayList<>();
        for (int i = 0; i < questionItems.length(); i++) {
            JSONObject item = questionItems.optJSONObject(i);
            if (item != null && "practice".equals(item.optString("mode"))) choices.add(item);
        }
        launchQuiz(choices, "practice", 5, true);
    }

    private void startWrongQuiz() {
        ArrayList<JSONObject> choices = new ArrayList<>();
        for (int i = 0; i < questionItems.length(); i++) {
            JSONObject item = questionItems.optJSONObject(i);
            if (item != null && wrongQuestionIds.contains(item.optString("id"))) choices.add(item);
        }
        if (choices.isEmpty()) { toast("暂时没有可复习的四级错题。"); return; }
        launchQuiz(choices, "wrong", choices.size(), false);
    }

    private void startCourseQuiz(String lessonId) {
        ArrayList<JSONObject> choices = new ArrayList<>();
        JSONArray questions = activeCourse.optJSONArray("questions");
        if (questions != null) for (int i = 0; i < questions.length(); i++) {
            JSONObject item = questions.optJSONObject(i);
            if (item != null && lessonId.equals(item.optString("lessonId"))) choices.add(item);
        }
        if (choices.isEmpty()) { toast("本章暂时没有选择题。"); return; }
        launchQuiz(choices, "course", choices.size(), false);
    }

    private void launchQuiz(ArrayList<JSONObject> choices, String mode, int count, boolean shuffle) {
        quizItems = new ArrayList<>(choices);
        if (shuffle) java.util.Collections.shuffle(quizItems, new Random());
        if (count > 0 && quizItems.size() > count) quizItems = new ArrayList<>(quizItems.subList(0, count));
        quizMode = mode;
        quizIndex = 0;
        quizScore = 0;
        quizObjectiveCount = 0;
        quizLocked = false;
        quizAnswerCorrect = null;
        quizFeedback = "";
        quizExplanation = "";
        quizInputValue = "";
        selectedOption = "";
        selectedMatches.clear();
        page = "quiz";
        render();
    }

    private void renderQuiz() {
        if (quizIndex >= quizItems.size()) { finishQuiz(); return; }
        JSONObject item = quizItems.get(quizIndex);
        addText(body, quizLabel() + " · " + questionTypeLabel(item.optString("type")), 12, GREEN, true);
        addText(body, item.optString("title", "练习"), 23, INK, true);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(quizItems.size());
        progress.setProgress(quizIndex + (quizLocked ? 1 : 0));
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));
        addViewWithTopMargin(body, progress, 8);
        addText(body, (quizIndex + 1) + " / " + quizItems.size(), 12, MUTED, false);

        String type = item.optString("type");
        String transcript = item.optString("transcript", "");
        if (type.startsWith("listening") && !transcript.isEmpty()) {
            Button listen = secondaryButton("朗读听力文本", () -> speak(transcript));
            addViewWithTopMargin(body, listen, 12);
        }
        String passage = item.optString("passage", "");
        if (!passage.isEmpty()) {
            LinearLayout passageCard = card(body);
            addText(passageCard, "材料", 13, GREEN, true);
            addText(passageCard, passage, 15, INK, false);
        }
        String prompt = item.optString("prompt", "");
        if (!prompt.isEmpty()) addText(body, prompt, 17, INK, true);

        activeOptionGroup = null;
        activeAnswerInput = null;
        activeMatchSpinners.clear();
        JSONArray options = item.optJSONArray("options");
        if (options != null) {
            activeOptionGroup = new RadioGroup(this);
            activeOptionGroup.setOrientation(RadioGroup.VERTICAL);
            addViewWithTopMargin(body, activeOptionGroup, 8);
            for (int i = 0; i < options.length(); i++) {
                Object option = options.opt(i);
                String value;
                String label;
                if (option instanceof JSONObject) {
                    JSONObject object = (JSONObject) option;
                    value = object.optString("id", String.valueOf((char) ('A' + i)));
                    label = value + ". " + object.optString("text", "");
                } else {
                    label = String.valueOf(option);
                    value = label.length() == 0 ? String.valueOf((char) ('A' + i)) : label.substring(0, 1).replace(")", "").replace(".", "");
                }
                RadioButton radio = new RadioButton(this);
                radio.setId(View.generateViewId());
                radio.setText(label);
                radio.setTag(value);
                radio.setTextColor(INK);
                radio.setTextSize(15);
                radio.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));
                radio.setEnabled(!quizLocked);
                activeOptionGroup.addView(radio);
                if (value.equals(selectedOption)) activeOptionGroup.check(radio.getId());
            }
            activeOptionGroup.setOnCheckedChangeListener((group, checkedId) -> {
                RadioButton checked = group.findViewById(checkedId);
                if (checked != null) selectedOption = String.valueOf(checked.getTag());
            });
        }

        JSONArray wordBank = item.optJSONArray("wordBank");
        if (wordBank != null) {
            StringBuilder words = new StringBuilder("词库：");
            for (int i = 0; i < wordBank.length(); i++) {
                if (i > 0) words.append("  ·  ");
                words.append(wordBank.optString(i));
            }
            LinearLayout bank = card(body);
            addText(bank, words.toString(), 14, GREEN, false);
        }

        JSONArray statements = item.optJSONArray("statements");
        if (statements != null) {
            for (int i = 0; i < statements.length(); i++) {
                JSONObject statement = statements.optJSONObject(i);
                if (statement == null) continue;
                LinearLayout matching = card(body);
                addText(matching, statement.optInt("number") + ". " + statement.optString("text"), 15, INK, true);
                ArrayList<String> paragraphChoices = new ArrayList<>();
                paragraphChoices.add("选择段落");
                JSONArray paragraphs = item.optJSONArray("paragraphs");
                if (paragraphs != null) {
                    for (int p = 0; p < paragraphs.length(); p++) {
                        JSONObject paragraph = paragraphs.optJSONObject(p);
                        if (paragraph != null) paragraphChoices.add(paragraph.optString("id"));
                    }
                } else paragraphChoices.addAll(Arrays.asList("A", "B", "C", "D"));
                Spinner answer = spinner(paragraphChoices);
                String key = String.valueOf(statement.optInt("number"));
                String current = selectedMatches.get(key);
                if (current != null && paragraphChoices.contains(current)) answer.setSelection(paragraphChoices.indexOf(current));
                answer.setEnabled(!quizLocked);
                answer.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                        if (position > 0) selectedMatches.put(key, String.valueOf(parent.getItemAtPosition(position)));
                        else selectedMatches.remove(key);
                    }
                    @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
                });
                matching.addView(answer);
                activeMatchSpinners.put(key, answer);
            }
        }

        if ("reading-cloze".equals(type) || "writing".equals(type) || "translation".equals(type)) {
            String placeholder = "reading-cloze".equals(type) ? (item.has("questionNumber") ? "填写本题单词或词库字母" : "按空格顺序填写答案；多个答案用 / 分隔") : "在这里写下你的答案";
            int inputType = "reading-cloze".equals(type) ? InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
            activeAnswerInput = editText(placeholder, inputType);
            activeAnswerInput.setMinHeight("reading-cloze".equals(type) ? dp(54) : dp(130));
            activeAnswerInput.setText(quizInputValue);
            activeAnswerInput.setEnabled(!quizLocked);
            addViewWithTopMargin(body, activeAnswerInput, 8);
        }

        if (quizLocked) {
            LinearLayout feedback = card(body);
            int color = quizAnswerCorrect == null ? ORANGE : (quizAnswerCorrect ? GREEN : Color.rgb(177, 63, 52));
            addText(feedback, quizAnswerCorrect == null ? "已记录" : quizAnswerCorrect ? "回答正确" : "参考答案", 16, color, true);
            addText(feedback, quizFeedback, 14, INK, false);
            if (!quizExplanation.isEmpty()) addText(feedback, quizExplanation, 13, MUTED, false);
            if (!item.optString("hint", "").isEmpty()) addText(feedback, "提示：" + item.optString("hint"), 13, MUTED, false);
            String answerSource = item.optString("answerSourceUrl");
            if (!answerSource.isEmpty()) addViewWithTopMargin(feedback, secondaryButton("打开来源答案解析 PDF", () -> openUrl(answerSource)), 8);
        }
        String sourceUrl = item.optString("sourceUrl");
        if (!sourceUrl.isEmpty()) {
            addText(body, item.optString("source") + " · 来源参考答案", 12, MUTED, false);
            addViewWithTopMargin(body, secondaryButton("查看来源与原卷", () -> openUrl(sourceUrl)), 8);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button quit = secondaryButton("退出", () -> { page = "real".equals(quizMode) ? "real" : "practice"; render(); });
        Button submit = primaryButton(quizLocked ? (quizIndex + 1 >= quizItems.size() ? "查看结果" : "下一题") : "提交答案", () -> {
            if (quizLocked) nextQuizItem(); else submitQuizAnswer();
        });
        actions.addView(quit, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        submitParams.leftMargin = dp(8);
        actions.addView(submit, submitParams);
        addViewWithTopMargin(body, actions, 14);
    }

    private String quizLabel() {
        if ("real".equals(quizMode)) return "真题精选";
        if ("wrong".equals(quizMode)) return "错题复习";
        if ("course".equals(quizMode)) return activeCourseTitle;
        return "专项练习";
    }

    private String questionTypeLabel(String type) {
        for (int i = 0; i < questionTypes.length(); i++) {
            JSONObject item = questionTypes.optJSONObject(i);
            if (item != null && type.equals(item.optString("id"))) return item.optString("label", type);
        }
        return type;
    }

    private void submitQuizAnswer() {
        JSONObject item = quizItems.get(quizIndex);
        String type = item.optString("type");
        if (activeAnswerInput != null) quizInputValue = activeAnswerInput.getText().toString().trim();
        if ("reading-cloze".equals(type) && quizInputValue.isEmpty()) { toast("请先填写词库答案。"); return; }
        if ("reading-matching".equals(type)) {
            JSONArray statements = item.optJSONArray("statements");
            if (statements != null && selectedMatches.size() < statements.length()) { toast("请为每句话选择对应段落。"); return; }
        } else if (!"writing".equals(type) && !"translation".equals(type)
                && (item.optJSONArray("options") != null) && selectedOption.isEmpty()) {
            toast("请先选择一个答案。"); return;
        }

        quizLocked = true;
        boolean subjective = "writing".equals(type) || "translation".equals(type);
        if (subjective) {
            quizAnswerCorrect = null;
            quizFeedback = "答案已保留在本次练习中。请对照评分要点和参考答案自评。";
            StringBuilder review = new StringBuilder();
            JSONArray rubric = item.optJSONArray("checklist");
            if (rubric == null) rubric = item.optJSONArray("rubric");
            if (rubric != null && rubric.length() > 0) {
                review.append("自评要点：");
                for (int i = 0; i < rubric.length(); i++) {
                    if (i > 0) review.append("；");
                    review.append(rubric.optString(i));
                }
            }
            String reference = item.optString("referenceAnswer", "");
            if (!reference.isEmpty()) {
                if (review.length() > 0) review.append("\n\n");
                review.append("参考答案：\n").append(reference);
            }
            quizExplanation = review.toString();
        } else if ("reading-matching".equals(type)) {
            JSONArray statements = item.optJSONArray("statements");
            int right = 0;
            StringBuilder answers = new StringBuilder("参考答案：");
            if (statements != null) for (int i = 0; i < statements.length(); i++) {
                JSONObject statement = statements.optJSONObject(i);
                if (statement == null) continue;
                String number = String.valueOf(statement.optInt("number"));
                if (statement.optString("answer").equals(selectedMatches.get(number))) right++;
                if (i > 0) answers.append(" · ");
                answers.append(number).append(statement.optString("answer"));
            }
            quizAnswerCorrect = statements != null && right == statements.length();
            quizFeedback = "匹配正确 " + right + "/" + (statements == null ? 0 : statements.length()) + "。" + answers;
            quizExplanation = item.optString("explanation", "");
        } else {
            String answer = item.optString("answer", "");
            String given = "reading-cloze".equals(type) ? normalizeAnswer(quizInputValue) : selectedOption.trim();
            String expected = "reading-cloze".equals(type) ? normalizeAnswer(answer) : answer.trim();
            quizAnswerCorrect = given.equalsIgnoreCase(expected);
            JSONArray accepted = item.optJSONArray("acceptedAnswers");
            if ("reading-cloze".equals(type) && accepted != null) {
                for (int i = 0; i < accepted.length(); i++)
                    if (given.equalsIgnoreCase(normalizeAnswer(accepted.optString(i)))) quizAnswerCorrect = true;
            }
            quizFeedback = "参考答案：" + answer;
            quizExplanation = item.optString("explanation", "");
        }

        if (quizAnswerCorrect != null) {
            quizObjectiveCount++;
            totalObjectiveAnswers++;
            if (quizAnswerCorrect) {
                quizScore++;
                correctObjectiveAnswers++;
                wrongQuestionIds.remove(item.optString("id"));
            } else {
                String id = item.optString("id");
                if (!id.isEmpty()) wrongQuestionIds.add(id);
            }
        }
        saveProgress();
        render();
    }

    private String normalizeAnswer(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s*/\\s*", "/").replaceAll("\\s+", " ");
    }

    private void nextQuizItem() {
        quizIndex++;
        quizLocked = false;
        quizAnswerCorrect = null;
        quizFeedback = "";
        quizExplanation = "";
        quizInputValue = "";
        selectedOption = "";
        selectedMatches.clear();
        render();
    }

    private void finishQuiz() {
        page = "quiz";
        int score = quizObjectiveCount == 0 ? 0 : Math.round(quizScore * 100f / quizObjectiveCount);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        if (!today.equals(lastStudyDate)) {
            studyStreak++;
            lastStudyDate = today;
        }
        JSONObject record = new JSONObject();
        try {
            record.put("title", quizLabel());
            record.put("meta", quizItems.size() + " 题 · " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date()));
            record.put("score", score + "%");
            history.put(0, record);
            while (history.length() > 8) history.remove(history.length() - 1);
        } catch (JSONException ignored) { }
        saveProgress();
        renderBottomNavigation();
        body.removeAllViews();
        addText(body, "练习完成", 13, GREEN, true);
        addText(body, quizObjectiveCount == 0 ? "已记录" : score + " 分", 32, INK, true);
        addText(body, "共完成 " + quizItems.size() + " 题，客观题正确 " + quizScore + " / " + quizObjectiveCount + "。", 15, MUTED, false);
        Button again = primaryButton("再练一组", () -> { page = "practice"; render(); });
        addViewWithTopMargin(body, again, 16);
        Button home = secondaryButton("回到四级首页", () -> { page = "home"; render(); });
        addViewWithTopMargin(body, home, 8);
    }

    private void renderReal() {
        addText(body, "四级真题阅读", 22, INK, true);
        JSONArray exams = readingSources.optJSONArray("exams");
        int downloaded = 0;
        if (exams != null) for (int i = 0; i < exams.length(); i++) {
            JSONObject exam = exams.optJSONObject(i);
            if (exam != null) downloaded += readingPaperItems(exam.optString("id"), "").size();
        }
        addText(body, (exams == null ? 0 : exams.length()) + " 套 · 可获取 " + readingSources.optInt("smallQuestionCount") + " 道小题 · 已下载 " + downloaded + " 道", 14, GREEN, true);
        addText(body, "每套30道：选词填空10、段落匹配10、仔细阅读10；共32篇文章。首次从公开来源获取，下载后在本机离线作答。", 14, MUTED, false);
        addText(body, "答案来自来源整理版，非考试机构官方答案；保留原卷与参考解析入口。", 12, MUTED, false);
        if (downloadingReading) addText(body, readingDownloadStatus, 14, ORANGE, true);
        if (exams != null && exams.length() > 0) {
            ArrayList<JSONObject> missing = new ArrayList<>();
            for (int i = 0; i < exams.length(); i++) {
                JSONObject exam = exams.optJSONObject(i);
                if (exam != null && readingPaperItems(exam.optString("id"), "").size() != 30) missing.add(exam);
            }
            Button all = primaryButton(missing.isEmpty() ? "已全部下载 · 随机练习20题" : "获取全部未下载试卷 · " + (missing.size() * 30) + " 题", () -> {
                if (missing.isEmpty()) startQuizByMode("real", 20);
                else downloadReadingPapers(missing);
            });
            all.setEnabled(!downloadingReading);
            addViewWithTopMargin(body, all, 12);
            for (int i = 0; i < exams.length(); i++) {
                JSONObject exam = exams.optJSONObject(i);
                if (exam == null) continue;
                boolean saved = readingPaperItems(exam.optString("id"), "").size() == 30;
                LinearLayout paper = card(body);
                addText(paper, exam.optString("exam"), 17, INK, true);
                addText(paper, "4篇文章 · 30道小题 · " + (saved ? "已下载，可离线" : "尚未下载"), 13, MUTED, false);
                Button action = primaryButton(saved ? "开始本套阅读" : "获取本套30题", () -> {
                    if (saved) chooseReadingPractice(exam);
                    else downloadReadingPapers(new ArrayList<>(Arrays.asList(exam)));
                });
                action.setEnabled(saved || !downloadingReading);
                addViewWithTopMargin(paper, action, 8);
                addViewWithTopMargin(paper, secondaryButton("查看来源 / 原卷", () -> openUrl(exam.optString("sourceUrl"))), 6);
            }
        }
        addSectionTitle(body, "原有精选摘选");
        for (int i = 0; i < questionItems.length(); i++) {
            JSONObject item = questionItems.optJSONObject(i);
            if (item == null || !"real".equals(item.optString("mode")) || item.optString("id").startsWith("r-")) continue;
            addActionCard(body, item.optString("title", questionTypeLabel(item.optString("type"))), questionTypeLabel(item.optString("type")) + " · " + item.optString("source", item.optString("exam", "精选题")), "练习", () -> {
                ArrayList<JSONObject> single = new ArrayList<>();
                single.add(item);
                launchQuiz(single, "real", 1, false);
            });
        }
        addSectionTitle(body, "完整试卷入口");
        for (int i = 0; i < examLinks.length(); i++) {
            JSONObject link = examLinks.optJSONObject(i);
            if (link == null) continue;
            addActionCard(body, link.optString("label", "在线试卷"), link.optString("note", ""), "打开", () -> openUrl(link.optString("url")));
        }
    }

    private void startQuizByMode(String mode, int count) {
        ArrayList<JSONObject> choices = new ArrayList<>();
        for (int i = 0; i < questionItems.length(); i++) {
            JSONObject item = questionItems.optJSONObject(i);
            if (item != null && mode.equals(item.optString("mode"))) choices.add(item);
        }
        launchQuiz(choices, mode, count, true);
    }

    private ArrayList<JSONObject> readingPaperItems(String id, String type) {
        ArrayList<JSONObject> choices = new ArrayList<>();
        for (int i = 0; i < questionItems.length(); i++) {
            JSONObject item = questionItems.optJSONObject(i);
            if (item != null && item.optString("id").startsWith("r-" + id + "-q")
                    && (type.isEmpty() || type.equals(item.optString("type")))) choices.add(item);
        }
        java.util.Collections.sort(choices, (a, b) -> Integer.compare(a.optInt("questionNumber"), b.optInt("questionNumber")));
        return choices;
    }

    private void chooseReadingPractice(JSONObject exam) {
        String[] labels = {"整套阅读 · 30题", "选词填空 · 10题", "段落匹配 · 10题", "仔细阅读 · 10题"};
        String[] types = {"", "reading-cloze", "reading-matching", "reading-detail"};
        new AlertDialog.Builder(this).setTitle(exam.optString("exam")).setItems(labels, (dialog, which) -> {
            ArrayList<JSONObject> items = readingPaperItems(exam.optString("id"), types[which]);
            if (items.isEmpty()) { toast("本套文件暂不可用，请重新获取。"); return; }
            launchQuiz(items, "real", items.size(), false);
        }).setNegativeButton("取消", null).show();
    }

    private void downloadReadingPapers(ArrayList<JSONObject> exams) {
        if (downloadingReading || exams.isEmpty()) return;
        downloadingReading = true;
        readingDownloadStatus = "正在获取 " + exams.get(0).optString("exam") + "…";
        render();
        new Thread(() -> {
            int completed = 0;
            ArrayList<String> errors = new ArrayList<>();
            for (JSONObject exam : exams) {
                try { repository.downloadReadingPaper(exam); completed++; }
                catch (Exception error) { errors.add(exam.optString("exam") + "：" + error.getMessage()); }
                final int done = completed;
                runOnUiThread(() -> {
                    readingDownloadStatus = "已获取 " + done + " / " + exams.size() + " 套…";
                    if (!isFinishing() && !isDestroyed() && "real".equals(page)) render();
                });
            }
            final int total = completed;
            runOnUiThread(() -> {
                downloadingReading = false;
                if (isFinishing() || isDestroyed()) return;
                loadContent();
                if ("real".equals(page)) render();
                if (errors.isEmpty()) toast("已保存 " + (total * 30) + " 道小题，可断网练习。");
                else new AlertDialog.Builder(this).setTitle("已完成 " + total + " 套").setMessage("以下试卷未下载成功，可重新获取。已有试卷仍可离线使用。" + android.text.TextUtils.join("；", errors)).setPositiveButton("知道了", null).show();
            });
        }, "study-reading-download").start();
    }

    private void renderVocabulary() {
        addText(body, "四级词汇库", 22, INK, true);
        addText(body, vocabulary.length() + " 个词条 · 搜索释义、收藏并记录掌握情况", 13, MUTED, false);
        EditText search = editText("搜索单词、音标或中文释义", InputType.TYPE_CLASS_TEXT);
        search.setText(vocabQuery);
        addViewWithTopMargin(body, search, 10);
        Spinner filter = spinner(Arrays.asList("全部", "收藏", "已掌握", "待复习"));
        filter.setSelection(Arrays.asList("全部", "收藏", "已掌握", "待复习").indexOf(selectedVocabFilter));
        addViewWithTopMargin(body, filter, 8);
        TextView count = text("", 12, MUTED, false);
        addViewWithTopMargin(body, count, 8);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        addViewWithTopMargin(body, list, 6);
        LinearLayout pager = new LinearLayout(this);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        pager.setGravity(Gravity.CENTER_VERTICAL);
        addViewWithTopMargin(body, pager, 8);
        Runnable refresh = () -> populateVocabList(list, count, pager);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                vocabQuery = s.toString();
                vocabPage = 1;
                refresh.run();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectedVocabFilter = String.valueOf(parent.getItemAtPosition(position));
                vocabPage = 1;
                refresh.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        refresh.run();
    }

    private void populateVocabList(LinearLayout list, TextView count, LinearLayout pager) {
        list.removeAllViews();
        pager.removeAllViews();
        ArrayList<JSONObject> matches = new ArrayList<>();
        String query = vocabQuery.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < vocabulary.length(); i++) {
            JSONObject word = vocabulary.optJSONObject(i);
            if (word == null) continue;
            String id = word.optString("id");
            if ("收藏".equals(selectedVocabFilter) && !starredWords.contains(id)) continue;
            if ("已掌握".equals(selectedVocabFilter) && !masteredWords.contains(id)) continue;
            if ("待复习".equals(selectedVocabFilter) && masteredWords.contains(id)) continue;
            String content = (word.optString("word") + " " + word.optString("phonetic") + " " + word.optString("definition")).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !content.contains(query)) continue;
            matches.add(word);
        }
        int pageSize = 30;
        int pages = Math.max(1, (matches.size() + pageSize - 1) / pageSize);
        vocabPage = Math.min(vocabPage, pages);
        count.setText(matches.size() + " 个词条 · 第 " + vocabPage + " / " + pages + " 页");
        int start = (vocabPage - 1) * pageSize;
        for (int i = start; i < Math.min(start + pageSize, matches.size()); i++) {
            JSONObject word = matches.get(i);
            String id = word.optString("id");
            LinearLayout row = card(list);
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView wordText = text(word.optString("word"), 18, INK, true);
            top.addView(wordText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView phonetic = text(word.optString("phonetic"), 13, MUTED, false);
            top.addView(phonetic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(top);
            addText(row, word.optString("definition"), 14, INK, false);
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button speak = smallButton("朗读", false, () -> speak(word.optString("word")));
            Button star = smallButton(starredWords.contains(id) ? "已收藏" : "收藏", starredWords.contains(id), () -> {
                toggleWord(starredWords, id);
                saveProgress();
                renderVocabulary();
            });
            Button mastered = smallButton(masteredWords.contains(id) ? "已掌握" : "待复习", masteredWords.contains(id), () -> {
                toggleWord(masteredWords, id);
                saveProgress();
                renderVocabulary();
            });
            actions.addView(speak);
            LinearLayout.LayoutParams starParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            starParams.leftMargin = dp(6);
            actions.addView(star, starParams);
            LinearLayout.LayoutParams masteredParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            masteredParams.leftMargin = dp(6);
            actions.addView(mastered, masteredParams);
            addViewWithTopMargin(row, actions, 6);
            row.setOnClickListener(view -> showWordDialog(word));
        }
        if (matches.isEmpty()) addEmpty(list, "没有找到符合条件的词条。");
        Button previous = smallButton("上一页", false, () -> { if (vocabPage > 1) { vocabPage--; populateVocabList(list, count, pager); } });
        previous.setEnabled(vocabPage > 1);
        Button next = smallButton("下一页", false, () -> { if (vocabPage < pages) { vocabPage++; populateVocabList(list, count, pager); } });
        next.setEnabled(vocabPage < pages);
        pager.addView(previous);
        View spacer = new View(this);
        pager.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        pager.addView(next);
    }

    private void showWordDialog(JSONObject word) {
        String id = word.optString("id");
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        addText(content, word.optString("phonetic"), 14, MUTED, false);
        addText(content, word.optString("definition"), 16, INK, false);
        new AlertDialog.Builder(this).setTitle(word.optString("word"))
                .setView(content)
                .setNeutralButton("朗读", (dialog, which) -> speak(word.optString("word")))
                .setNegativeButton(starredWords.contains(id) ? "取消收藏" : "收藏", (dialog, which) -> {
                    toggleWord(starredWords, id); saveProgress(); render();
                })
                .setPositiveButton(masteredWords.contains(id) ? "设为待复习" : "标记掌握", (dialog, which) -> {
                    toggleWord(masteredWords, id); saveProgress(); render();
                }).show();
    }

    private void toggleWord(Set<String> set, String id) {
        if (!set.add(id)) set.remove(id);
    }

    private void speak(String value) {
        if (textToSpeech == null || !ttsReady) { toast("朗读服务正在准备，稍后再试。"); return; }
        textToSpeech.setLanguage(Locale.US);
        textToSpeech.speak(value, TextToSpeech.QUEUE_FLUSH, null, "study-speech");
    }

    private void checkUpdates(boolean manual) {
        if (checkingUpdates) return;
        long now = System.currentTimeMillis();
        long lastCheck = preferences.getLong("lastUpdateCheck", 0L);
        if (!manual && now - lastCheck < UPDATE_INTERVAL_MS) return;
        checkingUpdates = true;
        if (manual) toast("正在检查课程和 APK 更新…");
        new Thread(() -> {
            String dataVersion = null;
            String failure = null;
            JSONObject release = null;
            try { release = repository.latestRelease(); }
            catch (Exception error) { failure = error.getMessage(); }
            try { dataVersion = repository.updateContent(); }
            catch (Exception error) { if (failure == null) failure = error.getMessage(); }
            final String updated = dataVersion;
            final String errorText = failure;
            final JSONObject latest = release;
            runOnUiThread(() -> {
                checkingUpdates = false;
                if (isFinishing() || isDestroyed()) return;
                if (errorText == null) preferences.edit().putLong("lastUpdateCheck", now).apply();
                boolean contentChanged = updated != null;
                if (contentChanged) {
                    loadContent();
                    render();
                    toast("课程内容已更新到 " + updated);
                }
                String latestTag = latest == null ? "" : latest.optString("tag_name", "");
                String latestUrl = latest == null ? RELEASE_URL : latest.optString("html_url", RELEASE_URL);
                String installed = installedVersion();
                if (!latestTag.isEmpty() && StudyRepository.compareVersions(latestTag, installed) > 0) {
                    String notes = latest.optString("body", "").trim();
                    if (notes.length() > 400) notes = notes.substring(0, 400) + "…";
                    new AlertDialog.Builder(this).setTitle("发现新版本 " + latestTag)
                            .setMessage(notes.isEmpty() ? "可以下载最新 APK。" : notes)
                            .setNegativeButton("稍后", null)
                            .setPositiveButton("查看下载", (dialog, which) -> openUrl(latestUrl)).show();
                } else if (manual && !contentChanged) {
                    toast(errorText == null ? "当前已是最新版本。" : "暂时无法检查更新，请稍后重试。");
                }
            });
        }, "study-update-check").start();
    }

    private String installedVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "0"; }
    }

    private void showMoreMenu() {
        String[] options = {"检查更新", "重置学习进度", "关于 Study"};
        new AlertDialog.Builder(this).setItems(options, (dialog, which) -> {
            if (which == 0) checkUpdates(true);
            else if (which == 1) confirmResetProgress();
            else new AlertDialog.Builder(this).setTitle("Study " + installedVersion())
                    .setMessage("本地学习工具 · 支持离线课程和进度记录")
                    .setPositiveButton("知道了", null).show();
        }).show();
    }

    private void confirmResetProgress() {
        new AlertDialog.Builder(this).setTitle("重置学习进度？")
                .setMessage("收藏、掌握状态、错题和练习记录都会清除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", (dialog, which) -> {
                    completedLessons.clear(); wrongQuestionIds.clear(); starredWords.clear(); masteredWords.clear();
                    totalObjectiveAnswers = 0; correctObjectiveAnswers = 0; studyStreak = 0; lastStudyDate = ""; history = new JSONArray();
                    saveProgress(); render();
                }).show();
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception ignored) { toast("无法打开链接。"); }
    }

    private void addActionCard(LinearLayout parent, String heading, String detail, String action, Runnable callback) {
        LinearLayout item = card(parent);
        item.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        addText(copy, heading, 15, INK, true);
        addText(copy, detail, 12, MUTED, false);
        item.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button button = smallButton(action, false, callback);
        item.addView(button);
    }

    private void addSectionTitle(LinearLayout parent, String value) {
        TextView title = text(value, 17, INK, true);
        addViewWithTopMargin(parent, title, 16);
    }

    private void addBullet(LinearLayout parent, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView mark = text("•", 17, GREEN, true);
        row.addView(mark, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView content = text(value, 14, INK, false);
        row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addViewWithTopMargin(parent, row, 5);
    }

    private void addNumberedText(LinearLayout parent, int number, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView mark = text(String.valueOf(number), 14, GREEN, true);
        mark.setGravity(Gravity.CENTER);
        row.addView(mark, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView content = text(value, 14, INK, false);
        row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addViewWithTopMargin(parent, row, 7);
    }

    private void addEmpty(LinearLayout parent, String message) {
        LinearLayout empty = card(parent);
        addText(empty, message, 14, MUTED, false);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        card.setBackground(roundRect(Color.WHITE, 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(9);
        parent.addView(card, params);
        return card;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setLineSpacing(dp(2), 1f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(2), 0, dp(2));
        return view;
    }

    private void addText(LinearLayout parent, String value, float size, int color, boolean bold) {
        TextView view = text(value, size, color, bold);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(5);
        parent.addView(view, params);
    }

    private void addViewWithTopMargin(LinearLayout parent, View view, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(marginDp);
        params.bottomMargin = dp(4);
        parent.addView(view, params);
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private Button primaryButton(String label, Runnable action) { return makeButton(label, true, action); }
    private Button secondaryButton(String label, Runnable action) { return makeButton(label, false, action); }
    private Button smallButton(String label, boolean selected, Runnable action) {
        Button button = makeButton(label, selected, action);
        button.setTextSize(13);
        button.setMinHeight(dp(38));
        button.setPadding(dp(10), dp(5), dp(10), dp(5));
        return button;
    }

    private Button makeButton(String label, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinHeight(dp(46));
        button.setPadding(dp(14), dp(7), dp(14), dp(7));
        button.setTextColor(primary ? Color.WHITE : GREEN);
        button.setBackground(roundRect(primary ? GREEN : GREEN_PALE, 7));
        if (action != null) button.setOnClickListener(view -> action.run());
        return button;
    }

    private EditText editText(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        input.setHint(hint);
        input.setInputType(inputType);
        input.setTextSize(15);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(roundRect(Color.WHITE, 7));
        input.setBackgroundTintList(null);
        return input;
    }

    private Spinner spinner(List<String> options) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }

    private static final class HorizontalScrollViewCompat {
        static void addTo(LinearLayout parent, TextView child) {
            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(parent.getContext());
            scroll.setHorizontalScrollBarEnabled(true);
            scroll.addView(child, new android.widget.HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = Math.round(8 * parent.getResources().getDisplayMetrics().density);
            parent.addView(scroll, params);
        }
    }
}
