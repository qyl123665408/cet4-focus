package com.cet4.focus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local-only PDF reader. Book text is never fetched from reading platforms. */
public class BookReaderActivity extends Activity {
    private static final int PICK_PDF = 710;
    private static final int INK = 0xff1d2722;
    private static final int GREEN = 0xff2a744f;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LinearLayout root;
    private TextView status;
    private PageView pageView;
    private ScrollView scroll;
    private JSONObject course;
    private JSONArray notes = new JSONArray();
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private int pageIndex;
    private int totalPages;
    private int firstBodyPage = 1;
    private volatile int renderTicket;
    private String bookHash = "";
    private boolean busy;
    private boolean marking;
    private volatile boolean closed;
    private float zoom = 1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("book_reader", MODE_PRIVATE);
        try { course = new StudyRepository(this).readJson("data/modules/x64-assembly.json"); }
        catch (Exception e) { course = new JSONObject(); }
        buildLayout();
        if (new File(getFilesDir(), "assembly-book.pdf").isFile()) openStoredBook(state == null);
        else showEmpty();
    }

    private void buildLayout() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(6));
        root.setBackgroundColor(0xfff4f7f4);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(10) + insets.getSystemWindowInsetLeft(), dp(10) + insets.getSystemWindowInsetTop(), dp(10) + insets.getSystemWindowInsetRight(), dp(6) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        LinearLayout top = row();
        button(top, "返回", this::finish);
        button(top, "导入原书", this::pickBook);
        button(top, "原书来源", this::showSources);
        root.addView(top);
        status = label("正在打开原书…", 14);
        root.addView(status);
        LinearLayout tools = row();
        button(tools, "目录", this::showChapters);
        button(tools, "页码", this::jumpToPage);
        button(tools, "重点", this::toggleMarking);
        button(tools, "笔记", this::showNotes);
        button(tools, "讲解", this::showExplanation);
        root.addView(tools);
        scroll = new ScrollView(this);
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        pageView = new PageView();
        horizontal.addView(pageView);
        scroll.addView(horizontal);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = row();
        button(bottom, "上一页", () -> navigate(-1));
        button(bottom, "缩小", () -> resize(-0.5f));
        button(bottom, "放大", () -> resize(0.5f));
        button(bottom, "下一页", () -> navigate(1));
        root.addView(bottom);
    }

    private void showEmpty() {
        status.setText("尚未导入原书。当前 App 的 43 章是辅助笔记，不是教材全文。请导入你有权使用的完整 PDF，之后可离线阅读、框选重点和写批注。");
    }

    private void showSources() {
        new AlertDialog.Builder(this).setTitle("原书获取渠道")
            .setMessage("已核对清华大学出版社、文泉书局与微信读书的中文版入口。平台阅读权限不等于可导出的 PDF，也不等于公开再分发许可。原书不会随 APK 上传 GitHub；导入的文件和批注只保存在本机。")
            .setPositiveButton("出版社", (d, w) -> browse("https://www.tup.tsinghua.edu.cn/booksCenter/book_09191001.html"))
            .setNeutralButton("文泉书局", (d, w) -> browse("https://wqbook.wqxuetang.com/book/3226907"))
            .setNegativeButton("微信读书", (d, w) -> browse("https://weread.qq.com/web/bookDetail/83132b50811e7a10dg019c07")).show();
    }

    private void browse(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { toast("没有可打开链接的应用"); }
    }

    private void pickBook() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/pdf");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, PICK_PDF); }
        catch (Exception e) { toast("没有可用的文件选择器"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_PDF || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = "已导入的原书.pdf";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) name = cursor.getString(col);
            }
        } catch (Exception ignored) { }
        final String displayName = name;
        busy = true;
        renderTicket++;
        status.setText("正在导入并验证 PDF，请稍候…");
        worker.execute(() -> {
            File temporary = new File(getFilesDir(), "assembly-book.importing");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(temporary)) {
                    if (input == null) throw new java.io.IOException("无法读取文件");
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); digest.update(buffer, 0, count); }
                    output.getFD().sync();
                }
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer test = new PdfRenderer(fd)) {
                    if (test.getPageCount() < 1) throw new java.io.IOException("PDF 没有页面");
                }
                StringBuilder hash = new StringBuilder();
                for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                closeRenderer();
                android.system.Os.rename(temporary.getAbsolutePath(), new File(getFilesDir(), "assembly-book.pdf").getAbsolutePath());
                String sourceHash = hash.toString();
                prefs.edit().putString("hash", sourceHash).putString("name", displayName).apply();
                runOnUiThread(() -> { if (!closed) { busy = false; openStoredBook(false); } });
            } catch (Exception e) {
                temporary.delete();
                runOnUiThread(() -> { if (!closed) { busy = false; toast("导入失败：请使用未加密且可正常打开的 PDF。原文件未替换。"); if (renderer == null && new File(getFilesDir(), "assembly-book.pdf").isFile()) openStoredBook(false); else updateStatus(); } });
            }
        });
    }

    private void openStoredBook(boolean useRequestedChapter) {
        busy = true;
        worker.execute(() -> {
            try {
                closeRenderer();
                descriptor = ParcelFileDescriptor.open(new File(getFilesDir(), "assembly-book.pdf"), ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(descriptor);
                int count = renderer.getPageCount();
                runOnUiThread(() -> {
                    if (closed) return;
                    totalPages = count;
                    bookHash = prefs.getString("hash", "legacy");
                    firstBodyPage = prefs.getInt("offset_" + bookHash, 1);
                    try { notes = new JSONArray(prefs.getString("notes_" + bookHash, "[]")); } catch (Exception e) { notes = new JSONArray(); }
                    pageIndex = Math.max(0, Math.min(count - 1, prefs.getInt("page_" + bookHash, 0)));
                    if (useRequestedChapter) {
                        JSONObject lesson = findLesson(getIntent().getStringExtra("lessonId"));
                        if (lesson != null) pageIndex = Math.min(count - 1, firstPage(lesson) + firstBodyPage - 2);
                    }
                    busy = false;
                    requestRender();
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!closed) { busy = false; status.setText("原书无法打开，请重新导入完整、未加密的 PDF。"); } }); }
        });
    }

    private void requestRender() {
        if (busy || renderer == null || closed) return;
        final int requestedPage = pageIndex;
        final int ticket = ++renderTicket;
        final int width = Math.min(2200, Math.max(400, Math.round(getResources().getDisplayMetrics().widthPixels * zoom)));
        pageView.bitmap = null;
        pageView.invalidate();
        updateStatus();
        prefs.edit().putInt("page_" + bookHash, pageIndex).apply();
        worker.execute(() -> {
            if (closed || ticket != renderTicket) return;
            Bitmap bitmap = null;
            try (PdfRenderer.Page pdfPage = renderer.openPage(requestedPage)) {
                int height = Math.min(7000, Math.max(1, Math.round(width * (float) pdfPage.getHeight() / pdfPage.getWidth())));
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                new Canvas(bitmap).drawColor(Color.WHITE);
                pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                Bitmap rendered = bitmap;
                runOnUiThread(() -> {
                    if (closed || ticket != renderTicket) { rendered.recycle(); return; }
                    pageView.bitmap = rendered;
                    pageView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(rendered.getWidth(), rendered.getHeight()));
                    pageView.invalidate();
                    scroll.scrollTo(0, 0);
                });
            } catch (Exception | OutOfMemoryError e) {
                if (bitmap != null) bitmap.recycle();
                runOnUiThread(() -> { if (!closed && ticket == renderTicket) status.setText("此页无法显示，请缩小或换一份可正常打开的 PDF。"); });
            }
        });
    }

    private void updateStatus() {
        if (totalPages == 0) { showEmpty(); return; }
        int printed = pageIndex - firstBodyPage + 2;
        status.setText(prefs.getString("name", "原书") + "  · PDF " + (pageIndex + 1) + " / " + totalPages + " 页" + (printed > 0 ? " · 正文 " + printed + " 页" : " · 前置页") + (marking ? " · 在页面拖动框选重点" : ""));
    }

    private void navigate(int delta) {
        if (busy || totalPages == 0) return;
        pageIndex = Math.max(0, Math.min(totalPages - 1, pageIndex + delta));
        requestRender();
    }

    private void resize(float delta) { zoom = Math.max(1, Math.min(3, zoom + delta)); requestRender(); }
    private void toggleMarking() { marking = !marking; updateStatus(); if (marking) toast("在原书上拖动框选重点，再填写批注。缩放不会改变标记位置。"); }

    private void jumpToPage() {
        if (totalPages == 0 || busy) { toast("请先导入原书"); return; }
        numberDialog("跳到 PDF 第几页（1—" + totalPages + "）", pageIndex + 1, 1, totalPages, value -> { pageIndex = value - 1; requestRender(); }, "校准正文页码", this::calibrate);
    }

    private interface NumberAction { void accept(int value); }
    private void numberDialog(String title, int initial, int min, int max, NumberAction action, String neutral, Runnable extra) {
        EditText input = new EditText(this); input.setInputType(InputType.TYPE_CLASS_NUMBER); input.setText(String.valueOf(initial)); input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("确定", null).setNegativeButton("取消", null).create();
        if (neutral != null) dialog.setButton(AlertDialog.BUTTON_NEUTRAL, neutral, (d,w) -> extra.run());
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { int n = Integer.parseInt(input.getText().toString()); if (n < min || n > max) throw new NumberFormatException(); action.accept(n); dialog.dismiss(); }
            catch (NumberFormatException e) { input.setError("请输入 " + min + " 到 " + max + " 之间的页码"); }
        }));
        dialog.show();
    }

    private void calibrate() {
        numberDialog("原书正文第 1 页位于 PDF 第几页？", firstBodyPage, 1, totalPages, n -> { firstBodyPage = n; prefs.edit().putInt("offset_" + bookHash, n).apply(); updateStatus(); }, null, null);
    }

    private int firstPage(JSONObject lesson) { try { return Integer.parseInt(lesson.optString("pages", "1-1").split("-")[0]); } catch (Exception e) { return 1; } }
    private JSONObject findLesson(String id) {
        JSONArray lessons = course.optJSONArray("lessons");
        if (id == null || lessons == null) return null;
        for (int i = 0; i < lessons.length(); i++) { JSONObject lesson = lessons.optJSONObject(i); if (lesson != null && id.equals(lesson.optString("id"))) return lesson; }
        return null;
    }

    private void showChapters() {
        if (totalPages == 0 || busy) { toast("请先导入原书"); return; }
        JSONArray lessons = course.optJSONArray("lessons");
        if (lessons == null) return;
        String[] labels = new String[lessons.length() + 1];
        labels[0] = "先校准正文页码（中文第一版目录）";
        for (int i = 0; i < lessons.length(); i++) { JSONObject lesson = lessons.optJSONObject(i); labels[i + 1] = lesson == null ? "" : (i + 1) + ". " + lesson.optString("title"); }
        new AlertDialog.Builder(this).setTitle("原书章节定位").setItems(labels, (d,w) -> {
            if (w == 0) { calibrate(); return; }
            JSONObject lesson = lessons.optJSONObject(w - 1);
            int target = firstPage(lesson) + firstBodyPage - 2;
            if (target >= totalPages) { toast("该章节超出此 PDF 页数，请确认导入的是完整原书且页码校准正确。"); return; }
            pageIndex = target; requestRender();
        }).show();
    }

    private void showExplanation() {
        if (totalPages == 0 || busy) { toast("请先导入原书并校准正文页码"); return; }
        int printedPage = pageIndex - firstBodyPage + 2;
        JSONArray lessons = course.optJSONArray("lessons");
        JSONObject selected = null;
        if (lessons != null) for (int i = 0; i < lessons.length(); i++) {
            JSONObject lesson = lessons.optJSONObject(i);
            if (lesson == null) continue;
            try { String[] range = lesson.getString("pages").split("-"); if (printedPage >= Integer.parseInt(range[0]) && printedPage <= Integer.parseInt(range[1])) { selected = lesson; break; } } catch (Exception ignored) { }
        }
        if (selected == null) { toast("本页没有对应章节讲解；请校准正文页码，或从目录选择章节。"); return; }
        StringBuilder text = new StringBuilder("以下为独立编写的章节辅助讲解，非原书原文，也不是自动识别本页内容。请先确认 PDF 版本和正文页码。");
        text.append((char) 10).append((char) 10).append("学习目标：").append(selected.optString("summary"));
        append(text, "知识重点", selected.optJSONArray("points"));
        JSONObject guides = course.optJSONObject("lessonGuides");
        JSONObject guide = guides == null ? null : guides.optJSONObject(selected.optString("id"));
        if (guide != null) { append(text, "理解与讲解", guide.optJSONArray("explain")); append(text, "例题推演", guide.optJSONArray("steps")); }
        JSONObject example = selected.optJSONObject("example");
        if (example != null) text.append((char) 10).append((char) 10).append("代码示例：").append((char) 10).append(example.optString("code")).append((char) 10).append(example.optString("explanation"));
        ScrollView content = new ScrollView(this); TextView view = label(text.toString(), 15); view.setTextIsSelectable(true); content.addView(view);
        new AlertDialog.Builder(this).setTitle(selected.optString("title")).setView(content).setPositiveButton("回到原书", null).show();
    }

    private void append(StringBuilder text, String title, JSONArray paragraphs) {
        if (paragraphs == null) return;
        text.append((char) 10).append((char) 10).append(title);
        for (int i = 0; i < paragraphs.length(); i++) text.append((char) 10).append((char) 10).append(paragraphs.optString(i));
    }

    private void addNote(RectF rect) {
        final int notePage = pageIndex;
        EditText input = new EditText(this); input.setHint("为什么重要？哪里还不懂？"); input.setMinLines(3);
        new AlertDialog.Builder(this).setTitle("PDF 第 " + (notePage + 1) + " 页 · 重点批注").setView(input).setPositiveButton("保存重点", (d,w) -> {
            try {
                JSONObject note = new JSONObject(); note.put("page", notePage); note.put("left", rect.left); note.put("top", rect.top); note.put("right", rect.right); note.put("bottom", rect.bottom);
                String value = input.getText().toString().trim(); note.put("text", value.isEmpty() ? "重点" : value); notes.put(note); saveNotes();
            } catch (Exception e) { toast("批注保存失败"); }
        }).setNegativeButton("取消", null).show();
    }

    private void saveNotes() { prefs.edit().putString("notes_" + bookHash, notes.toString()).apply(); pageView.invalidate(); }
    private void showNotes() {
        if (busy) { toast("原书正在加载，请稍候"); return; }
        if (notes.length() == 0) { toast("还没有重点；点击「重点」，在页面上拖动框选。"); return; }
        List<Integer> order = new ArrayList<>(); for (int i = 0; i < notes.length(); i++) order.add(i);
        java.util.Collections.sort(order, (a,b) -> Integer.compare(notes.optJSONObject(a).optInt("page"), notes.optJSONObject(b).optInt("page")));
        String[] labels = new String[order.size()];
        for (int i = 0; i < order.size(); i++) { JSONObject note = notes.optJSONObject(order.get(i)); labels[i] = "PDF " + (note.optInt("page") + 1) + " 页 · " + note.optString("text"); }
        new AlertDialog.Builder(this).setTitle("原书重点与批注").setItems(labels, (d,w) -> {
            int index = order.get(w); JSONObject note = notes.optJSONObject(index);
            EditText input = new EditText(this); input.setText(note.optString("text")); input.setMinLines(3);
            new AlertDialog.Builder(this).setTitle("重点批注").setView(input).setPositiveButton("保存并定位", (a,b) -> { try { note.put("text", input.getText().toString()); saveNotes(); pageIndex = note.optInt("page"); requestRender(); } catch (Exception ignored) { } })
                .setNeutralButton("删除重点", (a,b) -> { notes.remove(index); saveNotes(); }).setNegativeButton("取消", null).show();
        }).show();
    }

    private class PageView extends View {
        Bitmap bitmap;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF selection;
        float startX, startY;
        PageView() { super(BookReaderActivity.this); setMinimumHeight(dp(350)); setContentDescription("原书 PDF 页面，可放大阅读并框选重点"); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.drawColor(Color.WHITE);
            if (bitmap == null) return;
            paint.setAlpha(255);
            canvas.drawBitmap(bitmap, 0, 0, paint);
            paint.setColor(0x66ffd54f);
            for (int i = 0; i < notes.length(); i++) {
                JSONObject note = notes.optJSONObject(i); if (note == null || note.optInt("page", -1) != pageIndex) continue;
                canvas.drawRect((float) note.optDouble("left") * getWidth(), (float) note.optDouble("top") * getHeight(), (float) note.optDouble("right") * getWidth(), (float) note.optDouble("bottom") * getHeight(), paint);
            }
            if (selection != null) canvas.drawRect(selection, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!marking || bitmap == null || busy) return super.onTouchEvent(event);
            float x = Math.max(0, Math.min(getWidth(), event.getX())); float y = Math.max(0, Math.min(getHeight(), event.getY()));
            if (event.getAction() == MotionEvent.ACTION_DOWN) { getParent().requestDisallowInterceptTouchEvent(true); startX = x; startY = y; selection = new RectF(x,y,x,y); return true; }
            if (event.getAction() == MotionEvent.ACTION_MOVE) { selection = new RectF(Math.min(startX,x),Math.min(startY,y),Math.max(startX,x),Math.max(startY,y)); invalidate(); return true; }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                performClick(); getParent().requestDisallowInterceptTouchEvent(false);
                RectF rect = new RectF(Math.min(startX,x),Math.min(startY,y),Math.max(startX,x),Math.max(startY,y));
                selection = null; invalidate();
                if (rect.width() > dp(5) && rect.height() > dp(3)) addNote(new RectF(rect.left/getWidth(),rect.top/getHeight(),rect.right/getWidth(),rect.bottom/getHeight()));
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) { selection = null; invalidate(); getParent().requestDisallowInterceptTouchEvent(false); return true; }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }

    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); return row; }
    private TextView label(String value, int size) { TextView text = new TextView(this); text.setText(value); text.setTextColor(INK); text.setTextSize(size); text.setPadding(dp(8),dp(8),dp(8),dp(8)); return text; }
    private void button(LinearLayout parent, String title, Runnable action) { Button button = new Button(this); button.setText(title); button.setTextSize(12); button.setTextColor(GREEN); button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(2),0,dp(2),0); button.setOnClickListener(v -> action.run()); parent.addView(button,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1)); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_LONG).show(); }
    private void closeRenderer() { try { if (renderer != null) renderer.close(); } catch (Exception ignored) { } renderer = null; try { if (descriptor != null) descriptor.close(); } catch (Exception ignored) { } descriptor = null; }
    @Override protected void onDestroy() { closed = true; renderTicket++; worker.execute(this::closeRenderer); worker.shutdown(); super.onDestroy(); }
}
