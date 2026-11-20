package dev.dedsec.micbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes the Jio remote's microphone key to sideloaded apps.
 *
 * The firmware always launches com.android.app.jio.voiceassist for the mic
 * key and that app is the only client the speech service accepts, so this
 * service lets it do the recognition, reads the recognised query from
 * voiceStatesView, closes the overlay and types the text into the app that
 * was in the foreground.
 *
 * The routing is global: every application gets the generic delivery below,
 * except the ones Jio already knows about (the firmware's remote_app_list, the
 * launcher, Settings and SystemUI). For those the mic keeps its stock
 * behaviour untouched.
 */
public class MicA11yService extends AccessibilityService {

    static final String JIO_VA = "com.android.app.jio.voiceassist";
    static final String SMARTTUBE = "org.smarttube.stable";

    static final String ID_VOICE_STATE = JIO_VA + ":id/voiceStatesView";
    static final String ID_ST_SEARCH_EDIT = SMARTTUBE + ":id/lb_search_text_editor";
    static final String ID_ST_SEARCH_ORB = SMARTTUBE + ":id/search_orb";

    /** Packages that always keep the stock Jio mic behaviour. */
    private static final String[] BLOCKED_EXACT = {
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.tv.settings",
            "com.android.tv.settings.local",
            "com.klevico.monet",
    };
    private static final String[] BLOCKED_PREFIX = {
            "com.iwedia.",
            "com.jio.",
            "com.jiostb.",
    };

    private static final Pattern REMOTE_PKG =
            Pattern.compile("\"pkg\"\\s*:\\s*\"([^\"]+)\"");

    private final Handler h = new Handler(Looper.getMainLooper());

    /** last non-Jio, non-self application package */
    private String lastApp = "";

    /** packages listed in Settings.Global remote_app_list (Jio remote apps) */
    private final Set<String> remoteApps = new HashSet<>();
    private boolean remoteLoaded;

    private boolean busy;
    private long lastTrigger;
    private String lastQuery;
    private int stableCount;

    /** set once we clicked a search entry point for the current capture */
    private boolean searchClicked;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        L.f(this, "service connected; flags=" + (info == null ? -1 : info.flags));
        loadRemoteApps();

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
                    L.f(MicA11yService.this, "TEST broadcast target=" + t
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

        if (JIO_VA.equals(pkg) && !busy
                && !isBlocked(lastApp)
                && System.currentTimeMillis() - lastTrigger > 3000) {
            L.f(this, "Jio voice overlay opened over " + lastApp);
            startCapture(lastApp);
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();
        if ((kc == KeyEvent.KEYCODE_VOICE_ASSIST || kc == KeyEvent.KEYCODE_SEARCH)
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            L.f(this, "mic key kc=" + kc + " scan=" + event.getScanCode());
        }
        return false;
    }

    @Override
    public void onInterrupt() {}

    // ---- blocklist -----------------------------------------------------

    private void loadRemoteApps() {
        remoteLoaded = true;
        try {
            String raw = Settings.Global.getString(getContentResolver(), "remote_app_list");
            Matcher m = REMOTE_PKG.matcher(raw == null ? "" : raw);
            while (m.find()) remoteApps.add(m.group(1));
            L.f(this, "remote_app_list entries=" + remoteApps.size());
        } catch (Throwable t) {
            L.e("loadRemoteApps: " + t);
        }
    }

    /**
     * True when the package keeps its stock mic behaviour: Jio/preset apps,
     * the launcher, Settings, SystemUI, the voice app and ourselves.
     */
    private boolean isBlocked(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (JIO_VA.equals(pkg) || getPackageName().equals(pkg)) return true;

        if (!remoteLoaded) loadRemoteApps();
        if (remoteApps.contains(pkg)) return true;

        for (String s : BLOCKED_EXACT) {
            if (s.equals(pkg)) return true;
        }
        for (String s : BLOCKED_PREFIX) {
            if (pkg.startsWith(s)) return true;
        }
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
        L.i("deliverToApp try=" + tries + " top=" + top);

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

        // SmartTube's search box is a StreamingTextView (not editable) and its
        // search screen is a separate window, so it needs its own path first.
        if (SMARTTUBE.equals(target)) {
            AccessibilityNodeInfo edit = byId(root, ID_ST_SEARCH_EDIT);
            if (edit != null) {
                if (setText(edit, text)) {
                    L.f(this, "typed into SmartTube search: " + text);
                    busy = false;
                } else if (tries < 5) {
                    h.postDelayed(() -> deliverToApp(target, text, tries + 1), 400);
                } else {
                    busy = false;
                }
                return;
            }
            AccessibilityNodeInfo orb = byId(root, ID_ST_SEARCH_ORB);
            if (orb == null) orb = byDesc(root, "Search Action");
            if (orb != null && tries < 5) {
                L.f(this, "opening SmartTube search screen");
                searchClicked = true;
                click(orb);
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 900);
                return;
            }
        }

        // Generic path: focused field, then any field, then a search entry
        // point, then give up quietly.
        AccessibilityNodeInfo edit = focusedEditable(root);
        if (edit == null) edit = firstEditable(root);
        if (edit != null) {
            if (setText(edit, text)) {
                L.f(this, "typed into " + target + ": " + text);
                busy = false;
            } else if (tries < 5) {
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 400);
            } else {
                L.f(this, "could not type into " + target);
                busy = false;
            }
            return;
        }

        if (!searchClicked && tries < 4) {
            AccessibilityNodeInfo s = findSearch(root);
            if (s != null) {
                searchClicked = true;
                L.f(this, "opening search in " + target);
                click(s);
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 900);
                return;
            }
        }

        if (tries < 5) {
            h.postDelayed(() -> deliverToApp(target, text, tries + 1), 600);
        } else {
            L.f(this, "no destination in " + target + "; nothing typed");
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
     * Node that looks like the app's search entry point, or null. Walks the
     * tree because findAccessibilityNodeInfosByText ignores content
     * descriptions, which is where most TV apps put "Search".
     */
    private AccessibilityNodeInfo findSearch(AccessibilityNodeInfo root) {
        return findSearch(root, 0);
    }

    private AccessibilityNodeInfo findSearch(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 25) return null;
        try {
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
}
