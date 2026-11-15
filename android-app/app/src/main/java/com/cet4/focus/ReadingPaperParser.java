package com.cet4.focus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the documented public exam markup without running page scripts. */
final class ReadingPaperParser {
    private static final String NL = String.valueOf((char) 10);
    private static final Set<String> VOID = new HashSet<>(Arrays.asList("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"));
    private static final Pattern TOKENS = Pattern.compile("<!--[\\s\\S]*?-->|<[^>]+>|[^<]+");
    private static final Pattern ATTRS = Pattern.compile("([a-zA-Z0-9_-]+)\\s*=\\s*[\"']([^\"']*)[\"']");
    private ReadingPaperParser() { }

    static JSONArray parse(String html, JSONObject exam) throws IOException, JSONException {
        Node root = tree(html);
        String id = exam.getString("id");
        if (!id.matches("[0-9]{6}-[1-3]")) throw new IOException("Invalid exam identity");
        String label = exam.getString("exam");
        JSONArray items = new JSONArray();
        Node bank = root.required("id", "banked");
        LinkedHashMap<String, String> words = new LinkedHashMap<>();
        for (Node word : bank.all("class", "wbitem")) {
            String value = word.text();
            if (value.length() < 2) throw new IOException("Missing bank word");
            words.put(value.substring(0, 1), value.substring(1).trim());
        }
        if (!words.keySet().equals(new HashSet<>(Arrays.asList("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O")))) throw new IOException("Incomplete word bank");
        String passage = bank.required("class", "passage").text();
        for (int n = 26; n <= 35; n++) {
            Pattern marker = Pattern.compile("(?<![0-9])" + n + "(?![0-9])");
            Matcher match = marker.matcher(passage);
            int count = 0;
            while (match.find()) count++;
            if (count != 1) throw new IOException("Invalid blank marker " + n);
            passage = marker.matcher(passage).replaceAll("__" + n + "__");
        }
        for (Node blank : bank.all("class", "blank")) {
            int n = number(blank);
            String letter = blank.attr("data-ans");
            if (!words.containsKey(letter)) throw new IOException("Missing cloze answer");
            JSONArray choices = new JSONArray();
            for (Map.Entry<String, String> word : words.entrySet()) choices.put(word.getKey() + ") " + word.getValue());
            JSONObject q = common(exam, n, "reading-cloze", "选词填空");
            q.put("passageId", id + "-banked").put("passage", passage);
            q.put("prompt", "请选择第 " + n + " 空，输入单词或字母；其余空格保留供理解上下文。");
            q.put("wordBank", choices).put("blanks", new JSONArray().put(new JSONObject().put("number", n).put("answer", words.get(letter))));
            q.put("answer", words.get(letter)).put("acceptedAnswers", new JSONArray().put(letter).put(words.get(letter)));
            q.put("explanation", "来源参考答案：" + n + ". " + letter + ") " + words.get(letter) + "。可打开来源解析查看讲解。");
            items.put(q);
        }
        Node matching = root.required("id", "matching");
        LinkedHashMap<String, String> paragraphs = new LinkedHashMap<>();
        JSONArray paragraphData = new JSONArray();
        StringBuilder article = new StringBuilder();
        for (Node paragraph : matching.all("class", "para")) {
            String key = paragraph.attr("data-key");
            String value = paragraph.text();
            if (!key.matches("[A-Z]") || !value.startsWith(key)) throw new IOException("Invalid paragraph key");
            value = value.substring(1).trim();
            if (paragraphs.containsKey(key) || value.isEmpty()) throw new IOException("Invalid paragraph");
            paragraphs.put(key, value);
            paragraphData.put(new JSONObject().put("id", key).put("text", value));
            if (article.length() > 0) article.append(NL).append(NL);
            article.append(key).append(") ").append(value);
        }
        for (Node statement : matching.all("class", "mstmt")) {
            int n = number(statement);
            String answer = statement.attr("data-ans");
            if (!paragraphs.containsKey(answer)) throw new IOException("Answer refers to a missing paragraph");
            String prompt = statement.required("class", "qhead").text().replaceFirst("^[0-9]+[.]\\s*", "");
            JSONObject q = common(exam, n, "reading-matching", "段落匹配");
            q.put("passageId", id + "-matching").put("passage", article.toString()).put("paragraphs", paragraphData);
            q.put("statements", new JSONArray().put(new JSONObject().put("number", n).put("text", prompt).put("answer", answer)));
            q.put("explanation", "来源参考答案：" + answer + " 段。对应原文：" + NL + paragraphs.get(answer));
            items.put(q);
        }
        int index = 0;
        for (Node block : root.required("id", "careful").all("class", "careful-passage")) {
            index++;
            String articleText = block.required("class", "passage").text();
            if (articleText.split(" ").length < 200) throw new IOException("Incomplete reading passage");
            for (Node question : block.all("class", "q")) {
                int n = number(question);
                String answer = question.attr("data-ans");
                JSONArray options = new JSONArray();
                for (Node option : question.all("class", "opt")) options.put(option.attr("data-key") + ") " + option.required("class", "otext").text());
                if (options.length() != 4 || !answer.matches("[A-D]")) throw new IOException("Invalid reading choices");
                JSONObject q = common(exam, n, "reading-detail", "仔细阅读");
                q.put("passageId", id + "-careful-" + index).put("passage", articleText).put("prompt", question.required("class", "stem").text());
                q.put("options", options).put("answer", answer).put("explanation", "来源参考答案：" + options.getString(answer.charAt(0) - 'A') + "。未附独立逐题详解，可打开来源解析核对。");
                items.put(q);
            }
        }
        HashSet<Integer> numbers = new HashSet<>();
        for (int i = 0; i < items.length(); i++) numbers.add(items.getJSONObject(i).getInt("questionNumber"));
        if (items.length() != 30 || numbers.size() != 30) throw new IOException("Expected 30 reading questions");
        for (int n = 26; n <= 55; n++) if (!numbers.contains(n)) throw new IOException("Missing reading question " + n);
        return items;
    }

    private static JSONObject common(JSONObject exam, int n, String type, String title) throws JSONException {
        return new JSONObject().put("id", "r-" + exam.getString("id") + "-q" + n).put("type", type).put("mode", "real").put("exam", exam.getString("exam")).put("title", exam.getString("exam") + " · " + title + " " + n).put("questionNumber", n).put("source", exam.getString("exam") + " · 南京大学公开自测版").put("sourceKind", "public-exam").put("sourceUrl", exam.getString("sourceUrl")).put("answerSourceUrl", exam.getString("answerSourceUrl")).put("paperUrl", exam.getString("paperUrl"));
    }
    private static int number(Node node) throws IOException {
        try { return Integer.parseInt(node.attr("data-no")); }
        catch (NumberFormatException e) { throw new IOException("Invalid question number", e); }
    }
    private static Node tree(String html) {
        Node root = new Node("root");
        ArrayList<Node> stack = new ArrayList<>();
        stack.add(root);
        Matcher tokens = TOKENS.matcher(html);
        while (tokens.find()) {
            String token = tokens.group();
            if (token.startsWith("<!") || token.startsWith("<?")) continue;
            if (token.startsWith("</")) {
                String tag = token.substring(2, token.length() - 1).trim().toLowerCase(java.util.Locale.ROOT);
                for (int i = stack.size() - 1; i > 0; i--) if (stack.get(i).tag.equals(tag)) { while (stack.size() > i) stack.remove(stack.size() - 1); break; }
            } else if (token.startsWith("<")) {
                String tag = token.substring(1).split("[ >/]", 2)[0].toLowerCase(java.util.Locale.ROOT);
                Node node = new Node(tag);
                Matcher attributes = ATTRS.matcher(token);
                while (attributes.find()) node.attrs.put(attributes.group(1), decode(attributes.group(2)));
                stack.get(stack.size() - 1).children.add(node);
                if (!VOID.contains(tag) && !token.endsWith("/>")) stack.add(node);
            } else stack.get(stack.size() - 1).children.add(token);
        }
        return root;
    }
    private static String decode(String value) {
        return value.replace("&#x27;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&");
    }
    private static final class Node {
        final String tag;
        final Map<String, String> attrs = new LinkedHashMap<>();
        final List<Object> children = new ArrayList<>();
        Node(String tag) { this.tag = tag; }
        String attr(String key) { String value = attrs.get(key); return value == null ? "" : value; }
        String raw() { StringBuilder text = new StringBuilder(); for (Object child : children) text.append(child instanceof Node ? ((Node) child).raw() : child); return text.toString(); }
        String text() { return decode(raw()).replaceAll("\\s+", " ").trim(); }
        List<Node> all(String key, String value) {
            ArrayList<Node> found = new ArrayList<>();
            for (Object child : children) if (child instanceof Node) {
                Node node = (Node) child;
                boolean matches = "class".equals(key) ? Arrays.asList(node.attr(key).split(" ")).contains(value) : value.equals(node.attr(key));
                if (matches) found.add(node);
                found.addAll(node.all(key, value));
            }
            return found;
        }
        Node required(String key, String value) throws IOException { List<Node> matches = all(key, value); if (matches.isEmpty()) throw new IOException("Missing source section " + value); return matches.get(0); }
    }
}
