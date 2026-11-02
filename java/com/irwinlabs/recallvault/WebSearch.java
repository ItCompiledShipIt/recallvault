package com.irwinlabs.recallvault;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSearch - keyless web lookup for the brain via DuckDuckGo's
 * public HTML endpoint (v0.55.0, chris's Nov 1, 2026 request).
 * No API key, no account. Only the search words leave the
 * phone; vault contents are never sent. Parsing is pure and
 * JVM-tested: result links arrive wrapped in DDG's redirect
 * (uddg=), titles/snippets carry tags and entities - all
 * unwrapped here so the model sees clean text.
 */
public final class WebSearch {

    private WebSearch() {}

    public static final class Hit {
        public final String title;
        public final String url;
        public final String snippet;
        Hit(String title, String url, String snippet) {
            this.title = title; this.url = url; this.snippet = snippet;
        }
    }

    private static final Pattern P_LINK = Pattern.compile(
        "class=\"result__a\" href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern P_SNIP = Pattern.compile(
        "class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern P_TAG = Pattern.compile("<[^>]+>");

    static String clean(String html) {
        String t = P_TAG.matcher(html).replaceAll("");
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
             .replace("&nbsp;", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    static String unwrap(String href) {
        try {
            int i = href.indexOf("uddg=");
            if (i >= 0) {
                String enc = href.substring(i + 5);
                int amp = enc.indexOf('&');
                if (amp >= 0) enc = enc.substring(0, amp);
                return URLDecoder.decode(enc, "UTF-8");
            }
            if (href.startsWith("//")) return "https:" + href;
            return href;
        } catch (Exception e) {
            return href;
        }
    }

    /** Parse DDG HTML results; snippets pair with links in
     *  document order (each result block emits link then
     *  snippet). Pure - unit-tested. */
    public static List<Hit> parseResults(String html, int limit) {
        List<Hit> out = new ArrayList<>();
        if (html == null) return out;
        Matcher lm = P_LINK.matcher(html);
        Matcher sm = P_SNIP.matcher(html);
        List<String> snippets = new ArrayList<>();
        while (sm.find()) snippets.add(clean(sm.group(1)));
        int idx = 0;
        while (lm.find() && out.size() < limit) {
            String url = unwrap(lm.group(1));
            String title = clean(lm.group(2));
            String snip = idx < snippets.size() ? snippets.get(idx) : "";
            idx++;
            if (!title.isEmpty() && url.startsWith("http")) {
                out.add(new Hit(title, url, snip));
            }
        }
        return out;
    }

    /** Live search (network; call off the UI thread). Returns a
     *  plain-text brief for the model, or an honest failure
     *  line it can relay. */
    public static String search(String query) {
        HttpURLConnection c = null;
        try {
            String u = "https://html.duckduckgo.com/html/?q="
                + URLEncoder.encode(query, "UTF-8");
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < 512 * 1024) sb.append(buf, 0, n);
            r.close();
            List<Hit> hits = parseResults(sb.toString(), 5);
            if (hits.isEmpty()) {
                return "Web search for \"" + query + "\" returned no results (the search service may be busy or blocking automated lookups right now).";
            }
            StringBuilder out = new StringBuilder();
            out.append("Web results for \"").append(query)
               .append("\" (from DuckDuckGo - NOT from the user's vault):\n");
            int i = 1;
            for (Hit h : hits) {
                out.append(i++).append(". ").append(h.title).append("\n");
                if (!h.snippet.isEmpty()) out.append(h.snippet).append("\n");
                out.append(h.url).append("\n\n");
            }
            return out.toString().trim();
        } catch (Exception e) {
            return "Web search failed (" + e.getClass().getSimpleName() + "). The phone may be offline.";
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
