// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.sip;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/** Runs the plugin against a mock PBX on loopback. No device and no Android SDK needed. */
public final class SipPhoneTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Host that records what the plugin does. */
    static class FakeHost implements PluginHost {
        final List<String> windows = Collections.synchronizedList(new ArrayList<String>());
        final List<String> statuses = Collections.synchronizedList(new ArrayList<String>());
        final List<String> commands = Collections.synchronizedList(new ArrayList<String>());
        final List<String> overlayKeys = Collections.synchronizedList(new ArrayList<String>());
        final List<OverlaySpec> overlaySpecs = Collections.synchronizedList(new ArrayList<OverlaySpec>());
        final List<OverlayFactory> overlayFactories = Collections.synchronizedList(new ArrayList<OverlayFactory>());
        final AtomicInteger overlayHides = new AtomicInteger();
        final Map<String, Object> commandArgs = new ConcurrentHashMap<>();
        final Map<String, Object> values = new ConcurrentHashMap<>();
        final AtomicInteger hides = new AtomicInteger();
        volatile Map<String, Object> saved;
        volatile String overlayFailure;

        @Override public void showWindow(String title, String message, String button) { windows.add(title + "|" + message + "|" + button); }
        @Override public void hideWindow() { hides.incrementAndGet(); }
        @Override public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) {
            if (overlayFailure != null) throw new UnsupportedOperationException(overlayFailure);
            overlayKeys.add(key); overlaySpecs.add(spec); overlayFactories.add(factory);
        }
        @Override public void hideOverlay(String key) { overlayHides.incrementAndGet(); }
        @Override public void log(String message) { System.out.println("  [host] " + message); }
        @Override public void status(String message, boolean error) { statuses.add((error ? "E:" : "") + message); }
        @Override public void saveSettings(Map<String, Object> values) { saved = values; }
        @Override public void publishTextSensor(String key, String name, String state) { values.put("text." + key, state); }
        @Override public void publishBinarySensor(String key, String name, String deviceClass, Boolean state) { values.put("binary." + key, state); }
        @Override public void publishSwitch(String key, String name, boolean state) { values.put("switch." + key, state); }
        @Override public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            commands.add(command);
            commandArgs.put(command, new LinkedHashMap<>(arguments));
            callback.onResult(true, null, null);
        }
    }

    static final class Received {
        final SipMessage message;
        final SocketAddress from;
        Received(SipMessage message, SocketAddress from) { this.message = message; this.from = from; }
    }

    private static Received receive(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
        socket.receive(packet);
        SipMessage message = SipMessage.parse(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
        check(message != null, "PBX received something that is not SIP");
        return new Received(message, packet.getSocketAddress());
    }

    private static void send(DatagramSocket socket, SocketAddress to, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length, to));
    }

    private static void await(String what, java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            if (condition.call()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Timeout: " + what);
    }

    private static String reply(SipMessage request, String status, String toTag, String extra) {
        StringBuilder sb = new StringBuilder("SIP/2.0 ").append(status).append("\r\n");
        for (String via : request.all("via")) sb.append("Via: ").append(via).append("\r\n");
        sb.append("From: ").append(request.header("from")).append("\r\n");
        sb.append("To: ").append(request.header("to")).append(";tag=").append(toTag).append("\r\n");
        sb.append("Call-ID: ").append(request.header("call-id")).append("\r\n");
        sb.append("CSeq: ").append(request.header("cseq")).append("\r\n");
        sb.append(extra).append("Content-Length: 0\r\n\r\n");
        return sb.toString();
    }

    private static String invite(String callId, String branch, int pbxPort, int pluginPort) {
        return "INVITE sip:1001@127.0.0.1:" + pluginPort + " SIP/2.0\r\n"
            + "Via: SIP/2.0/UDP 127.0.0.1:" + pbxPort + ";branch=" + branch + "\r\n"
            + "Max-Forwards: 70\r\n"
            + "From: \"Alice\" <sip:alice@pbx>;tag=a1\r\n"
            + "To: <sip:1001@pbx>\r\n"
            + "Call-ID: " + callId + "\r\n"
            + "CSeq: 1 INVITE\r\n"
            + "Contact: <sip:alice@127.0.0.1:" + pbxPort + ">\r\n"
            + "Content-Length: 0\r\n\r\n";
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static Map<String, Object> settings(int pbxPort, int pluginPort) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("server", "127.0.0.1");
        values.put("port", String.valueOf(pbxPort));
        values.put("user", "1001");
        values.put("authUser", "");
        values.put("password", "secret");
        values.put("localPort", String.valueOf(pluginPort));
        values.put("expires", "60");
        values.put("dnd", false);
        return values;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        translations();
        digestVector();
        parser();
        ranges();
        windowFailure();
        mediaNegotiation();
        flow();
        System.out.println("SipPhoneTest ok");
    }

    private static void translations() {
        check("Answer".equals(PluginText.get("button.answer", "Answer")), "English is the default locale");
        Locale previous=Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        check("Eingehender Anruf".equals(PluginText.get("screen.incoming", "Incoming call")), "German call-screen labels load from de.properties");
        check("Annehmen".equals(PluginText.get("button.answer", "Answer")), "German answer label is translated");
        check("Ready".equals(PluginText.get("state.ready", "Ready")), "plugin status remains English");
        Locale.setDefault(previous);
        System.out.println("  translations ok");
    }

    private static void digestVector() {
        // RFC 2617 section 3.5 example.
        String response = SipDigest.response("Mufasa", "testrealm@host.com", "Circle Of Life", "GET", "/dir/index.html",
            "dcd98b7102dd2f0e8b11d0f600bfb0c093", "auth", "00000001", "0a4f113b");
        check("6629fae49393a05397450978507c4ef1".equals(response), "RFC 2617 digest vector, got " + response);
        Map<String, String> params = SipDigest.parseParams("Digest realm=\"a,b\", nonce=\"n\", qop=\"auth,auth-int\", algorithm=MD5, stale=false");
        check("a,b".equals(params.get("realm")) && "auth,auth-int".equals(params.get("qop")) && "MD5".equals(params.get("algorithm")), "challenge parsing " + params);
        System.out.println("  digest ok");
    }

    private static void parser() throws Exception {
        check("Alice (alice)".equals(SipMessage.caller("\"Alice\" <sip:alice@pbx>;tag=a1")), "quoted display name");
        check("alice".equals(SipMessage.caller("<sip:alice@pbx>;tag=a1")), "no display name");
        check("alice".equals(SipMessage.caller("sip:alice@pbx;tag=x")), "bare uri");
        check("Unknown caller".equals(SipMessage.caller(null)), "missing from");
        SipMessage compact = SipMessage.parse("OPTIONS sip:x SIP/2.0\r\nv: SIP/2.0/UDP a\r\nf: <sip:a>\r\nt: <sip:b>\r\ni: 1\r\nCSeq: 5 OPTIONS\r\n\r\n");
        check(compact != null && "1".equals(compact.header("call-id")) && 5 == compact.cseqNumber(), "compact header forms");
        check(SipMessage.parse("GET / HTTP/1.1\r\n\r\n") == null, "non-SIP rejected");
        SipAudio.Offer offer=SipAudio.parseOffer("v=0\r\nc=IN IP4 127.0.0.1\r\nm=audio 1234 RTP/AVP 8\r\n");
        check(offer.port==1234&&offer.payload==8,"PCMA offer parsed");
        check((SipAudio.linearToMu((short)0)&255)==255,"PCMU zero vector");
        check((SipAudio.linearToA((short)0)&255)==213,"PCMA zero vector");
        System.out.println("  parser ok");
    }

    private static void ranges() throws Exception {
        String[][] bad = {{"port", "0"}, {"port", "70000"}, {"port", "abc"}, {"localPort", "80"}, {"expires", "30"}, {"expires", "5000"}};
        for (String[] pair : bad) {
            FakeHost host = new FakeHost();
            SipPhonePlugin plugin = new SipPhonePlugin();
            Map<String, Object> values = settings(5060, 5062);
            values.put(pair[0], pair[1]);
            plugin.start(host, values);
            plugin.stop();
            check(!host.statuses.isEmpty() && host.statuses.get(host.statuses.size() - 1).startsWith("E:"), pair[0] + "=" + pair[1] + " must report an error, got " + host.statuses);
        }
        FakeHost host = new FakeHost();
        SipPhonePlugin plugin = new SipPhonePlugin();
        Map<String, Object> defaults = settings(5060, 5060);
        defaults.put("port", "");
        defaults.put("localPort", "");
        plugin.start(host, defaults);
        plugin.stop();
        check(host.statuses.get(0).startsWith("Connecting to"), "empty ports fall back to 5060, got " + host.statuses);
        System.out.println("  ranges ok");
    }

    /** A host that refuses native overlays must produce a visible fallback and error. */
    private static void windowFailure() throws Exception {
        DatagramSocket pbx = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        pbx.setSoTimeout(4000);
        int pbxPort = pbx.getLocalPort();
        int pluginPort = freePort();
        FakeHost host = new FakeHost();
        host.overlayFailure = "overlay denied";
        SipPhonePlugin plugin = new SipPhonePlugin();
        plugin.start(host, settings(pbxPort, pluginPort));
        try {
            Received register = receive(pbx);
            send(pbx, register.from, invite("call9@pbx", "z9hG4bKinv9", pbxPort, pluginPort));
            check(receive(pbx).message.status() == 100, "100 even if the window fails");
            check(receive(pbx).message.status() == 180, "180 even if the window fails");
            await("invite still recorded", () -> host.values.get("text.last_invite").toString().endsWith("Alice (alice): klingelt"));
            await("overlay error visible", () -> host.statuses.stream().anyMatch(status -> status.contains("overlay denied")));
            await("fallback window visible", () -> !host.windows.isEmpty());
            check(host.statuses.stream().anyMatch(status -> status.contains("overlay denied")), "overlay error visible, got " + host.statuses);
            check(host.overlayKeys.isEmpty(), "rejected overlay was not recorded as open");
            check("Incoming call|Alice (alice)|Answer".equals(host.windows.get(0)), "fallback accept button");
            System.out.println("  window failure visible ok");
        } finally {
            plugin.stop();
            pbx.close();
        }
    }

    private static void flow() throws Exception {
        DatagramSocket pbx = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        pbx.setSoTimeout(4000);
        int pbxPort = pbx.getLocalPort();
        int pluginPort = freePort();
        FakeHost host = new FakeHost();
        SipPhonePlugin plugin = new SipPhonePlugin();
        plugin.start(host, settings(pbxPort, pluginPort));
        try {
            // 1. REGISTER without credentials is challenged, the retry carries a valid digest.
            Received first = receive(pbx);
            check("REGISTER".equals(first.message.method()), "first request is REGISTER");
            check(first.message.header("authorization") == null, "first REGISTER has no credentials");
            send(pbx, first.from, reply(first.message, "401 Unauthorized", "srv1",
                "WWW-Authenticate: Digest realm=\"test\", nonce=\"abc123\", algorithm=MD5, qop=\"auth\"\r\n"));
            Received second = receive(pbx);
            Map<String, String> auth = SipDigest.parseParams(second.message.header("authorization"));
            check("1001".equals(auth.get("username")) && "test".equals(auth.get("realm")), "digest username/realm " + auth);
            check("sip:127.0.0.1".equals(auth.get("uri")), "digest uri " + auth.get("uri"));
            String expected = SipDigest.response("1001", "test", "secret", "REGISTER", "sip:127.0.0.1",
                "abc123", "auth", auth.get("nc"), auth.get("cnonce"));
            check(expected.equals(auth.get("response")), "digest response matches");
            send(pbx, second.from, reply(second.message, "200 OK", "srv1", "Expires: 60\r\n"));
            await("registered", () -> Boolean.TRUE.equals(host.values.get("binary.registered")));
            check(host.statuses.get(host.statuses.size() - 1).startsWith("Registered"), "status " + host.statuses);
            SocketAddress plugin1 = second.from;
            System.out.println("  registration ok");

            // 2. Incoming call rings, window shows the caller, display is woken.
            send(pbx, plugin1, invite("call1@pbx", "z9hG4bKinv1", pbxPort, pluginPort));
            check(receive(pbx).message.status() == 100, "100 Trying");
            Received ringing = receive(pbx);
            check(ringing.message.status() == 180, "180 Ringing");
            check(ringing.message.header("to").contains("tag="), "180 carries a To tag");
            await("full screen overlay", () -> !host.overlayKeys.isEmpty());
            check("sip_call".equals(host.overlayKeys.get(0)), "stable native overlay key");
            OverlaySpec callSpec = host.overlaySpecs.get(0);
            check(callSpec.width == OverlaySpec.FILL && callSpec.height == OverlaySpec.FILL && callSpec.onTop && callSpec.touchable,
                "incoming call uses a touchable, full-screen native overlay");
            await("display woken", () -> host.commands.contains("screenOn") && host.commands.contains("stopScreensaver"));
            await("call sensor", () -> "Ringing: Alice (alice)".equals(host.values.get("text.call")));
            // Retransmitted INVITE gets the ringing response again, no second window.
            send(pbx, plugin1, invite("call1@pbx", "z9hG4bKinv1", pbxPort, pluginPort));
            check(receive(pbx).message.status() == 180, "retransmission answered with 180");
            check(host.overlayKeys.size() == 1, "no duplicate overlay");
            System.out.println("  ringing ok");

            // 3. Caller cancels: 200 for CANCEL and 487 for the INVITE, window closes.
            int hidesBefore = host.hides.get();
            int overlayHidesBefore = host.overlayHides.get();
            send(pbx, plugin1, "CANCEL sip:1001@127.0.0.1 SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:" + pbxPort + ";branch=z9hG4bKinv1\r\n"
                + "From: \"Alice\" <sip:alice@pbx>;tag=a1\r\nTo: <sip:1001@pbx>\r\nCall-ID: call1@pbx\r\nCSeq: 1 CANCEL\r\nContent-Length: 0\r\n\r\n");
            boolean cancelOk = false, terminated = false;
            for (int i = 0; i < 2; i++) {
                SipMessage message = receive(pbx).message;
                if ("CANCEL".equals(message.cseqMethod()) && message.status() == 200) cancelOk = true;
                if ("INVITE".equals(message.cseqMethod()) && message.status() == 487) terminated = true;
            }
            check(cancelOk && terminated, "CANCEL answered with 200 and INVITE with 487");
            await("window hidden", () -> host.hides.get() > hidesBefore);
            await("native overlay hidden", () -> host.overlayHides.get() > overlayHidesBefore);
            await("sensor back to idle", () -> "Ready".equals(host.values.get("text.call")));
            // ACK for the 487 must not produce a reply. The next message must be the OPTIONS answer.
            send(pbx, plugin1, "ACK sip:1001@127.0.0.1 SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:" + pbxPort + ";branch=z9hG4bKinv1\r\n"
                + "From: \"Alice\" <sip:alice@pbx>;tag=a1\r\nTo: <sip:1001@pbx>;tag=x\r\nCall-ID: call1@pbx\r\nCSeq: 1 ACK\r\nContent-Length: 0\r\n\r\n");
            send(pbx, plugin1, "OPTIONS sip:1001@127.0.0.1 SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:" + pbxPort + ";branch=z9hG4bKopt\r\n"
                + "From: <sip:pbx@pbx>;tag=p\r\nTo: <sip:1001@pbx>\r\nCall-ID: opt1@pbx\r\nCSeq: 1 OPTIONS\r\nContent-Length: 0\r\n\r\n");
            SipMessage options = receive(pbx).message;
            check("OPTIONS".equals(options.cseqMethod()) && options.status() == 200, "OPTIONS answered, ACK ignored: " + options.startLine);
            // INVITE retransmitted after the final response gets the 487 again.
            send(pbx, plugin1, invite("call1@pbx", "z9hG4bKinv1", pbxPort, pluginPort));
            check(receive(pbx).message.status() == 487, "late retransmission gets the final response");
            check(host.overlayKeys.size() == 1, "still one full screen overlay");
            System.out.println("  cancel ok");

            // 4. Decline through the command.
            send(pbx, plugin1, invite("call2@pbx", "z9hG4bKinv2", pbxPort, pluginPort));
            check(receive(pbx).message.status() == 100, "call2 100");
            check(receive(pbx).message.status() == 180, "call2 180");
            await("second overlay", () -> host.overlayKeys.size() == 2);
            plugin.execute("decline", Collections.<String, Object>emptyMap());
            Received declined = receive(pbx);
            check(declined.message.status() == 603 && "INVITE".equals(declined.message.cseqMethod()), "decline sends 603");
            System.out.println("  decline ok");

            // 5. Decline through the native call overlay action.
            send(pbx, plugin1, invite("call3@pbx", "z9hG4bKinv3", pbxPort, pluginPort));
            check(receive(pbx).message.status() == 100, "call3 100");
            check(receive(pbx).message.status() == 180, "call3 180");
            await("third overlay", () -> host.overlayKeys.size() == 3);
            plugin.execute("decline", Collections.<String, Object>emptyMap());
            check(receive(pbx).message.status() == 603, "decline action sends 603");
            System.out.println("  native overlay actions ok");

            // 6. Do not disturb answers busy without ringing.
            plugin.onEvent("switch.dnd", Collections.<String, Object>singletonMap("on", true));
            check(Boolean.TRUE.equals(host.saved.get("dnd")), "dnd saved");
            await("dnd switch state", () -> Boolean.TRUE.equals(host.values.get("switch.dnd")));
            send(pbx, plugin1, invite("call4@pbx", "z9hG4bKinv4", pbxPort, pluginPort));
            check(receive(pbx).message.status() == 486, "dnd answers 486");
            check(host.overlayKeys.size() == 3, "no overlay while dnd");
            System.out.println("  dnd ok");

            // 6b. Asterisk/FreePBX style INVITE with SDP body. Closing the window must not decline the call.
            await("last invite shows dnd", () -> host.values.get("text.last_invite").toString().contains("rejected (Do Not Disturb)"));
            plugin.onEvent("switch.dnd", Collections.<String, Object>singletonMap("on", false));
            String sdp = "v=0\r\no=- 1 1 IN IP4 192.168.3.4\r\ns=Asterisk\r\nc=IN IP4 192.168.3.4\r\nt=0 0\r\nm=audio 10000 RTP/AVP 0 8 101\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\na=sendrecv\r\n";
            String asterisk = "INVITE sip:1000@127.0.0.1:" + pluginPort + " SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:" + pbxPort + ";rport;branch=z9hG4bKPj64be4221-b9c1\r\n"
                + "From: \"Peter\" <sip:1001@127.0.0.1>;tag=316d60e2-40e6\r\n"
                + "To: <sip:1000@127.0.0.1>\r\n"
                + "Contact: <sip:1001@127.0.0.1:" + pbxPort + ">\r\n"
                + "Call-ID: 2768e62c-7b13-472b@127.0.0.1\r\n"
                + "CSeq: 56234 INVITE\r\n"
                + "Allow: OPTIONS, REGISTER, SUBSCRIBE, NOTIFY, PUBLISH, INVITE, ACK, BYE, CANCEL, UPDATE, PRACK, MESSAGE, REFER\r\n"
                + "Supported: 100rel, timer, norefersub\r\n"
                + "Session-Expires: 1800\r\n"
                + "Min-SE: 90\r\n"
                + "Max-Forwards: 70\r\n"
                + "User-Agent: FPBX-17.0.33(22.10.1)\r\n"
                + "Content-Type: application/sdp\r\n"
                + "Content-Length:  " + sdp.length() + "\r\n\r\n" + sdp;
            send(pbx, plugin1, asterisk);
            check(receive(pbx).message.status() == 100, "asterisk invite 100");
            check(receive(pbx).message.status() == 180, "asterisk invite 180");
            await("fourth overlay", () -> host.overlayKeys.size() == 4);
            await("last invite shows ringing", () -> host.values.get("text.last_invite").toString().endsWith("Peter (1001): klingelt"));
            plugin.onEvent("window.closed", Collections.<String, Object>emptyMap());
            send(pbx, plugin1, "CANCEL sip:1000@127.0.0.1:" + pluginPort + " SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:" + pbxPort + ";rport;branch=z9hG4bKPj64be4221-b9c1\r\n"
                + "From: \"Peter\" <sip:1001@127.0.0.1>;tag=316d60e2-40e6\r\nTo: <sip:1000@127.0.0.1>\r\n"
                + "Call-ID: 2768e62c-7b13-472b@127.0.0.1\r\nCSeq: 56234 CANCEL\r\nContent-Length: 0\r\n\r\n");
            boolean closedCancelOk = false, closedTerminated = false;
            for (int i = 0; i < 2; i++) {
                SipMessage message = receive(pbx).message;
                check(message.status() != 603, "closing the window must not decline the call");
                if ("CANCEL".equals(message.cseqMethod()) && message.status() == 200) closedCancelOk = true;
                if ("INVITE".equals(message.cseqMethod()) && message.status() == 487) closedTerminated = true;
            }
            check(closedCancelOk && closedTerminated, "ringing continued until the caller cancelled");
            System.out.println("  asterisk invite ok");

            // 7. A wrong password ends in a clear status instead of a retry loop.
            Map<String, Object> wrong = settings(pbxPort, pluginPort);
            wrong.put("password", "wrong");
            plugin.configure(wrong);
            Received again = receive(pbx);
            send(pbx, again.from, reply(again.message, "401 Unauthorized", "srv2", "WWW-Authenticate: Digest realm=\"test\", nonce=\"n2\"\r\n"));
            Received retry = receive(pbx);
            send(pbx, retry.from, reply(retry.message, "401 Unauthorized", "srv2", "WWW-Authenticate: Digest realm=\"test\", nonce=\"n3\"\r\n"));
            await("auth failure status", () -> host.statuses.get(host.statuses.size() - 1).startsWith("E:Registration rejected"));
            await("registered flag cleared", () -> Boolean.FALSE.equals(host.values.get("binary.registered")));
            System.out.println("  wrong password ok");
        } finally {
            plugin.stop();
            pbx.close();
        }
    }

    private static void mediaNegotiation() throws Exception {
        DatagramSocket pbx = new DatagramSocket(0, InetAddress.getByName("127.0.0.1")); pbx.setSoTimeout(4000);
        int pbxPort=pbx.getLocalPort(), pluginPort=freePort(); FakeHost host=new FakeHost(); SipPhonePlugin plugin=new SipPhonePlugin();
        plugin.start(host,settings(pbxPort,pluginPort));
        try {
            Received registration=receive(pbx); send(pbx,registration.from,reply(registration.message,"200 OK","srv", "Expires: 60\r\n"));
            await("registration",()->Boolean.TRUE.equals(host.values.get("binary.registered")));
            String sdp="v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=PBX\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio 12000 RTP/AVP 0 8\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\n";
            String invite=invite("audio@pbx","z9hG4bKaudio",pbxPort,pluginPort).replace("Content-Length: 0\r\n\r\n","Content-Type: application/sdp\r\nContent-Length: "+sdp.length()+"\r\n\r\n"+sdp);
            send(pbx,registration.from,invite); check(receive(pbx).message.status()==100,"audio invite trying"); check(receive(pbx).message.status()==180,"audio invite ringing");
            await("audio overlay",()->!host.overlayFactories.isEmpty()); plugin.execute("answer",Collections.<String,Object>emptyMap());
            Received ok=receive(pbx); check(ok.message.status()==200,"answer returns 200");
            await("active call overlay refresh",()->host.overlayFactories.size() >= 2);
            SipCallOverlay activeOverlay=(SipCallOverlay)host.overlayFactories.get(host.overlayFactories.size()-1);
            check(activeOverlay.active,"answer refreshes the native overlay in active-call mode");
            check(host.windows.isEmpty(),"successful full-screen call does not fall back to a floating window");
            check("application/sdp".equals(ok.message.header("content-type")),"answer has SDP content type");
            check(ok.message.body.contains("m=audio ")&&ok.message.body.contains("PCMU/8000"),"SDP advertises G.711 RTP");
            System.out.println("  media negotiation ok");
        } finally { plugin.stop(); pbx.close(); }
    }
}
