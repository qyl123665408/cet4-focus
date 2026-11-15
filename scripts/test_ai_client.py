"""Exercise production AI protocol/prompt code on the JVM; no live API key required."""
import os
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
TMP = ROOT / 'tmp' / 'ai-test'
TMP.mkdir(parents=True, exist_ok=True)
jar = ROOT / 'tmp/parser-test/json-20250517.jar'
if not jar.exists():
    jar = TMP / 'json-20250517.jar'
    if not jar.exists():
        jar.write_bytes(urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar', timeout=40).read())
harness = TMP / 'AiClientCheck.java'
harness.write_text(r'''package com.cet4.focus;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public class AiClientCheck {
 static int status = 200, opens = 0;
 static String body = "{\"choices\":[{\"message\":{\"content\":\"答案依据：the passage.\"}}]}";
 static boolean timeout, blockRead;
 static Fake last;
 static void check(boolean okay, String detail) { if (!okay) throw new AssertionError(detail); }
 interface Action { void run() throws Exception; }
 static void reject(Action action, String detail) throws Exception {
  try { action.run(); throw new AssertionError("Accepted: "+detail); } catch(IOException expected) {}
 }
 static class Fake extends HttpURLConnection {
  ByteArrayOutputStream request = new ByteArrayOutputStream();
  volatile boolean disconnected;
  Fake(URL url) { super(url); }
  public void connect() {}
  public void disconnect() { disconnected = true; }
  public boolean usingProxy() { return false; }
  public OutputStream getOutputStream() { return request; }
  public int getResponseCode() throws IOException { if(timeout) throw new SocketTimeoutException(); return status; }
  public InputStream getInputStream() {
   if(blockRead) return new InputStream() {
    public int read() throws IOException {
     while(!disconnected) { try { Thread.sleep(5); } catch(InterruptedException e) { throw new IOException(); } }
     throw new IOException("cancelled fake transport");
    }
   };
   return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
  }
 }
 public static void main(String[] args) throws Exception {
  URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
   protected URLConnection openConnection(URL url) { opens++; return last = new Fake(url); }
  } : null);
  check(AiClient.endpoint("https://provider.example/v1/").equals("https://provider.example/v1/chat/completions"), "base path");
  check(AiClient.endpoint("https://provider.example/custom/chat/completions").equals("https://provider.example/custom/chat/completions"), "full endpoint");
  for(String invalid : new String[]{"http://provider.example","https://a:b@provider.example","https://provider.example?key=x","https://provider.example/#x","file:///x","bad url"})
   reject(() -> AiClient.endpoint(invalid), invalid);
  reject(() -> AiClient.validate("https://provider.example","","dummy"), "empty model");
  reject(() -> AiClient.validate("https://provider.example","model",""), "empty key");
  reject(() -> AiClient.validate("https://provider.example","model","dummy\r\nInjected"), "header injection");
  JSONArray messages = new JSONArray().put(new JSONObject().put("role","user").put("content","讲解这道题"));
  String text = new AiClient().complete("https://provider.example/v1","test-model","dummy-key",messages,false);
  check(text.startsWith("答案依据"), "UTF-8 body");
  check(last.getRequestMethod().equals("POST"), "POST");
  check(!last.getInstanceFollowRedirects(), "redirect credentials");
  check(last.getRequestProperty("Authorization").equals("Bearer dummy-key"), "bearer header");
  check(last.getConnectTimeout()==15000 && last.getReadTimeout()==90000, "timeouts");
  JSONObject sent = new JSONObject(last.request.toString("UTF-8"));
  check(sent.getString("model").equals("test-model") && !sent.getBoolean("stream") && sent.getJSONArray("messages").length()==1,"payload");
  check(last.disconnected, "connection cleanup");
  for(int code : new int[]{301,302,307,400,401,402,403,404,429,500,503}) {
   status=code;
   try { new AiClient().complete("https://provider.example","model","dummy-key",messages,false); throw new AssertionError("status "+code); }
   catch(AiClient.UserException e) { check(!e.getMessage().contains("dummy-key"), "key in error"); }
   check(last.disconnected, "failure cleanup");
  }
  status=200; timeout=true;
  try { new AiClient().complete("https://provider.example","model","dummy-key",messages,false); throw new AssertionError("timeout"); }
  catch(SocketTimeoutException expected) {}
  timeout=false;
  body="<html>error</html>"; reject(() -> new AiClient().complete("https://provider.example","model","dummy-key",messages,false), "HTML response");
  for(String invalid : new String[]{"{}","{\"choices\":[]}","{\"choices\":[{\"message\":{\"content\":null}}]}"})
   reject(() -> AiClient.parseResponse(invalid), "empty/missing content");
  check(AiClient.parseResponse("{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]},\"finish_reason\":\"length\"}]}").contains("截断"),"parts and truncated answer");
  body="x".repeat(2*1024*1024+1); reject(() -> new AiClient().complete("https://provider.example","model","dummy-key",messages,false),"oversized response");
  blockRead=true;
  AiClient cancelled = new AiClient(); final Throwable[] failure={null}; final int before=opens;
  Thread worker=new Thread(() -> {try { cancelled.complete("https://provider.example","model","dummy-key",messages,false); } catch(Throwable e) {failure[0]=e;} });
  worker.start();
  long deadline=System.currentTimeMillis()+3000;
  while(opens==before && System.currentTimeMillis()<deadline) Thread.sleep(5);
  cancelled.cancel(); worker.join(3000);
  check(!worker.isAlive() && failure[0] instanceof IOException, "inflight cancellation");
  blockRead=false;
  AiClient preCancelled=new AiClient(); preCancelled.cancel();
  reject(() -> preCancelled.complete("https://provider.example","model","dummy-key",messages,false),"cancel before start");
  JSONObject item=new JSONObject().put("passage","Complete passage").put("answer","B").put("options",new JSONArray().put("A. one").put("B. two")).put("localPrivateData","do not send");
  String context=AiPrompt.context(item,"A");
  check(context.contains("Complete passage") && context.contains("B. two") && context.contains("我的作答：A") && !context.contains("do not send"), "question context allowlist");
  System.out.println("PASS: URL/input validation, request payload, UTF-8, HTTP errors, redirects, timeout, malformed/empty/large responses, cancellation, prompt context. No live paid API called.");
 }
}
''', encoding='utf-8')
jdk = Path(os.environ.get('JAVA_HOME', 'E:/develop/jdk-25.02')) / 'bin'
source = ROOT / 'android-app/app/src/main/java/com/cet4/focus'
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(TMP), str(source/'AiClient.java'), str(source/'AiPrompt.java'), str(harness)], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(TMP)+os.pathsep+str(jar), 'com.cet4.focus.AiClientCheck'], check=True)
