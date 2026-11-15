package com.cet4.focus;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native tutor. Conversation lives in this screen and is never included in progress backups. */
public class AiTutorActivity extends Activity {
    private static final int GREEN = Color.rgb(42, 116, 79);
    private LinearLayout content;
    private ScrollView scroll;
    private EditText followup;
    private Button send, settings;
    private TextView status;
    private JSONArray messages = new JSONArray();
    private String questionContext = "";
    private AiSettingsStore store;
    private AiClient activeClient;
    private int requestId;
    private boolean settingsOnly;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        store = new AiSettingsStore(this);
        settingsOnly = getIntent().getBooleanExtra("settings_only", false);
        questionContext = getIntent().getStringExtra("question_context");
        if (questionContext == null) questionContext = "";
        if (state != null) {
            try { messages = new JSONArray(state.getString("messages", "[]")); }
            catch (Exception ignored) { messages = new JSONArray(); }
        }
        buildScreen();
        if (settingsOnly && state == null) showSettings();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundColor(Color.rgb(244, 247, 244));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(16), dp(12) + insets.getSystemWindowInsetTop(), dp(16), dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        LinearLayout header = new LinearLayout(this);
        header.addView(button("返回", this::finish));
        TextView heading = label(settingsOnly ? "AI 设置" : "AI 题目讲解", 20, true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        settings = button("设置", this::showSettings);
        header.addView(settings); root.addView(header);
        TextView notice = label("支持 OpenAI 兼容的 Chat Completions 接口。点击发送才会把本题、作答和本页对话发送到你填写的服务商，费用由对应 API 账户承担。AI 内容仅供学习参考。", 13, false);
        root.addView(notice);
        scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label("", 13, false); root.addView(status);
        if (settingsOnly) {
            content.addView(label("保存接口后，回到题目提交答案，即可点“AI 讲解 / 追问”。支持阅读定位、选词填空语法分析及写作翻译修改建议。", 16, false));
            content.addView(button("配置 AI 接口", this::showSettings));
            return;
        }
        redrawMessages();
        followup = new EditText(this);
        followup.setHint("输入追问，例如：为什么不能选 B？");
        followup.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        followup.setMinLines(2); followup.setMaxLines(4); root.addView(followup);
        LinearLayout actions = new LinearLayout(this);
        send = button(messages.length() == 0 ? "讲解这道题" : "发送追问", this::sendQuestion);
        actions.addView(send, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(button("取消请求", () -> { cancelRequest(); status.setText("已取消。本次提问未加入对话。"); }));
        root.addView(actions);
    }

    private void redrawMessages() {
        content.removeAllViews();
        if (messages.length() == 0) content.addView(label("已带入当前题目、文章、选项、参考答案及你的作答。点击“讲解这道题”开始，也可以先输入你最想弄懂的问题。", 16, false));
        for (int i = 2; i < messages.length(); i++) {
            JSONObject item = messages.optJSONObject(i);
            if (item == null) continue;
            content.addView(label("assistant".equals(item.optString("role")) ? "AI 讲解" : "我的提问", 14, true));
            TextView text = label(item.optString("content"), 16, false);
            text.setTextIsSelectable(true); content.addView(text);
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void sendQuestion() {
        if (activeClient != null) return;
        AiSettingsStore.Settings config;
        try {
            config = store.load();
            AiClient.validate(config.address, config.model, config.key);
        } catch (Exception error) { status.setText("请先配置可用的接口、模型和 API 密钥。"); showSettings(); return; }
        String input = followup.getText().toString().trim();
        if (input.isEmpty() && messages.length() != 0) { status.setText("请填写追问内容。"); return; }
        if (input.length() > 5000) { status.setText("每次追问请控制在 5000 字以内。"); return; }
        if (messages.length() >= 22) { status.setText("本页对话已达 10 轮，请返回题目重新打开讲解。"); return; }
        JSONArray pending;
        try {
            pending = new JSONArray(messages.toString());
            if (pending.length() == 0) {
                pending.put(new JSONObject().put("role", "system").put("content", AiPrompt.SYSTEM));
                pending.put(new JSONObject().put("role", "user").put("content", questionContext));
            }
            pending.put(new JSONObject().put("role", "user").put("content", input.isEmpty() ? "请详细讲解这道题，说明正确答案依据、我作答的问题及相关词汇语法。" : input));
        } catch (Exception error) { status.setText("无法生成提问，请返回题目重试。"); return; }
        AiClient client = new AiClient(); activeClient = client; final int token = ++requestId;
        send.setEnabled(false); settings.setEnabled(false); followup.setEnabled(false);
        status.setText("正在请求 AI，请稍候；可以取消。");
        new Thread(() -> {
            String result = null, failure = null;
            try { result = client.complete(config.address, config.model, config.key, pending, false); }
            catch (Exception error) { failure = safeError(error); }
            final String reply = result, problem = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || token != requestId) return;
                activeClient = null; send.setEnabled(true); settings.setEnabled(true); followup.setEnabled(true);
                if (problem != null) { status.setText(problem); return; }
                try { pending.put(new JSONObject().put("role", "assistant").put("content", reply)); }
                catch (Exception ignored) { status.setText("无法保存本轮回复，请重试。"); return; }
                messages = pending; followup.setText(""); send.setText("发送追问");
                status.setText("AI 生成内容，请结合原文和参考答案判断。"); redrawMessages();
            });
        }, "study-ai-tutor").start();
    }

    private void showSettings() {
        if (activeClient != null) return;
        AiSettingsStore.Settings old;
        boolean readable = true;
        try { old = store.load(); }
        catch (Exception error) { old = new AiSettingsStore.Settings("", "", ""); readable = false; }
        final AiSettingsStore.Settings saved = old;
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), dp(8), dp(20), dp(8));
        fields.addView(label("填写你自己的服务商信息。Base URL 会自动补上 /chat/completions，也可直接填完整接口。仅支持 HTTPS。", 14, false));
        fields.addView(label("接口地址 / Base URL", 14, true));
        EditText address = field("https://你的服务商/v1", false); address.setText(saved.address); fields.addView(address);
        fields.addView(label("模型 ID（按服务商控制台填写）", 14, true));
        EditText model = field("服务商提供的模型名称", false); model.setText(saved.model); fields.addView(model);
        fields.addView(label("API Key", 14, true));
        EditText key = field(saved.key.isEmpty() ? "粘贴 API 密钥" : "已保存，留空保留；输入新密钥可替换", true); fields.addView(key);
        fields.addView(label("密钥用 Android Keystore 加密，仅保存在本机且不进入备份。更换接口地址需重新填入密钥。测试连接会发起一次简短请求。", 12, false));
        TextView feedback = label(readable ? "" : "原设置无法解密，请清除后重新配置。", 13, false); fields.addView(feedback);
        Button test = button("测试连接", () -> {}); fields.addView(test);
        Button clear = button("清除本机 AI 设置", () -> {}); fields.addView(clear);
        ScrollView wrapper = new ScrollView(this); wrapper.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("AI 接口设置").setView(wrapper)
                .setNegativeButton("关闭", null).setPositiveButton("保存", null).create();
        final AiClient[] testing = {null};
        dialog.setOnDismissListener(ignored -> {
            key.setText("");
            if (testing[0] != null) { testing[0].cancel(); if (activeClient == testing[0]) activeClient = null; }
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    AiSettingsStore.Settings value = settingsFromFields(address, model, key, saved);
                    store.save(value); status.setText("AI 设置已加密保存。"); dialog.dismiss();
                } catch (java.io.IOException error) { feedback.setText(error.getMessage()); }
                catch (Exception error) { feedback.setText("无法安全保存设置，请清除后重试。"); }
            });
            clear.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("清除 AI 设置？")
                    .setMessage("本机保存的接口、模型和密钥会被删除，不影响学习进度。")
                    .setNegativeButton("取消", null).setPositiveButton("清除", (prompt, which) -> {
                        try { store.clear(); dialog.dismiss(); status.setText("已清除本机 AI 设置。"); }
                        catch (Exception error) { feedback.setText("清除失败，请重试。"); }
                    }).show());
            test.setOnClickListener(view -> {
                AiSettingsStore.Settings value;
                try { value = settingsFromFields(address, model, key, saved); }
                catch (Exception error) { feedback.setText(safeError(error)); return; }
                AiClient client = new AiClient(); testing[0] = client; activeClient = client;
                test.setEnabled(false); clear.setEnabled(false); address.setEnabled(false); model.setEnabled(false); key.setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false); feedback.setText("正在测试，请稍候…");
                new Thread(() -> {
                    String failure = null;
                    try {
                        JSONArray probe = new JSONArray().put(new JSONObject().put("role", "user").put("content", "请只回复：连接成功"));
                        client.complete(value.address, value.model, value.key, probe, false);
                    } catch (Exception error) { failure = safeError(error); }
                    final String problem = failure;
                    runOnUiThread(() -> {
                        if (activeClient == client) activeClient = null; testing[0] = null;
                        if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                        test.setEnabled(true); clear.setEnabled(true); address.setEnabled(true); model.setEnabled(true); key.setEnabled(true);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        feedback.setText(problem == null ? "连接成功，模型已返回正文。点击“保存”完成配置。" : problem);
                    });
                }, "study-ai-test").start();
            });
        });
        dialog.show();
        dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
    }

    private AiSettingsStore.Settings settingsFromFields(EditText address, EditText model, EditText key, AiSettingsStore.Settings saved) throws java.io.IOException {
        String url = address.getText().toString().trim(), secret = key.getText().toString().trim();
        if (secret.isEmpty() && !saved.key.isEmpty()) {
            if (!AiClient.endpoint(url).equals(AiClient.endpoint(saved.address)))
                throw new AiClient.UserException("更换接口地址后，请重新填写要发给该服务商的密钥。");
            secret = saved.key;
        }
        String modelName = model.getText().toString().trim();
        AiClient.validate(url, modelName, secret);
        return new AiSettingsStore.Settings(url, modelName, secret);
    }

    private static String safeError(Exception error) {
        if (error instanceof java.net.SocketTimeoutException) return "连接或回复超时，请稍后重试。";
        if (error instanceof javax.net.ssl.SSLException) return "HTTPS 证书校验失败，请检查接口地址及手机时间。";
        if (error instanceof java.net.UnknownHostException || error instanceof java.net.ConnectException) return "无法连接服务，请检查网络和接口地址。";
        // Only locally-authored messages may be shown; never display provider bodies or raw network errors.
        String message = error.getMessage();
        if (error instanceof AiClient.UserException && message != null) return message;
        return "请求失败，请检查网络、接口和模型设置后重试。";
    }

    private void cancelRequest() {
        requestId++; if (activeClient != null) activeClient.cancel(); activeClient = null;
        if (send != null) send.setEnabled(true); if (settings != null) settings.setEnabled(true);
        if (followup != null) followup.setEnabled(true);
    }
    @Override protected void onDestroy() { cancelRequest(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        String history = messages.toString();
        if (history.length() < 120000) state.putString("messages", history);
        super.onSaveInstanceState(state);
    }
    private EditText field(String hint, boolean password) {
        EditText edit = new EditText(this); edit.setHint(hint); edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        edit.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return edit;
    }
    private TextView label(String value, int size, boolean bold) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(Color.rgb(29, 39, 34));
        text.setPadding(0, dp(6), 0, dp(6)); text.setLineSpacing(dp(3), 1f);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return text;
    }
    private Button button(String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setTextColor(GREEN);
        button.setOnClickListener(view -> action.run()); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
