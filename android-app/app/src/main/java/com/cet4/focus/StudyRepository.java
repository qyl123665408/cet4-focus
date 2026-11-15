package com.cet4.focus;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StudyRepository {
    private static final String CONTENT_BASE = "https://raw.githubusercontent.com/qyl123665408/cet4-focus/main/";
    private static final String RELEASE_API = "https://api.github.com/repos/qyl123665408/cet4-focus/releases/latest";
    private final Context context;
    private final File contentDirectory;

    StudyRepository(Context context) {
        this.context = context.getApplicationContext();
        this.contentDirectory = new File(this.context.getFilesDir(), "content");
    }

    JSONObject readJson(String path) throws IOException, JSONException {
        File saved = contentFile(path);
        if (saved.isFile()) return new JSONObject(readFile(saved));
        try (InputStream input = context.getAssets().open(path)) {
            return new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
        }
    }

    JSONArray readArray(String path) throws IOException, JSONException {
        File saved = contentFile(path);
        if (saved.isFile()) return new JSONArray(readFile(saved));
        try (InputStream input = context.getAssets().open(path)) {
            return new JSONArray(new String(readAll(input), StandardCharsets.UTF_8));
        }
    }

    String updateContent() throws IOException, JSONException {
        String currentVersion = readJson("data/version.json").optString("dataVersion", "0");
        JSONObject remoteVersion = new JSONObject(download(CONTENT_BASE + "data/version.json"));
        String newVersion = remoteVersion.optString("dataVersion", "0");
        if (compareVersions(newVersion, currentVersion) <= 0) return null;

        LinkedHashMap<String, String> updates = new LinkedHashMap<>();
        updates.put("data/questions.json", download(CONTENT_BASE + "data/questions.json"));
        updates.put("data/vocab.json", download(CONTENT_BASE + "data/vocab.json"));
        String indexText = download(CONTENT_BASE + "data/modules/index.json");
        updates.put("data/modules/index.json", indexText);
        JSONArray modules = new JSONObject(indexText).optJSONArray("modules");
        if (modules != null) {
            for (int i = 0; i < modules.length(); i++) {
                JSONObject module = modules.optJSONObject(i);
                if (module == null || !"course".equals(module.optString("kind"))) continue;
                String path = safeContentPath(module.optString("file"));
                if (path != null && !updates.containsKey(path)) updates.put(path, download(CONTENT_BASE + path));
            }
        }
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            String value = entry.getValue().trim();
            if (value.startsWith("[")) new JSONArray(value);
            else new JSONObject(value);
        }
        for (Map.Entry<String, String> entry : updates.entrySet()) saveAtomically(entry.getKey(), entry.getValue());
        saveAtomically("data/version.json", remoteVersion.toString());
        return newVersion;
    }

    JSONObject latestRelease() throws IOException, JSONException {
        HttpURLConnection connection = openConnection(RELEASE_API);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            return new JSONObject(readResponse(connection));
        } finally {
            connection.disconnect();
        }
    }

    static int compareVersions(String left, String right) {
        String[] a = left.replaceFirst("^[vV]", "").split("[.-]");
        String[] b = right.replaceFirst("^[vV]", "").split("[.-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? numericPart(a[i]) : 0;
            int y = i < b.length ? numericPart(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int numericPart(String part) {
        String digits = part.replaceAll("[^0-9].*$", "");
        try { return digits.isEmpty() ? 0 : Integer.parseInt(digits); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String safeContentPath(String path) {
        if (path == null || !path.startsWith("data/") || path.contains("..") || path.contains("\\")) return null;
        return path;
    }

    private File contentFile(String path) throws IOException {
        String safePath = safeContentPath(path);
        if (safePath == null) throw new IOException("Invalid content path");
        File file = new File(contentDirectory, safePath.substring("data/".length()));
        String root = contentDirectory.getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(root)) throw new IOException("Invalid content path");
        return file;
    }

    private void saveAtomically(String path, String text) throws IOException {
        File destination = contentFile(path);
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create content directory");
        File temporary = new File(destination.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) throw new IOException("Cannot replace saved content");
        if (!temporary.renameTo(destination)) throw new IOException("Cannot save downloaded content");
    }

    private static String download(String url) throws IOException {
        HttpURLConnection connection = openConnection(url);
        try { return readResponse(connection); }
        finally { connection.disconnect(); }
    }

    private static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("User-Agent", "Study-Android-App");
        return connection;
    }

    private static String readResponse(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
        try (InputStream input = connection.getInputStream()) {
            return new String(readAll(input), StandardCharsets.UTF_8);
        }
    }

    private static String readFile(File file) throws IOException {
        try (InputStream input = new java.io.FileInputStream(file)) {
            return new String(readAll(input), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
