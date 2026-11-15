"""Run the actual Java parser on all checked HTML sources (local-only test data)."""
import json
import os
import subprocess
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TMP = ROOT / 'tmp' / 'parser-test'
TMP.mkdir(parents=True, exist_ok=True)
jar = TMP / 'json-20250517.jar'
if not jar.exists():
    jar.write_bytes(urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar', timeout=40).read())
harness = TMP / 'ParserCheck.java'
harness.write_text('''package com.cet4.focus;
import java.nio.file.*;
import org.json.*;
public class ParserCheck {
 public static void main(String[] args) throws Exception {
  Path root = Paths.get(args[0]);
  JSONObject manifest = new JSONObject(Files.readString(root.resolve("data/reading-sources.json")));
  JSONArray all = new JSONArray();
  for (Object entry : manifest.getJSONArray("exams")) {
   JSONObject exam = (JSONObject) entry;
   String html = Files.readString(root.resolve("tmp/cet4-sources/" + exam.getString("id") + ".html"));
   JSONArray items = ReadingPaperParser.parse(html, exam);
   for (Object item : items) all.put(item);
  }
  Files.writeString(root.resolve("tmp/parser-test/java-reading.json"), all.toString());
  JSONObject bad = new JSONObject(manifest.getJSONArray("exams").getJSONObject(0).toString());
  bad.put("id", "202312-2");
  try {
   ReadingPaperParser.parse(Files.readString(root.resolve("tmp/cet4-sources/202312-2.html")), bad);
   throw new AssertionError("Missing paragraph was accepted");
  } catch (java.io.IOException expected) { }
  System.out.println("Parsed " + all.length() + " questions; rejected missing paragraph");
 }
}''', encoding='utf-8')
jdk = Path(os.environ.get('JAVA_HOME', 'E:/develop/jdk-25.02')) / 'bin'
source = ROOT / 'android-app/app/src/main/java/com/cet4/focus/ReadingPaperParser.java'
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(TMP), str(source), str(harness)], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(TMP) + ';' + str(jar), 'com.cet4.focus.ParserCheck', str(ROOT)], check=True)
expected = {q['id']: q for q in json.loads((ROOT / 'tmp/cet4-sources/verified-reading.json').read_text(encoding='utf-8'))}
actual = json.loads((TMP / 'java-reading.json').read_text(encoding='utf-8'))
assert len(actual) == len(expected) == 240
for q in actual:
    ref = expected[q['id']]
    for key in ['questionNumber', 'type', 'passageId', 'passage', 'wordBank', 'blanks', 'acceptedAnswers', 'paragraphs', 'statements', 'options', 'answer']:
        assert q.get(key) == ref.get(key), (q['id'], key)
    if q['type'] == 'reading-detail':
        assert q['prompt'] == ref['prompt'], (q['id'], 'prompt')
assert any(p['id'] > 'D' for q in actual if q['type'] == 'reading-matching' for p in q['paragraphs'])
print('PASS: 240 Java questions match independently parsed source fields; all question types and paragraph choices covered.')

# Minimal Context/AssetManager test doubles; repository/parser remain production code.
(TMP / 'Context.java').write_text('''package android.content;
import java.io.File;
import android.content.res.AssetManager;
public class Context {
 private final File root, files;
 public Context(File root, File files) { this.root=root; this.files=files; }
 public Context getApplicationContext() { return this; }
 public File getFilesDir() { return files; }
 public AssetManager getAssets() { return new AssetManager(root); }
}''', encoding='utf-8')
(TMP / 'AssetManager.java').write_text('''package android.content.res;
import java.io.*;
public class AssetManager {
 private final File root;
 public AssetManager(File root) { this.root=root; }
 public InputStream open(String path) throws IOException { return new FileInputStream(new File(root,path)); }
}''', encoding='utf-8')
(TMP / 'RepositoryCheck.java').write_text('''package com.cet4.focus;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import org.json.*;
public class RepositoryCheck {
 public static void main(String[] args) throws Exception {
  Path root=Paths.get(args[0]);
  URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
   protected URLConnection openConnection(URL url) throws IOException {
    if (!url.getHost().equals("eol.nju.edu.cn") || !url.getPath().startsWith("/cet4/papers/") || !url.getPath().endsWith(".html")) throw new IOException("Network disabled in test");
    byte[] data=Files.readAllBytes(root.resolve("tmp/cet4-sources/"+url.getPath().substring(url.getPath().lastIndexOf('/')+1)));
    return new HttpURLConnection(url) {
     public void connect() {}
     public void disconnect() {}
     public boolean usingProxy() { return false; }
     public int getResponseCode() { return 200; }
     public InputStream getInputStream() { return new ByteArrayInputStream(data); }
    };
   }
  } : null);
  Path files=Files.createTempDirectory(root.resolve("tmp/parser-test"), "repository-");
  android.content.Context context=new android.content.Context(root.toFile(),files.toFile());
  StudyRepository repository=new StudyRepository(context);
  JSONArray exams=new JSONObject(Files.readString(root.resolve("data/reading-sources.json"))).getJSONArray("exams");
  for(Object entry:exams) if(repository.downloadReadingPaper((JSONObject)entry)!=30) throw new AssertionError("Incomplete download");
  StudyRepository restarted=new StudyRepository(context);
  if(restarted.savedReadingQuestions().length()!=240) throw new AssertionError("Offline restart lost data");
  JSONObject bad=new JSONObject(exams.getJSONObject(0).toString());
  bad.put("sourceSha256","0".repeat(64));
  try { restarted.downloadReadingPaper(bad); throw new AssertionError("Hash mismatch accepted"); } catch(IOException expected) {}
  if(restarted.savedReadingQuestions().length()!=240) throw new AssertionError("Failed download overwrote good cache");
  bad=new JSONObject(exams.getJSONObject(0).toString()).put("sourceUrl","https://example.com/other");
  try { restarted.downloadReadingPaper(bad); throw new AssertionError("Unexpected source accepted"); } catch(IOException expected) {}
  Path cache=files.resolve("content/reading-papers/"+exams.getJSONObject(0).getString("id")+".json");
  Files.writeString(cache,"[]");
  if(restarted.savedReadingQuestions().length()!=210) throw new AssertionError("Bad local cache counted");
  restarted.downloadReadingPaper(exams.getJSONObject(0));
  if(new StudyRepository(context).savedReadingQuestions().length()!=240) throw new AssertionError("Retry did not restore paper");
  System.out.println("PASS: all 8 papers saved/reloaded offline; source hash and URL rejected; existing data preserved; corrupt cache excluded and retry recovered.");
 }
}''', encoding='utf-8')
repo_source = source.with_name('StudyRepository.java')
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '-cp', str(jar) + os.pathsep + str(TMP), '-d', str(TMP), str(repo_source), str(TMP/'Context.java'), str(TMP/'AssetManager.java'), str(TMP/'RepositoryCheck.java')], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(TMP) + os.pathsep + str(jar), 'com.cet4.focus.RepositoryCheck', str(ROOT)], check=True)
