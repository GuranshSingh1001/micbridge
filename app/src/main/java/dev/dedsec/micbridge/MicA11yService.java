package dev.dedsec.micbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Locale;

/**
 * Routes the Jio remote's microphone key to sideloaded apps.
 *
 * The firmware always launches com.android.app.jio.voiceassist for the mic
 * key and that app is the only client the speech service accepts, so this
 * service lets it do the recognition, reads the recognised query from
 * voiceStatesView, closes the overlay, and delivers the text to the app that
 * was in the foreground.
 *
 * Supports Leanback apps, Cobalt / TizenTube / SmartTube, TV Bro / browsers,
 * and generic Android TV apps.
 */
public class MicA11yService extends AccessibilityService {

    public static final String JIO_VA = "com.android.app.jio.voiceassist";
    public static final String ID_VOICE_STATE = JIO_VA + ":id/voiceStatesView";

    /** Known candidate IDs used by different versions of Jio Voice Assist */
    private static final String[] CANDIDATE_VIEW_IDS = {
            ID_VOICE_STATE,
            JIO_VA + ":id/tv_speech_result",
            JIO_VA + ":id/text_result",
            JIO_VA + ":id/tv_voice_state",
            JIO_VA + ":id/voice_query",
            JIO_VA + ":id/query",
            JIO_VA + ":id/title",
            JIO_VA + ":id/sub_title",
            JIO_VA + ":id/prompt_text",
            JIO_VA + ":id/search_query"
    };

    /** Candidate IDs that indicate the final recognition / result screen */
    private static final String[] FINAL_UI_IDS = {
            JIO_VA + ":id/banner_layout",
            JIO_VA + ":id/title",
            JIO_VA + ":id/search_result",
            JIO_VA + ":id/rv_search_results",
            JIO_VA + ":id/card_view",
            JIO_VA + ":id/result_container"
    };

    /** Callback interface for RecognizeActivity to receive results */
    public interface QueryListener {
        void onQueryCaptured(String query);
        void onCaptureFailed();
    }

    private static volatile QueryListener sQueryListener;

    public static void setQueryListener(QueryListener listener) {
        sQueryListener = listener;
    }

    /** Comma/space separated allowlist, supports a trailing "*" wildcard. */
    public static final String SETTING_ALLOW = "micbridge_allow";

    /** Packages that always keep the stock Jio mic behaviour. */
    private static final String[] BLOCKED_EXACT = {
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.tv.settings",
            "com.android.tv.settings.local",
            "com.klevico.monet",
            "in.startv.hotstar",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.zee5.aosp",
            "com.suntv.sunnxt",
            "com.hoichoi.mitv",
            "com.epicchannel.epicon",
            "com.ultrajhakaas.android",
            "com.etvwin.androidtv"
    };

    private static final String[] BLOCKED_PREFIX = {
            "com.iwedia.",
            "com.jio.",
            "com.jiostb.",
            "com.jiotv."
    };

    private final Handler h = new Handler(Looper.getMainLooper());

    /** last non-Jio, non-self application package */
    private String lastApp = "";

    /** package that was in front when the mic key went down */
    private String keyApp = "";
    private long keyAppAt;

    private boolean busy;
    private long lastTrigger;
    private long lastBlockedLog;
    private String lastQuery;
    private int stableCount;
    private boolean overlaySeen;
    private int overlayAbsentCount;

    /** set once we clicked a search entry point for the current capture */
    private boolean searchClicked;

    private String homePkg;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_FOCUSED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            setServiceInfo(info);
        }
        L.f(this, "service connected; flags=" + (info == null ? -1 : info.flags)
                + " home=" + homePackage());
        L.f(this, "blocklist: Jio/preset + launcher + Settings/SystemUI; allow="
                + allowSetting());

        try {
            android.provider.Settings.Global.putString(getContentResolver(), "jio_stb_active_language", "en-IN");
            android.provider.Settings.Global.putString(getContentResolver(), "system_SelectedLanguage", "en-IN");
            android.provider.Settings.System.putString(getContentResolver(), "system_SelectedLanguage", "en-IN");
            android.provider.Settings.System.putString(getContentResolver(), "system_locales", "en-IN");
        } catch (Throwable ignored) {}

        // Debug hook: adb shell am broadcast -a dev.dedsec.micbridge.TEST
        //   --es query "some text"   (deliver directly)
        //   --es target <pkg>        (override the foreground app)
        //   (no extra)               (capture from the open Jio overlay)
        try {
            registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String t = i.getStringExtra("target");
                    if (t == null || t.isEmpty()) t = lastApp;
                    String q = i.getStringExtra("query");
                    L.f(MicA11yService.this, "TEST target=" + t
                            + " query=" + q + " blocked=" + isBlocked(t));
                    if (q != null && !q.isEmpty()) deliver(t, q);
                    else startCapture(t);
                }
            }, new IntentFilter("dev.dedsec.micbridge.TEST"));
        } catch (Throwable t) {
            L.f(this, "debug receiver failed: " + t);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();

        CharSequence ep = event.getPackageName();
        String eventPkg = ep != null ? ep.toString() : null;

        // Keep track of the active foreground app
        if (eventPkg != null && !JIO_VA.equals(eventPkg) && !getPackageName().equals(eventPkg)) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                lastApp = eventPkg;
            }
        }

        // We act on window state/hierarchy changes, or any event from Jio VA
        boolean isJioEvent = (eventPkg != null && JIO_VA.equals(eventPkg));

        // If currently capturing, listen directly to Jio VA events for text updates
        if (busy) {
            if (isJioEvent) {
                overlaySeen = true;
                overlayAbsentCount = 0;
                checkJioEventForQuery(event);
            }
            return;
        }

        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && !isJioEvent) {
            return;
        }

        long now = System.currentTimeMillis();
        if ((now - lastTrigger <= 1000) || !jioOverlayPresent()) return;

        // Target resolution
        String target = resolveTarget(now);
        if (target == null || target.isEmpty()) return;

        boolean blocked = isBlocked(target);
        if (blocked) {
            if (now - lastBlockedLog > 3000) {
                lastBlockedLog = now;
                L.f(this, "voice overlay over " + target
                        + " -> blocked, keeping stock behaviour");
            }
            return;
        }
        L.f(this, "voice overlay over " + target + " -> capturing");
        startCapture(target);
    }

    private void checkJioEventForQuery(AccessibilityEvent event) {
        try {
            List<CharSequence> texts = event.getText();
            if (texts != null) {
                for (CharSequence cs : texts) {
                    if (cs != null) {
                        String s = cs.toString().trim();
                        if (plausibleQuery(s)) {
                            updateQueryFromEvent(s);
                            return;
                        }
                    }
                }
            }
            AccessibilityNodeInfo src = event.getSource();
            if (src != null) {
                String s = extractNodeText(src);
                if (s != null && plausibleQuery(s)) {
                    updateQueryFromEvent(s);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void updateQueryFromEvent(String q) {
        if (q == null) return;
        if (q.equals(lastQuery)) {
            stableCount++;
        } else {
            lastQuery = q;
            stableCount = 0;
        }
    }

    private String resolveTarget(long now) {
        String target = lastApp;
        if (!keyApp.isEmpty() && now - keyAppAt <= 15000) {
            target = keyApp;
            keyApp = ""; // one press, one target
        }
        if (target.isEmpty() || JIO_VA.equals(target) || getPackageName().equals(target)) {
            String act = activeAppPackage();
            if (act != null && !act.isEmpty()) target = act;
        }
        return target;
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();
        int scan = event.getScanCode();

        boolean isMicKey = (kc == KeyEvent.KEYCODE_VOICE_ASSIST
                || kc == KeyEvent.KEYCODE_SEARCH
                || kc == KeyEvent.KEYCODE_ASSIST
                || scan == 217
                || scan == 218
                || scan == 240);

        if (isMicKey && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            String front = activeAppPackage();
            keyApp = (front != null && !JIO_VA.equals(front)
                    && !getPackageName().equals(front)) ? front : lastApp;
            keyAppAt = System.currentTimeMillis();
            L.f(this, "mic key kc=" + kc + " scan=" + scan + " app=" + keyApp);

            // Proactively watch for Jio Voice Assist overlay appearing
            watchForOverlay(keyApp, 0);
        }
        return false;
    }

    /**
     * Poll briefly after a mic key event in case window state change is dropped by the system
     */
    private void watchForOverlay(final String target, final int attempt) {
        if (busy || attempt > 8) return;
        h.postDelayed(() -> {
            if (busy) return;
            if (jioOverlayPresent()) {
                long now = System.currentTimeMillis();
                if (now - lastTrigger > 1000) {
                    if (isBlocked(target)) {
                        L.f(MicA11yService.this, "voice overlay over " + target + " (watched) -> blocked");
                    } else {
                        L.f(MicA11yService.this, "voice overlay over " + target + " (watched) -> capturing");
                        startCapture(target);
                    }
                }
            } else {
                watchForOverlay(target, attempt + 1);
            }
        }, attempt == 0 ? 150 : 250);
    }

    @Override
    public void onInterrupt() {}

    // ---- blocklist -----------------------------------------------------

    private String allowSetting() {
        try {
            String s = Settings.Global.getString(getContentResolver(), SETTING_ALLOW);
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean isAllowed(String pkg) {
        String raw = allowSetting();
        if (raw.isEmpty()) return false;
        for (String part : raw.split("[,\\s]+")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.endsWith("*")) {
                if (pkg.startsWith(p.substring(0, p.length() - 1))) return true;
            } else if (pkg.equals(p)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlocked(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (JIO_VA.equals(pkg) || getPackageName().equals(pkg)) return true;
        if (isAllowed(pkg)) return false;
        if (pkg.equals(homePackage())) return true;

        for (String s : BLOCKED_EXACT) {
            if (s.equals(pkg)) return true;
        }
        for (String s : BLOCKED_PREFIX) {
            if (pkg.startsWith(s)) return true;
        }
        return false;
    }

    private String homePackage() {
        if (homePkg != null) return homePkg;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo r = getPackageManager()
                    .resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (r != null && r.activityInfo != null) {
                homePkg = r.activityInfo.packageName;
            }
        } catch (Throwable ignored) {}
        if (homePkg == null) homePkg = "";
        return homePkg;
    }

    /** True when the Jio voice overlay window is on screen. */
    private boolean jioOverlayPresent() {
        return jioRoot() != null;
    }

    // ---- query capture -------------------------------------------------

    public void startCapture(String target) {
        busy = true;
        lastTrigger = System.currentTimeMillis();
        lastQuery = null;
        stableCount = 0;
        overlaySeen = false;
        overlayAbsentCount = 0;
        searchClicked = false;
        poll(target, 0);
    }

    private void poll(final String target, final int tick) {
        if (!busy) return;

        if (tick > 120) { // ~18 s (at 150ms intervals)
            L.f(this, "capture timeout");
            busy = false;
            notifyListenerFailed();
            return;
        }

        boolean overlayPresent = jioOverlayPresent();
        if (overlayPresent) {
            overlaySeen = true;
            overlayAbsentCount = 0;

            String q = readQuery();
            if (q != null) {
                if (q.equals(lastQuery)) {
                    stableCount++;
                } else {
                    lastQuery = q;
                    stableCount = 0;
                }

                boolean finalUi = isFinalUi();
                if ((finalUi && stableCount >= 1) || stableCount >= 2) {
                    L.f(this, "captured query: \"" + q + "\" (finalUi=" + finalUi + ")");
                    deliver(target, q);
                    return;
                }
            }
        } else {
            if (overlaySeen) {
                overlayAbsentCount++;
                // If overlay was seen and has now vanished for >= 2 ticks (~300ms)
                if (overlayAbsentCount >= 2) {
                    if (lastQuery != null && !lastQuery.isEmpty()) {
                        L.f(this, "overlay closed with query: \"" + lastQuery + "\" -> delivering");
                        deliver(target, lastQuery);
                        return;
                    }
                    if (tick > 6) { // ~900ms after start
                        L.f(this, "overlay closed before a query appeared");
                        busy = false;
                        notifyListenerFailed();
                        return;
                    }
                }
            } else {
                // Overlay not seen yet; wait up to ~4 seconds for it to appear
                if (tick > 26) {
                    L.f(this, "overlay never appeared after mic press");
                    busy = false;
                    notifyListenerFailed();
                    return;
                }
            }
        }
        h.postDelayed(() -> poll(target, tick + 1), 150);
    }

    private boolean isFinalUi() {
        for (String id : FINAL_UI_IDS) {
            if (hasId(id)) return true;
        }
        return false;
    }

    /** Reads the recognised query out of the Jio overlay, or null. */
    private String readQuery() {
        try {
            AccessibilityNodeInfo root = jioRoot();
            if (root == null) return null;

            // 1. Check known candidate IDs
            for (String id : CANDIDATE_VIEW_IDS) {
                List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(id);
                if (l != null && !l.isEmpty()) {
                    for (AccessibilityNodeInfo n : l) {
                        String s = extractNodeText(n);
                        if (s != null && plausibleQuery(s)) return s;
                    }
                }
            }

            // 2. Fallback: Search all text in the Jio overlay for plausible query text
            return findPlausibleText(root, 0);
        } catch (Throwable t) {
            L.e("readQuery: " + t);
        }
        return null;
    }

    private String extractNodeText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) {
            String s = t.toString().trim();
            if (!s.isEmpty()) return s;
        }
        CharSequence d = node.getContentDescription();
        if (d != null && d.length() > 0) {
            String s = d.toString().trim();
            if (!s.isEmpty()) return s;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String c = extractNodeText(node.getChild(i));
            if (c != null && !c.isEmpty()) return c;
        }
        return null;
    }

    private String findPlausibleText(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 20) return null;
        CharSequence t = n.getText();
        if (t != null) {
            String s = t.toString().trim();
            if (plausibleQuery(s)) return s;
        }
        CharSequence d = n.getContentDescription();
        if (d != null) {
            String s = d.toString().trim();
            if (plausibleQuery(s)) return s;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            String r = findPlausibleText(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo jioRoot() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w == null) continue;
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null || root.getPackageName() == null) continue;
                    if (JIO_VA.contentEquals(root.getPackageName())) return root;
                }
            }
        } catch (Throwable ignored) {}
        try {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null && active.getPackageName() != null
                    && JIO_VA.contentEquals(active.getPackageName())) {
                return active;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean hasId(String id) {
        try {
            AccessibilityNodeInfo root = jioRoot();
            if (root == null) return false;
            List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(id);
            return l != null && !l.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Filters out non-speech prompts in both English and Hindi.
     */
    private boolean plausibleQuery(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 2) return false;

        String l = t.toLowerCase(Locale.US);

        if (l.equals("…") || l.equals("...") || l.equals("..") || l.equals(".") || l.equals("-")) {
            return false;
        }

        // Instructional mic prompts in both English and Hindi (e.g. "बोलने के लिए माईक दबाएं")
        if ((l.contains("बोलने") || l.contains("दबा") || l.contains("प्रेस") || l.contains("press") || l.contains("hold") || l.contains("tap"))
                && (l.contains("माईक") || l.contains("माइक") || l.contains("mic") || l.contains("speak") || l.contains("लिए") || l.contains("button"))) {
            return false;
        }

        // English prompts
        if (l.contains("listening")
                || l.contains("speak now")
                || l.contains("say something")
                || l.contains("try saying")
                || l.contains("how can i help")
                || l.contains("tap to speak")
                || l.contains("im listening")
                || l.contains("i'm listening")
                || l.contains("press mic")
                || l.contains("press to speak")
                || l.contains("what can i do")
                || l.equals("on")
                || l.equals("off")
                || l.equals("try again")
                || l.equals("mic")
                || l.equals("microphone")) {
            return false;
        }

        // Hindi listening & helper prompts
        if (l.contains("सुन रहे")
                || l.contains("सुन रहा")
                || l.contains("सुन रही")
                || l.contains("बोलिए")
                || l.contains("बोलिये")
                || l.contains("कुछ कहिए")
                || l.contains("कुछ कहिये")
                || l.contains("कुछ बोलिए")
                || l.contains("कुछ बोलिये")
                || l.contains("कहिए")
                || l.contains("कहिये")
                || l.contains("बोलना शुरू")
                || l.contains("शुरू करें")
                || l.contains("माईक दबा")
                || l.contains("माइक दबा")
                || l.contains("बोलने के लिए")
                || l.equals("माइक")
                || l.equals("माईक")
                || l.equals("दबाएं")
                || l.equals("दबाएँ")
                || l.equals("दबाइए")
                || l.equals("दबायें")
                || l.equals("दबाओ")
                || l.equals("हेलो")
                || l.equals("हेलो हेलो")
                || l.equals("हेलो जियो")
                || l.equals("हेलो जीयो")) {
            return false;
        }

        // Error / failure messages in English & Hindi
        String[] bad = {
                "sorry", "didn't", "did not", "not able", "no result",
                "something went wrong", "could not", "couldn't", "try saying",
                "network", "please try", "couldn't hear", "didn't catch",
                "no internet", "timed out", "error", "voice assistant",
                "google assistant", "hello jio",
                "माफ़", "माफ", "क्षमा", "दोबारा", "कोशिश", "समस्या",
                "समझ नहीं", "परिणाम नहीं", "इंटरनेट", "नेटवर्क", "त्रुटि",
                "नमस्ते", "हेलो जियो", "हेलो जीयो"
        };
        for (String b : bad) {
            if (l.contains(b)) return false;
        }

        return true;
    }

    // ---- delivery ------------------------------------------------------

    private void deliver(final String target, final String text) {
        // If RecognizeActivity is active, deliver to it directly
        QueryListener ql = sQueryListener;
        if (ql != null) {
            sQueryListener = null;
            ql.onQueryCaptured(text);
            busy = false;
            dismissOverlayIfNeeded();
            return;
        }

        h.postDelayed(() -> deliverToApp(target, text, 0), 100);
    }

    private void notifyListenerFailed() {
        QueryListener ql = sQueryListener;
        if (ql != null) {
            sQueryListener = null;
            ql.onCaptureFailed();
        }
    }

    private void dismissOverlayIfNeeded() {
        if (jioOverlayPresent()) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    /**
     * Special handling for apps that lack traditional Android EditText widgets (Cobalt / TizenTube)
     * or web browsers (TV Bro) that support direct search intents.
     */
    private boolean deliverSpecial(String target, String text) {
        if (target == null || text == null || text.trim().isEmpty()) return false;
        String query = text.trim();

        // 1. YouTube / TizenTube / Cobalt family
        if (target.contains("tizentube") || target.contains("cobalt")
                || target.equals("com.google.android.youtube.tv")
                || target.equals("org.smarttube.stable")
                || target.equals("org.smarttube.beta")
                || target.equals("com.teamsmart.videomanager.tv")) {
            try {
                String url = "https://www.youtube.com/results?search_query=" + Uri.encode(query);
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.setPackage(target);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
                return true;
            } catch (Throwable t) {
                L.e("deliverSpecial youtube failed: " + t);
            }
        }

        // 2. TV Bro and web browsers: if no text field is currently focused, open search URL
        if (target.contains("tvwebbrowser") || target.contains("browser")) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo focused = (root != null) ? root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : null;
            if (focused == null || !isEditable(focused)) {
                try {
                    String url;
                    if (query.startsWith("http://") || query.startsWith("https://")) {
                        url = query;
                    } else if (query.contains(".") && !query.contains(" ") && query.length() > 3) {
                        url = "https://" + query;
                    } else {
                        url = "https://www.google.com/search?q=" + Uri.encode(query);
                    }
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.setPackage(target);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(i);
                    return true;
                } catch (Throwable t) {
                    L.e("deliverSpecial browser failed: " + t);
                }
            }
        }

        return false;
    }

    private void deliverToApp(final String target, final String text, final int tries) {
        String top = topPackage();
        boolean overlayStillUp = jioOverlayPresent() || (top != null && JIO_VA.equals(top));
        L.i("deliverToApp try=" + tries + " top=" + top + " target=" + target + " overlay=" + overlayStillUp);

        if (overlayStillUp) {
            if (tries < 5) {
                L.f(this, "dismissing Jio overlay");
                performGlobalAction(GLOBAL_ACTION_BACK);
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 350);
            } else {
                L.f(this, "could not dismiss Jio overlay");
                busy = false;
            }
            return;
        }

        // Fast-path intent delivery for Cobalt/TizenTube and Browsers
        if (deliverSpecial(target, text)) {
            L.f(this, "delivered to " + target + " via special intent: " + text);
            busy = false;
            return;
        }

        String activePkg = activeAppPackage();
        if (activePkg != null && !activePkg.equals(target)) {
            if (tries < 5) {
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 350);
            } else {
                L.f(this, "target never came to front (" + target + ", active=" + activePkg + ")");
                busy = false;
            }
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            if (tries < 5) h.postDelayed(() -> deliverToApp(target, text, tries + 1), 350);
            else busy = false;
            return;
        }

        AccessibilityNodeInfo field = focusedEditable(root);
        if (field == null) field = firstEditable(root);
        if (field == null) field = findLeanbackField(root, 0);

        if (field != null) {
            if (typeInto(field, text)) {
                L.f(this, "typed into " + target + " (" + nodeName(field) + "): " + text);
                busy = false;
            } else if (tries < 5) {
                L.f(this, "could not type into " + nodeName(field) + "; retrying");
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 350);
            } else {
                L.f(this, "could not type into " + nodeName(field));
                busy = false;
            }
            return;
        }

        if (!searchClicked && tries < 4) {
            AccessibilityNodeInfo s = findSearch(root);
            if (s != null) {
                searchClicked = true;
                L.f(this, "opening search in " + target + " via " + nodeName(s));
                click(s);
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 700);
                return;
            }
        }

        if (tries < 5) {
            h.postDelayed(() -> deliverToApp(target, text, tries + 1), 400);
        } else {
            AccessibilityNodeInfo focus = null;
            try { focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT); }
            catch (Throwable ignored) {}
            L.f(this, "no text field in " + target
                    + " (focus=" + nodeName(focus) + "); nothing typed");
            busy = false;
        }
    }

    // ---- helpers -------------------------------------------------------

    private String topPackage() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w == null) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null || r.getPackageName() == null) continue;
                    if (w.isFocused() || w.isActive()) return r.getPackageName().toString();
                }
            }
        } catch (Throwable ignored) {}
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null) return r.getPackageName().toString();
        } catch (Throwable ignored) {}
        return null;
    }

    private String activeAppPackage() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                String best = null;
                for (AccessibilityWindowInfo w : ws) {
                    if (w == null) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null || r.getPackageName() == null) continue;
                    String p = r.getPackageName().toString();
                    if (JIO_VA.equals(p) || getPackageName().equals(p)) continue;
                    if (w.isFocused() || w.isActive()) return p;
                    if (best == null) best = p;
                }
                if (best != null) return best;
            }
        } catch (Throwable ignored) {}
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null) {
                String p = r.getPackageName().toString();
                if (!JIO_VA.equals(p) && !getPackageName().equals(p)) return p;
            }
        } catch (Throwable ignored) {}
        return lastApp;
    }

    /** Editable node that currently holds input focus, or null. */
    private AccessibilityNodeInfo focusedEditable(AccessibilityNodeInfo root) {
        try {
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f == null) return null;
            if (isEditable(f)) return f;
            return firstEditable(f);
        } catch (Throwable t) {
            return null;
        }
    }

    private AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (isEditable(root)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo c = root.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo r = firstEditable(c);
            if (r != null) return r;
        }
        return null;
    }

    private boolean isEditable(AccessibilityNodeInfo n) {
        if (n == null) return false;
        try {
            if (n.isEditable()) return true;
        } catch (Throwable ignored) {}
        CharSequence c = n.getClassName();
        return c != null
                && c.toString().toLowerCase(Locale.US).contains("edittext");
    }

    /**
     * Search boxes (SmartTube, Leanback, SearchView, etc.)
     */
    private AccessibilityNodeInfo findLeanbackField(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        try {
            CharSequence cls = n.getClassName();
            String c = cls == null ? "" : cls.toString();
            if (c.contains("StreamingTextView") || c.contains("SearchEditText")) return n;

            String id = null;
            try { id = n.getViewIdResourceName(); } catch (Throwable ignored) {}
            if (id != null) {
                String low = id.toLowerCase(Locale.US);
                if (low.contains("search") && (low.contains("text") || low.contains("editor")
                        || low.contains("box") || low.contains("field") || low.contains("src")
                        || low.contains("input") || low.contains("query") || low.contains("bar"))) {
                    return n;
                }
            }

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo r = findLeanbackField(n.getChild(i), depth + 1);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Node that looks like the app's search entry point, or null. */
    private AccessibilityNodeInfo findSearch(AccessibilityNodeInfo root) {
        return findSearch(root, 0);
    }

    private AccessibilityNodeInfo findSearch(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 25) return null;
        try {
            CharSequence cls = n.getClassName();
            if (cls != null && cls.toString().contains("WebView")) return null;
            if (mentionsSearch(n.getText()) || mentionsSearch(n.getContentDescription())) {
                return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo r = findSearch(n.getChild(i), depth + 1);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean mentionsSearch(CharSequence cs) {
        return cs != null && cs.toString().toLowerCase(Locale.US).contains("search");
    }

    private void click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo t = n;
        while (t != null && !t.isClickable()) t = t.getParent();
        if (t != null) t.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    /** Focus/click the field first, then set the text and trigger submit. */
    private boolean typeInto(AccessibilityNodeInfo node, String text) {
        if (setText(node, text)) {
            triggerImeAction(node);
            return true;
        }
        try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); } catch (Throwable ignored) {}
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean ok = setText(node, text);
        if (ok) triggerImeAction(node);
        return ok;
    }

    private void triggerImeAction(AccessibilityNodeInfo node) {
        if (node == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            }
        } catch (Throwable ignored) {}
    }

    private boolean setText(AccessibilityNodeInfo node, String text) {
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        if (!ok) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        }
        L.f(this, "ACTION_SET_TEXT ok=" + ok);
        return ok;
    }

    /** Short "Class#id" form for logs. */
    private String nodeName(AccessibilityNodeInfo n) {
        if (n == null) return "null";
        String cls = String.valueOf(n.getClassName());
        int dot = cls.lastIndexOf('.');
        String id = null;
        try { id = n.getViewIdResourceName(); } catch (Throwable ignored) {}
        if (id != null) {
            int s = id.indexOf(":id/");
            if (s >= 0) id = id.substring(s + 4);
        }
        return (dot >= 0 ? cls.substring(dot + 1) : cls) + (id == null ? "" : "#" + id);
    }
}
