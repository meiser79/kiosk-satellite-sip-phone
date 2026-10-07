// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.sip;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;

/**
 * SIP user agent over UDP: registers with a PBX, accepts calls and carries G.711 RTP media.
 * All network work runs on one plugin-owned thread; listener callbacks never hold the internal lock.
 */
final class SipClient {
    interface Listener {
        void onRegistration(boolean registered, String detail);
        void onIncomingCall(String caller);
        /** Every new INVITE with its outcome, including calls that were turned away. */
        void onInvite(String note);
        void onCallEnded(String reason);
        default void onCallAnswered(String caller) { }
        void log(String message);
    }

    static final class Config {
        final String server, user, authUser, password;
        final int port, localPort, expires;

        Config(String server, int port, String user, String authUser, String password, int localPort, int expires) {
            this.server = server;
            this.port = port;
            this.user = user;
            this.authUser = authUser;
            this.password = password;
            this.localPort = localPort;
            this.expires = expires;
        }

        String key() {
            return server + "|" + port + "|" + user + "|" + authUser + "|" + password + "|" + localPort + "|" + expires;
        }

        /** A human readable problem, or null when the configuration can be used. */
        String problem() {
            if (server.isEmpty()) return "No server configured";
            if (user.isEmpty()) return "No user configured";
            if (port < 1 || port > 65535) return "Server port must be between 1 and 65535";
            if (localPort < 1024 || localPort > 65535) return "Local port must be between 1024 and 65535";
            if (expires < 60 || expires > 3600) return "Registration expiry must be between 60 and 3600 seconds";
            for (String value : new String[] {server, user, authUser, password}) {
                if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return "Settings must not contain line breaks";
            }
            // The password only enters the digest hash, the other values are written into headers.
            for (String value : new String[] {server, user, authUser}) {
                if (value.indexOf('"') >= 0 || value.indexOf(' ') >= 0 || value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
                    return "Server and user values must not contain spaces, quotes or angle brackets";
                }
            }
            return null;
        }
    }

    private static final long RETRY_MS = 30_000;
    private static final int NETWORK_RETRY_MS = 5_000;
    private static final String ALLOW = "INVITE, ACK, CANCEL, BYE, OPTIONS";

    /** An unanswered incoming INVITE. */
    private static final class Call {
        final SipMessage invite;
        final String toTag;
        final InetSocketAddress source;
        byte[] ringing;
        SipAudio.Offer offer;
        SipAudio audio;
        boolean accepted;
        boolean mediaStarted;

        Call(SipMessage invite, String toTag, InetSocketAddress source, byte[] ringing, SipAudio.Offer offer) {
            this.invite = invite; this.toTag = toTag; this.source = source; this.ringing = ringing; this.offer=offer;
        }
    }

    private final Config config;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();
    private final String registerCallId = hex(8) + "@ks-sip";
    private final String registerTag = hex(4);

    private volatile boolean running;
    private volatile boolean dnd;
    private volatile DatagramSocket socket;
    private Thread thread;

    // Touched by the network thread only, except where noted.
    private InetAddress serverAddress;
    private String localIp;
    private int cseq;
    private int lastRegisterCseq = -1;
    private boolean lastRegisterHadAuth;
    private long nextRegisterAt;

    // Guarded by lock.
    private boolean registered;
    private String lastDetail = "";
    private Call call;
    private String lastFinalCallId;
    private byte[] lastFinalResponse;
    private InetSocketAddress lastFinalTarget;

    SipClient(Config config, Listener listener) {
        this.config = config;
        this.listener = listener;
    }

    void setDnd(boolean value) { dnd = value; }

    void start() {
        running = true;
        thread = new Thread(this::run, "sip-phone");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops the network thread and waits briefly for it. Safe to call more than once. */
    void stop() {
        running = false;
        synchronized(lock) { if(call!=null && call.audio!=null) call.audio.close(); call=null; }
        DatagramSocket current = socket;
        if (current != null) current.close();
        Thread worker = thread;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(1000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Declines the ringing call. Returns false when no call is ringing. */
    boolean decline() {
        boolean active;
        synchronized (lock) {
            if (call == null) return false;
            active=call.accepted;
            if (active) {
                Call currentCall=call;
                String uri="sip:"+config.user+"@"+config.server;
                int byeCseq=currentCall.invite.cseqNumber()+1;
                StringBuilder bye=new StringBuilder("BYE ").append(uri).append(" SIP/2.0\r\n")
                    .append("Via: SIP/2.0/UDP ").append(localIp).append(':').append(config.localPort).append(";branch=").append(branch()).append(";rport\r\n")
                    .append("Max-Forwards: 70\r\nFrom: ").append(withTag(currentCall.invite.header("to"),currentCall.toTag)).append("\r\n")
                    .append("To: ").append(currentCall.invite.header("from")).append("\r\nCall-ID: ").append(currentCall.invite.header("call-id"))
                    .append("\r\nCSeq: ").append(byeCseq).append(" BYE\r\nContent-Length: 0\r\n\r\n");
                sendQuiet(bye.toString().getBytes(StandardCharsets.UTF_8),currentCall.source);
                if(currentCall.audio!=null) currentCall.audio.close();
                finish(currentCall.invite.header("call-id"),currentCall.ringing,currentCall.source);
            } else {
                byte[] response = response(call.invite, 603, "Decline", call.toTag);
                sendQuiet(response, call.source);
                finish(call.invite.header("call-id"), response, call.source);
            }
        }
        notifyEnded(active ? "Call ended" : "Call declined");
        return true;
    }

    private String withTag(String header,String tag) {
        if(header==null)return "";
        return header.toLowerCase().contains(";tag=")?header:header+";tag="+tag;
    }

    /** Accepts the ringing call; RTP starts when ACK arrives. */
    boolean accept() {
        synchronized (lock) {
            if (call == null || call.accepted) return false;
            if (call.offer == null) { listener.log("Cannot answer: INVITE has no supported SDP audio offer"); return false; }
            try {
                call.audio = new SipAudio(InetAddress.getByName(localIp), call.offer);
                call.ringing = responseWithBody(call.invite, 200, "OK", call.toTag, call.audio.sdp());
                call.accepted=true;
                sendQuiet(call.ringing, call.source);
                listener.onCallAnswered(SipMessage.caller(call.invite.header("from")));
                return true;
            } catch (Exception error) {
                listener.log("Could not negotiate audio: " + error.getMessage());
                return false;
            }
        }
    }

    // ---- network thread ----

    private void run() {
        while (running) {
            try {
                open();
                long now = System.currentTimeMillis();
                if (now >= nextRegisterAt) {
                    sendRegister(null, null);
                    // Retry if the PBX never answers; a response replaces this deadline.
                    nextRegisterAt = now + RETRY_MS;
                }
                receiveOnce();
            } catch (IOException | RuntimeException error) {
                if (!running) return;
                closeSocket();
                report(false, "Netzwerkfehler: " + error.getMessage());
                pause(NETWORK_RETRY_MS);
            }
        }
        closeSocket();
    }

    private void open() throws IOException {
        if (socket != null) return;
        String problem = config.problem();
        if (problem != null) throw new IOException(problem);
        serverAddress = InetAddress.getByName(config.server);
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(serverAddress, config.port);
            localIp = probe.getLocalAddress().getHostAddress();
        }
        if (localIp.contains(":") || localIp.equals("0.0.0.0")) throw new IOException("No local IPv4 address found for the server");
        DatagramSocket bound = new DatagramSocket(null);
        bound.setReuseAddress(true);
        bound.bind(new InetSocketAddress(config.localPort));
        bound.setSoTimeout(500);
        socket = bound;
        cseq = 0;
        lastRegisterCseq = -1;
        nextRegisterAt = 0;
    }

    private void closeSocket() {
        DatagramSocket current = socket;
        socket = null;
        if (current != null) current.close();
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            if (!running) Thread.currentThread().interrupt();
        }
    }

    private void receiveOnce() throws IOException {
        DatagramSocket current = socket;
        if (current == null) return;
        DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
        try {
            current.receive(packet);
        } catch (SocketTimeoutException timeout) {
            return;
        }
        SipMessage message = SipMessage.parse(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
        if (message == null) return;
        InetSocketAddress from = new InetSocketAddress(packet.getAddress(), packet.getPort());
        try {
            if (message.isRequest()) handleRequest(message, from);
            else handleResponse(message);
        } catch (RuntimeException error) {
            // A bug in message handling must not tear down the registration.
            listener.log("Unerwarteter Fehler bei \"" + message.startLine + "\": " + error);
        }
    }

    // ---- registration ----

    private void sendRegister(String authName, String authValue) throws IOException {
        cseq++;
        lastRegisterCseq = cseq;
        lastRegisterHadAuth = authValue != null;
        String aor = "sip:" + config.user + "@" + config.server;
        StringBuilder sb = new StringBuilder()
            .append("REGISTER sip:").append(config.server).append(" SIP/2.0\r\n")
            .append("Via: SIP/2.0/UDP ").append(localIp).append(':').append(config.localPort)
            .append(";branch=").append(branch()).append(";rport\r\n")
            .append("Max-Forwards: 70\r\n")
            .append("From: <").append(aor).append(">;tag=").append(registerTag).append("\r\n")
            .append("To: <").append(aor).append(">\r\n")
            .append("Call-ID: ").append(registerCallId).append("\r\n")
            .append("CSeq: ").append(cseq).append(" REGISTER\r\n")
            .append("Contact: <sip:").append(config.user).append('@').append(localIp).append(':')
            .append(config.localPort).append(">\r\n")
            .append("Expires: ").append(config.expires).append("\r\n")
            .append("Allow: ").append(ALLOW).append("\r\n")
            .append("User-Agent: KS-SIP-Plugin/0.1.0\r\n");
        if (authValue != null) sb.append(authName).append(": ").append(authValue).append("\r\n");
        sb.append("Content-Length: 0\r\n\r\n");
        send(sb.toString().getBytes(StandardCharsets.UTF_8), new InetSocketAddress(serverAddress, config.port));
    }

    private void handleResponse(SipMessage message) throws IOException {
        if (!"REGISTER".equals(message.cseqMethod()) || message.cseqNumber() != lastRegisterCseq) return;
        int code = message.status();
        if (code < 200) return;
        if (code == 200) {
            int expires = registrationExpires(message);
            nextRegisterAt = System.currentTimeMillis() + Math.max(30, expires) * 500L;
            report(true, "Registered (expires in " + expires + " s)");
            return;
        }
        if (code == 401 || code == 407) {
            if (lastRegisterHadAuth) {
                failRegistration("Registration rejected; check the username or password");
                return;
            }
            String challenge = message.header(code == 401 ? "www-authenticate" : "proxy-authenticate");
            if (challenge == null) {
                failRegistration("Server requires authentication but sent no challenge");
                return;
            }
            Map<String, String> params = SipDigest.parseParams(challenge);
            String algorithm = params.get("algorithm");
            if (algorithm != null && !algorithm.equalsIgnoreCase("MD5")) {
                failRegistration("Digest algorithm " + algorithm + " is not supported (MD5 only)");
                return;
            }
            String qop = null;
            String offered = params.get("qop");
            if (offered != null) {
                for (String option : offered.split(",")) if (option.trim().equalsIgnoreCase("auth")) qop = "auth";
                if (qop == null) {
                    failRegistration("qop \"" + offered + "\" is not supported");
                    return;
                }
            }
            String realm = params.get("realm");
            String nonce = params.get("nonce");
            if (realm == null || nonce == null) {
                failRegistration("Challenge is missing realm or nonce");
                return;
            }
            String user = config.authUser.isEmpty() ? config.user : config.authUser;
            String uri = "sip:" + config.server;
            String cnonce = hex(8);
            String nc = "00000001";
            StringBuilder value = new StringBuilder("Digest username=\"").append(user)
                .append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(uri)
                .append("\", response=\"")
                .append(SipDigest.response(user, realm, config.password, "REGISTER", uri, nonce, qop, nc, cnonce))
                .append("\", algorithm=MD5");
            if (qop != null) value.append(", qop=").append(qop).append(", nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
            if (params.get("opaque") != null) value.append(", opaque=\"").append(params.get("opaque")).append('"');
            sendRegister(code == 401 ? "Authorization" : "Proxy-Authorization", value.toString());
            return;
        }
        failRegistration("Registration failed: " + code + " " + message.reason());
    }

    private int registrationExpires(SipMessage message) {
        String header = message.header("expires");
        if (header != null) {
            try { return Integer.parseInt(header.trim()); } catch (NumberFormatException ignored) { }
        }
        String contact = message.header("contact");
        if (contact != null) {
            int at = contact.toLowerCase().indexOf("expires=");
            if (at >= 0) {
                StringBuilder digits = new StringBuilder();
                for (int i = at + 8; i < contact.length() && Character.isDigit(contact.charAt(i)); i++) digits.append(contact.charAt(i));
                if (digits.length() > 0) return Integer.parseInt(digits.toString());
            }
        }
        return config.expires;
    }

    private void failRegistration(String detail) {
        nextRegisterAt = System.currentTimeMillis() + RETRY_MS;
        report(false, detail);
    }

    private void report(boolean ok, String detail) {
        boolean changed;
        synchronized (lock) {
            changed = ok != registered || !detail.equals(lastDetail);
            registered = ok;
            lastDetail = detail;
        }
        if (changed) {
            try {
                listener.onRegistration(ok, detail);
            } catch (RuntimeException ignored) {
                // The host may already be revoked while the plugin shuts down.
            }
        }
    }

    // ---- incoming requests ----

    private void handleRequest(SipMessage message, InetSocketAddress from) {
        for (String required : new String[] {"via", "from", "to", "call-id", "cseq"}) {
            if (message.header(required) == null) return;
        }
        String method = message.method();
        String callId = message.header("call-id");
        String ringingCaller = null;
        String inviteNote = null;
        boolean ended = false;
        synchronized (lock) {
            if ("INVITE".equals(method)) {
                if (call != null && callId.equals(call.invite.header("call-id"))) {
                    sendQuiet(call.ringing, from); // retransmitted INVITE
                } else if (callId.equals(lastFinalCallId) && lastFinalResponse != null) {
                    sendQuiet(lastFinalResponse, lastFinalTarget);
                } else if (dnd || call != null) {
                    byte[] busy = response(message, 486, "Busy Here", hex(4));
                    sendQuiet(busy, from);
                    finish(callId, busy, from);
                    inviteNote = SipMessage.caller(message.header("from")) + (dnd ? ": rejected (Do Not Disturb)" : ": rejected (busy)");
                } else {
                    String toTag = hex(4);
                    sendQuiet(response(message, 100, "Trying", null), from);
                    byte[] ringing = response(message, 180, "Ringing", toTag);
                    sendQuiet(ringing, from);
                    SipAudio.Offer offer=null;
                    try { offer=SipAudio.parseOffer(message.body); } catch(Exception ignored) { }
                    call = new Call(message, toTag, from, ringing, offer);
                    ringingCaller = SipMessage.caller(message.header("from"));
                    inviteNote = ringingCaller + ": klingelt";
                }
            } else if ("CANCEL".equals(method)) {
                if (call != null && !call.accepted && callId.equals(call.invite.header("call-id"))) {
                    sendQuiet(response(message, 200, "OK", call.toTag), from);
                    byte[] terminated = response(call.invite, 487, "Request Terminated", call.toTag);
                    sendQuiet(terminated, call.source);
                    finish(callId, terminated, call.source);
                    ended = true;
                } else {
                    sendQuiet(response(message, 481, "Call/Transaction Does Not Exist", hex(4)), from);
                }
            } else if ("ACK".equals(method)) {
                if(call!=null && callId.equals(call.invite.header("call-id")) && call.accepted && call.audio!=null && !call.mediaStarted) {
                    call.mediaStarted=true;
                    try { call.audio.start(); listener.log("RTP audio active (G.711, 8 kHz)"); }
                    catch(RuntimeException error) { listener.log(error.getMessage()); }
                }
            } else if ("OPTIONS".equals(method)) {
                sendQuiet(response(message, 200, "OK", hex(4)), from);
            } else if ("BYE".equals(method)) {
                if(call!=null && callId.equals(call.invite.header("call-id")) && call.accepted) {
                    byte[] ok=response(message,200,"OK",call.toTag); sendQuiet(ok,from);
                    byte[] accepted=call.ringing;
                    if(call.audio!=null) call.audio.close(); finish(callId,accepted,from); ended=true;
                } else sendQuiet(response(message, 481, "Call/Transaction Does Not Exist", hex(4)), from);
            } else {
                sendQuiet(response(message, 501, "Not Implemented", hex(4)), from);
            }
        }
        if (inviteNote != null) {
            try { listener.onInvite(inviteNote); } catch (RuntimeException ignored) { }
        }
        if (ringingCaller != null) {
            try { listener.onIncomingCall(ringingCaller); } catch (RuntimeException ignored) { }
        }
        if (ended) notifyEnded("Remote party ended the call");
    }

    /** Remembers the final response so retransmitted INVITEs get the same answer. Call with lock held. */
    private void finish(String callId, byte[] response, InetSocketAddress target) {
        if(call!=null && callId.equals(call.invite.header("call-id"))) {
            if(call.audio!=null) call.audio.close();
            call = null;
        }
        lastFinalCallId = callId;
        lastFinalResponse = response;
        lastFinalTarget = target;
    }

    private void notifyEnded(String reason) {
        try { listener.onCallEnded(reason); } catch (RuntimeException ignored) { }
    }

    // ---- message building ----

    private byte[] response(SipMessage request, int code, String reason, String toTag) {
        return responseWithBody(request, code, reason, toTag, "");
    }

    private byte[] responseWithBody(SipMessage request, int code, String reason, String toTag, String body) {
        StringBuilder sb = new StringBuilder("SIP/2.0 ").append(code).append(' ').append(reason).append("\r\n");
        for (String via : request.all("via")) sb.append("Via: ").append(via).append("\r\n");
        sb.append("From: ").append(request.header("from")).append("\r\n");
        String to = request.header("to");
        if (toTag != null && !to.contains("tag=")) to += ";tag=" + toTag;
        sb.append("To: ").append(to).append("\r\n")
          .append("Call-ID: ").append(request.header("call-id")).append("\r\n")
          .append("CSeq: ").append(request.header("cseq")).append("\r\n")
          .append("Contact: <sip:").append(config.user).append('@').append(localIp).append(':').append(config.localPort).append(">\r\n")
          .append("Allow: ").append(ALLOW).append("\r\n")
          .append("User-Agent: KS-SIP-Plugin/0.1.0\r\n");
        if (!body.isEmpty()) sb.append("Content-Type: application/sdp\r\n");
        sb.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n\r\n").append(body);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void send(byte[] data, InetSocketAddress target) throws IOException {
        DatagramSocket current = socket;
        if (current == null) throw new IOException("Socket geschlossen");
        current.send(new DatagramPacket(data, data.length, target));
    }

    private void sendQuiet(byte[] data, InetSocketAddress target) {
        try {
            send(data, target);
        } catch (IOException error) {
            listener.log("Send failed: " + error.getMessage());
        }
    }

    private String branch() { return "z9hG4bK" + hex(6); }

    private String hex(int bytes) {
        byte[] data = new byte[bytes];
        random.nextBytes(data);
        StringBuilder out = new StringBuilder();
        for (byte b : data) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
