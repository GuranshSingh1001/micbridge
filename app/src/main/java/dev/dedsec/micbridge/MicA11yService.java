package dev.dedsec.micbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
 * voiceStatesView, closes the overlay and types the text into the app that
 * was in the foreground.
 *
 * Routing is global. Only Jio/preset apps, the current launcher, Settings and
 * SystemUI keep their stock behaviour. A package can always be forced back
 * into the generic flow with:
 *
 *   adb shell settings put global micbridge_allow "com.example.app,com.other.*"
 */
public class MicA11yService extends AccessibilityService {

    static final String JIO_VA = "com.android.app.jio.voiceassist";
    static final String ID_VOICE_STATE = JIO_VA + ":id/voiceStatesView";

    /** Comma/space separated allowlist, supports a trailing "*" wildcard. */
    static final String SETTING_ALLOW = "micbridge_allow";

    /** Packages that always keep the stock Jio mic behaviour. */
    private static final String[] BLOCKED_EXACT = {
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.tv.settings",
            "com.android.tv.settings.local",
            "com.klevico.monet",
            "com.gathin.tvhome",
            "in.startv.hotstar",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.zee5.aosp",
            "com.suntv.sunnxt",
            "com.hoichoi.mitv",
            "com.epicchannel.epicon",
            "com.ultrajhakaas.android",
            "com.etvwin.androidtv",
    };
    private static final String[] BLOCKED_PREFIX = {
            "com.iwedia.",
            "com.jio.",
            "com.jiostb.",
            "com.jiotv.",
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

    /** set once we clicked a search entry point for the current capture */
    private boolean searchClicked;

    private String homePkg;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        L.f(this, "service connected; flags=" + (info == null ? -1 : info.flags)
                + " home=" + homePackage());
        L.f(this, "blocklist: Jio/preset + launcher + Settings/SystemUI; allow="
                + allowSetting());

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
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }

        String pkg = activeAppPackage();
        if (pkg == null) {
            CharSequence p = event.getPackageName();
            if (p == null) return;
            pkg = p.toString();
        }
        if (!JIO_VA.equals(pkg) && !getPackageName().equals(pkg)) {
            lastApp = pkg;
        }

        long now = System.currentTimeMillis();
        if (busy || now - lastTrigger <= 3000 || !jioOverlayPresent()) return;

        // Prefer the app that was in front when the mic key went down: some
        // launchers grab focus again while the overlay is opening, which
        // would otherwise look like a mic press on the launcher.
        String target = lastApp;
        if (!keyApp.isEmpty() && now - keyAppAt <= 15000) {
            target = keyApp;
            keyApp = "";            // one press, one target
        }

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

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();
        if ((kc == KeyEvent.KEYCODE_VOICE_ASSIST || kc == KeyEvent.KEYCODE_SEARCH)
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            keyApp = lastApp;
            keyAppAt = System.currentTimeMillis();
            L.f(this, "mic key kc=" + kc + " scan=" + event.getScanCode()
                    + " app=" + keyApp);
        }
        return false;
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

    /**
     * True when the package keeps its stock mic behaviour: Jio/preset apps,
     * the launcher, Settings, SystemUI, the voice app and ourselves. The
     * micbridge_allow setting always wins.
     */
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
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return false;
            for (AccessibilityWindowInfo w : ws) {
                if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                if (JIO_VA.contentEquals(String.valueOf(r.getPackageName()))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ---- query capture -------------------------------------------------

    private void startCapture(String target) {
        busy = true;
        lastTrigger = System.currentTimeMillis();
        lastQuery = null;
        stableCount = 0;
        searchClicked = false;
        poll(target, 0);
    }

    private void poll(final String target, final int tick) {
        if (tick > 90) {          // ~27 s
            L.f(this, "capture timeout");
            busy = false;
            return;
        }

        String top = activeAppPackage();
        if (!JIO_VA.equals(top)) {
            if (tick > 2) {
                L.f(this, "overlay closed before a query appeared (top=" + top + ")");
                busy = false;
                return;
            }
        } else {
            String q = readQuery();
            if (q != null) {
                if (q.equals(lastQuery)) {
                    stableCount++;
                } else {
                    lastQuery = q;
                    stableCount = 0;
                }
                // The result screen carries a banner/title; prefer that, and
                // fall back to a text that stayed unchanged for ~600 ms.
                boolean finalUi = hasId(JIO_VA + ":id/banner_layout")
                        || hasId(JIO_VA + ":id/title");
                if ((finalUi && stableCount >= 1) || stableCount >= 2) {
                    L.f(this, "captured query: \"" + q + "\" (finalUi=" + finalUi + ")");
                    deliver(target, q);
                    return;
                }
            }
        }
        h.postDelayed(() -> poll(target, tick + 1), 300);
    }

    /** Reads the recognised query out of the Jio overlay, or null. */
    private String readQuery() {
        try {
            AccessibilityNodeInfo root = jioRoot();
            if (root == null) return null;
            List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(ID_VOICE_STATE);
            if (l == null || l.isEmpty()) return null;
            CharSequence t = l.get(0).getText();
            if (t == null) return null;
            String s = t.toString().trim();
            if (!plausibleQuery(s)) return null;
            return s;
        } catch (Throwable t) {
            L.e("readQuery: " + t);
        }
        return null;
    }

    private AccessibilityNodeInfo jioRoot() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return null;
            for (AccessibilityWindowInfo w : ws) {
                if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                if (!JIO_VA.contentEquals(String.valueOf(root.getPackageName()))) continue;
                return root;
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

    private boolean plausibleQuery(String s) {
        if (s.length() < 2) return false;
        String l = s.toLowerCase(Locale.US);
        if (l.contains("listening")) return false;
        if (l.equals("on") || l.equals("off")
                || l.equals("try again") || l.equals("…") || l.equals("...")) {
            return false;
        }
        String[] bad = {"sorry", "didn't", "did not", "not able", "no result",
                "something went wrong", "could not", "couldn't", "try saying",
                "network", "please try"};
        for (String b : bad) {
            if (l.contains(b)) return false;
        }
        return true;
    }

    // ---- delivery ------------------------------------------------------

    private void deliver(final String target, final String text) {
        h.postDelayed(() -> deliverToApp(target, text, 0), 100);
    }

    private void deliverToApp(final String target, final String text, final int tries) {
        String top = activeAppPackage();
        L.i("deliverToApp try=" + tries + " top=" + top + " target=" + target);

        if (JIO_VA.equals(top)) {
            if (tries < 4) {
                L.f(this, "dismissing Jio overlay");
                performGlobalAction(GLOBAL_ACTION_BACK);
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 500);
            } else {
                L.f(this, "could not dismiss Jio overlay");
                busy = false;
            }
            return;
        }

        if (top == null || !top.equals(target)) {
            if (tries < 5) {
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 500);
            } else {
                L.f(this, "target never came to front (" + target + ")");
                busy = false;
            }
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            if (tries < 5) h.postDelayed(() -> deliverToApp(target, text, tries + 1), 500);
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
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 450);
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
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 900);
                return;
            }
        }

        if (tries < 5) {
            h.postDelayed(() -> deliverToApp(target, text, tries + 1), 600);
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

    private String activeAppPackage() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return null;
            String best = null;
            for (AccessibilityWindowInfo w : ws) {
                if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null) continue;
                String p = r.getPackageName().toString();
                if (w.isFocused() || w.isActive()) return p;
                if (best == null) best = p;
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private AccessibilityNodeInfo byId(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(id);
        return (l != null && !l.isEmpty()) ? l.get(0) : null;
    }

    private AccessibilityNodeInfo byDesc(AccessibilityNodeInfo root, String desc) {
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(desc);
        if (l != null) {
            for (AccessibilityNodeInfo n : l) {
                if (desc.contentEquals(String.valueOf(n.getContentDescription()))) return n;
            }
        }
        return null;
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
     * Leanback search boxes (SmartTube, TizenTube, ...) are streaming text
     * views rather than editable fields, so they need their own lookup.
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
                if (low.contains("search_text") || low.endsWith("search_editor")
                        || low.endsWith("search_box") || low.endsWith("search_field")) {
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
            // Never click around inside web page content (TV Bro & co): only
            // the app's own chrome can be a search entry point.
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

    /** Focus/click the field first, then set the text. */
    private boolean typeInto(AccessibilityNodeInfo node, String text) {
        if (setText(node, text)) return true;
        try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); } catch (Throwable ignored) {}
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return setText(node, text);
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
