// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.sip;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * SIP phone: registers with a PBX, presents a full-screen call UI and negotiates G.711 RTP audio.
 *
 * Locking: all plugin state and every host call sit behind {@code state}. The plugin never calls the
 * client while holding it, so the network thread can always make progress.
 */
public final class SipPhonePlugin implements KioskPlugin {
    private final Object state = new Object();
    private PluginHost host;
    private Map<String, Object> settings = new LinkedHashMap<>();
    private SipClient client;
    private final SipRingtone ringtone = new SipRingtone();
    private final SipCallOverlay.Actions overlayActions = new SipCallOverlay.Actions() {
        public boolean answer() { return SipPhonePlugin.this.answer(); }
        public boolean decline() { return SipPhonePlugin.this.decline(); }
    };
    private String configKey = "";
    private int generation;
    private boolean registered;
    private boolean dnd;
    private String callState = "Ready";
    private String lastInvite = "No calls yet";
    private String currentCaller = "";
    private boolean inCall;
    private volatile boolean fullScreenActive;
    private volatile boolean fullScreenRequested;

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        synchronized (state) {
            this.host = host;
        }
        host.log("SIP Phone started");
        apply(settings);
    }

    @Override
    public void configure(Map<String, Object> settings) {
        apply(settings);
    }

    private void apply(Map<String, Object> next) {
        SipClient.Config config = new SipClient.Config(
            text(next, "server"), integer(next, "port", 5060), text(next, "user"), text(next, "authUser"),
            raw(next, "password"), integer(next, "localPort", 5060), integer(next, "expires", 300));
        boolean doNotDisturb = Boolean.TRUE.equals(next.get("dnd"));
        SipClient existing = null, old = null, fresh = null;
        String problem = null;
        synchronized (state) {
            if (host == null) return;
            settings = new LinkedHashMap<>(next);
            dnd = doNotDisturb;
            if (client != null && config.key().equals(configKey)) {
                existing = client;
            } else {
                old = client;
                client = null;
                configKey = config.key();
                generation++;
                registered = false;
                callState = "Ready";
                inCall = false;
                problem = config.problem();
                if (problem == null) {
                    fresh = new SipClient(config, new Events(generation));
                    client = fresh;
                }
            }
        }
        if (existing != null) existing.setDnd(doNotDisturb);
        if (old != null) {
            ringtone.stop();
            old.stop();
        }
        if (fresh != null) {
            fresh.setDnd(doNotDisturb);
            fresh.start();
        }
        synchronized (state) {
            if (host == null) return;
            try {
                if (old != null || fresh != null || problem != null) host.hideWindow();
                if (old != null || problem != null) closeCallOverlay(host);
                if (fresh != null) host.status("Connecting to " + config.server + " ...", false);
                if (problem != null) host.status(problem, true);
                publishLocked();
            } catch (RuntimeException ignored) {
                // Host revoked while stopping.
            }
        }
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        if ("decline".equals(command)) decline();
        else if ("answer".equals(command)) answer();
        else throw new IllegalArgumentException("Unknown command: " + command);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if ("window.action".equals(event)) {
            boolean active;
            synchronized (state) {
                active=inCall;
            }
            if(active) decline(); else answer();
        } else if ("window.closed".equals(event)) {
            // Closing the window does not end the call: the caller keeps hearing the ring tone and the
            // PBX decides. Declining is a deliberate tap on the button or the "decline" command.
            synchronized (state) {
                if (host != null) host.log("Call screen closed; the call is still ringing");
            }
        } else if ("overlay.closed".equals(event) && payload != null && "sip_call".equals(payload.get("key"))) {
            fullScreenActive = false;
            fullScreenRequested = false;
        } else if ("switch.dnd".equals(event)) {
            Object on = payload.get("on");
            if (!(on instanceof Boolean)) throw new IllegalArgumentException("Do not disturb switch requires a Boolean");
            Map<String, Object> next;
            PluginHost h;
            synchronized (state) {
                next = new LinkedHashMap<>(settings);
                h = host;
            }
            next.put("dnd", on);
            apply(next);
            if (h != null) h.saveSettings(next);
        }
    }

    private boolean decline() {
        SipClient current;
        synchronized (state) {
            current = client;
        }
        boolean declined = current != null && current.decline();
        if (declined) ringtone.stop();
        return declined;
    }

    private boolean answer() {
        SipClient current;
        synchronized (state) { current=client; }
        return current!=null && current.accept();
    }

    private boolean openCallOverlay(PluginHost h, String caller, boolean active) {
        try {
            h.showOverlay("sip_call", OverlaySpec.fullScreen().onTop(true), new SipCallOverlay(caller, active, overlayActions));
            fullScreenRequested = true;
            fullScreenActive = true;
            return true;
        } catch(RuntimeException error) {
            fullScreenActive=false;
            fullScreenRequested=false;
            try {
                h.status("Native call overlay unavailable: " + error.getMessage(),true);
                h.showWindow(active ? PluginText.get("screen.in_call", "In call") : PluginText.get("screen.incoming", "Incoming call"), displayCaller(caller), active ? PluginText.get("button.hang_up", "Hang up") : PluginText.get("button.answer", "Answer"));
            } catch(RuntimeException ignored) { }
            return false;
        }
    }

    private static String displayCaller(String caller) {
        return "Unknown caller".equals(caller) ? PluginText.get("screen.unknown_caller", "Unknown caller") : caller;
    }

    private void closeCallOverlay(PluginHost h) {
        fullScreenActive = false;
        fullScreenRequested = false;
        try { h.hideOverlay("sip_call"); }
        catch(RuntimeException ignored) { }
    }

    @Override
    public void stop() {
        SipClient old;
        synchronized (state) {
            generation++;
            old = client;
            client = null;
            host = null;
        }
        ringtone.stop();
        if (old != null) old.stop();
    }

    /** Publishes entity states. Call with {@code state} held. */
    private void publishLocked() {
        host.publishTextSensor("call", "Call status", callState);
        host.publishTextSensor("last_invite", "Last call", lastInvite);
        host.publishBinarySensor("registered", "SIP registered", "connectivity", registered);
        host.publishSwitch("dnd", "Do not disturb", dnd);
    }

    /** Wakes the display so the ringing window is visible. Failures only get logged. */
    private static void wake(final PluginHost h) {
        for (final String command : new String[] {"screenOn", "stopScreensaver"}) {
            try {
                h.executeCommand(command, Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                    try {
                        if (!ok) h.log(command + " failed: " + error);
                    } catch (RuntimeException ignored) {
                        // Session already revoked.
                    }
                });
            } catch (RuntimeException error) {
                h.log(command + " is unavailable: " + error.getMessage());
            }
        }
    }

    private static String text(Map<String, Object> values, String key) {
        return raw(values, key).trim();
    }

    private static String raw(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    /** Accepts numbers and numeric strings. Empty means the fallback, unparsable text becomes -1 so validation reports it. */
    private static int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null || value.toString().trim().isEmpty()) return fallback;
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    /** Events of one client generation. Late events of a replaced client are dropped. */
    private final class Events implements SipClient.Listener {
        private final int owner;

        Events(int owner) {
            this.owner = owner;
        }

        private boolean live() {
            return generation == owner && host != null;
        }

        /** Makes a failure visible in the log and in the plugin status instead of swallowing it. Call with state held. */
        private void fail(String what, RuntimeException error) {
            try {
                host.log(what + ": " + error);
                host.status(what + ": " + error.getMessage(), true);
            } catch (RuntimeException ignored) {
                // Host revoked, nothing left to report to.
            }
        }

        @Override
        public void onRegistration(boolean ok, String detail) {
            synchronized (state) {
                if (!live()) return;
                try {
                    registered = ok;
                    host.log(detail);
                    host.status(detail, !ok);
                    publishLocked();
                } catch (RuntimeException error) {
                    fail("Could not report status", error);
                }
            }
        }

        @Override
        public void onInvite(String note) {
            synchronized (state) {
                if (!live()) return;
                try {
                    lastInvite = new SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(new Date()) + " " + note;
                    host.log("INVITE: " + note);
                    publishLocked();
                } catch (RuntimeException error) {
                    fail("Could not report INVITE", error);
                }
            }
        }

        @Override
        public void onIncomingCall(String caller) {
            synchronized (state) {
                if (!live()) return;
                try {
                    callState = "Ringing: " + caller;
                    currentCaller = caller;
                    try {
                        ringtone.start();
                    } catch (Exception error) {
                        host.log("Could not play ringtone: " + error.getMessage());
                    }
                    // Wake first: dismissing the screensaver must not take the new window down with it.
                    wake(host);
                    if (openCallOverlay(host, caller, false)) host.log("Native full-screen call overlay opened for " + caller);
                    else host.log("Native call overlay unavailable; showing the fallback call window");
                    publishLocked();
                } catch (RuntimeException error) {
                    fail("Could not show call screen", error);
                }
            }
        }

        @Override
        public void onCallAnswered(String caller) {
            synchronized (state) {
                if (!live()) return;
                try {
                    inCall = true;
                    ringtone.stop();
                    callState = "In call: " + caller;
                    host.status("Call active · G.711/RTP", false);
                    if(fullScreenRequested || fullScreenActive) openCallOverlay(host, caller, true);
                    else host.showWindow(PluginText.get("screen.in_call", "In call"), caller, PluginText.get("button.hang_up", "Hang up"));
                    publishLocked();
                } catch (RuntimeException error) { fail("Could not report call status", error); }
            }
        }

        @Override
        public void onCallEnded(String reason) {
            synchronized (state) {
                if (!live()) return;
                try {
                    inCall = false;
                    ringtone.stop();
                    callState = "Ready";
                    currentCaller = "";
                    fullScreenActive = false;
                    fullScreenRequested = false;
                    closeCallOverlay(host);
                    host.hideWindow();
                    host.log("Call ended: " + reason);
                    publishLocked();
                } catch (RuntimeException error) {
                    fail("Could not report call end", error);
                }
            }
        }

        @Override
        public void log(String message) {
            synchronized (state) {
                if (!live()) return;
                try {
                    host.log(message);
                } catch (RuntimeException ignored) {
                    // Host revoked.
                }
            }
        }
    }
}
