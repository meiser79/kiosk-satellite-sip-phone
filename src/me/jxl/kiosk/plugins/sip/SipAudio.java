// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.sip;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Random;

/** Basic RTP audio for G.711 PCMU/PCMA at 8 kHz. Android audio APIs are resolved at runtime. */
final class SipAudio {
    static final class Offer {
        final InetAddress address; final int port, payload;
        Offer(InetAddress address, int port, int payload) { this.address=address; this.port=port; this.payload=payload; }
    }
    private final DatagramSocket rtp;
    private final Offer remote;
    private final InetAddress local;
    private volatile boolean running;
    private Thread tx, rx;
    private Object recorder, track, audioManager;
    private int oldMode; private boolean oldSpeaker;
    private final Random random = new Random();
    private int sequence = random.nextInt(65536), timestamp = random.nextInt();
    private final int ssrc = random.nextInt();

    static Offer parseOffer(String sdp) throws Exception {
        if (sdp == null || sdp.isEmpty()) throw new IllegalArgumentException("INVITE has no SDP audio offer");
        String ip = null; int port = -1, payload = -1; boolean audio = false;
        for (String raw : sdp.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("c=IN IP4 ")) ip = line.substring(9).trim();
            else if (line.startsWith("m=audio ")) {
                String[] parts = line.substring(8).trim().split(" +");
                port = Integer.parseInt(parts[0]); audio = true;
                for (int i=2; i<parts.length; i++) if ("0".equals(parts[i]) || "8".equals(parts[i])) { payload=Integer.parseInt(parts[i]); break; }
            } else if (line.startsWith("m=") && audio) break;
        }
        if (!audio || port < 1 || port > 65535 || payload < 0) throw new IllegalArgumentException("INVITE has no common G.711 audio codec (PCMU/PCMA)");
        if (ip == null) throw new IllegalArgumentException("SDP has no IPv4 media address");
        return new Offer(InetAddress.getByName(ip), port, payload);
    }

    SipAudio(InetAddress local, Offer remote) throws Exception {
        this.local=local; this.remote=remote;
        rtp=new DatagramSocket(new InetSocketAddress(local,0)); rtp.setSoTimeout(200);
    }
    int localPort() { return rtp.getLocalPort(); }
    String sdp() {
        int pt=remote.payload;
        return "v=0\r\no=- " + Integer.toUnsignedString(ssrc) + " 1 IN IP4 " + local.getHostAddress()+"\r\ns=Kiosk Satellite SIP Phone\r\nc=IN IP4 "+local.getHostAddress()+"\r\nt=0 0\r\nm=audio "+localPort()+" RTP/AVP "+pt+"\r\na=rtpmap:"+pt+" "+(pt==0?"PCMU":"PCMA")+"/8000\r\na=sendrecv\r\n";
    }

    void start() {
        running=true;
        try { openAndroidAudio(); } catch (Exception e) { close(); throw new IllegalStateException("Could not start Android audio: " + e.getMessage(), e); }
        tx=new Thread(this::sendLoop,"sip-rtp-tx"); rx=new Thread(this::receiveLoop,"sip-rtp-rx"); tx.setDaemon(true);rx.setDaemon(true);tx.start();rx.start();
    }
    void close() {
        running=false;
        try { rtp.close(); } catch(Exception ignored) { }
        try { if(recorder!=null) recorder.getClass().getMethod("stop").invoke(recorder); } catch(Exception ignored) { }
        try { if(track!=null) track.getClass().getMethod("stop").invoke(track); } catch(Exception ignored) { }
        try { if(audioManager!=null) { audioManager.getClass().getMethod("setSpeakerphoneOn",boolean.class).invoke(audioManager,oldSpeaker); audioManager.getClass().getMethod("setMode",int.class).invoke(audioManager,oldMode); } } catch(Exception ignored) { }
        release(recorder); release(track); recorder=null;track=null;
    }
    private void openAndroidAudio() throws Exception {
        Class<?> thread=Class.forName("android.app.ActivityThread"); Object app=thread.getMethod("currentApplication").invoke(null);
        if(app!=null) { audioManager=app.getClass().getMethod("getSystemService",String.class).invoke(app,"audio");
            if(audioManager!=null) { oldMode=(Integer)audioManager.getClass().getMethod("getMode").invoke(audioManager); oldSpeaker=(Boolean)audioManager.getClass().getMethod("isSpeakerphoneOn").invoke(audioManager); audioManager.getClass().getMethod("setMode",int.class).invoke(audioManager,3); audioManager.getClass().getMethod("setSpeakerphoneOn",boolean.class).invoke(audioManager,true); } }
        Class<?> ar=Class.forName("android.media.AudioRecord"), at=Class.forName("android.media.AudioTrack");
        int minRec=(Integer)ar.getMethod("getMinBufferSize",int.class,int.class,int.class).invoke(null,8000,16,2);
        int minTrack=(Integer)at.getMethod("getMinBufferSize",int.class,int.class,int.class).invoke(null,8000,4,2);
        recorder=ar.getConstructor(int.class,int.class,int.class,int.class,int.class).newInstance(1,8000,16,2,Math.max(minRec,640));
        track=at.getConstructor(int.class,int.class,int.class,int.class,int.class,int.class).newInstance(0,8000,4,2,Math.max(minTrack,640),1);
        ar.getMethod("startRecording").invoke(recorder); at.getMethod("play").invoke(track);
    }
    private void sendLoop() {
        byte[] pcm=new byte[320]; byte[] packet=new byte[172];
        try { Method read=recorder.getClass().getMethod("read",byte[].class,int.class,int.class);
            while(running) { int n=(Integer)read.invoke(recorder,pcm,0,pcm.length); if(n<2) continue; int samples=Math.min(n/2,160);
                packet[0]=(byte)0x80;packet[1]=(byte)remote.payload; packet[2]=(byte)(sequence>>>8);packet[3]=(byte)sequence; sequence=(sequence+1)&65535;
                packet[4]=(byte)(timestamp>>>24);packet[5]=(byte)(timestamp>>>16);packet[6]=(byte)(timestamp>>>8);packet[7]=(byte)timestamp; timestamp+=samples;
                packet[8]=(byte)(ssrc>>>24);packet[9]=(byte)(ssrc>>>16);packet[10]=(byte)(ssrc>>>8);packet[11]=(byte)ssrc;
                for(int i=0;i<samples;i++){ short sample=(short)((pcm[i*2]&255)|((pcm[i*2+1]&255)<<8));packet[12+i]=remote.payload==0?linearToMu(sample):linearToA(sample); }
                rtp.send(new DatagramPacket(packet,12+samples,remote.address,remote.port));
                long sleep=20-(samples*1000L/8000L); if(sleep>0) Thread.sleep(sleep);
            }
        } catch(Exception ignored) { }
    }
    private void receiveLoop() {
        byte[] buf=new byte[2048];
        try { Method write=track.getClass().getMethod("write",byte[].class,int.class,int.class);
            while(running) { DatagramPacket p=new DatagramPacket(buf,buf.length); try{rtp.receive(p);}catch(SocketTimeoutException e){continue;}
                if(p.getLength()<13 || (buf[0]&0xc0)!=0x80) continue; int header=12+((buf[0]&0x0f)*4); if((buf[0]&0x10)!=0 && p.getLength()>=header+4) header+=4; if(p.getLength()<=header)continue;
                int pt=buf[1]&0x7f; if(pt!=0 && pt!=8)continue; byte[] pcm=new byte[(p.getLength()-header)*2];
                for(int i=header;i<p.getLength();i++){short x=pt==0?muToLinear(buf[i]):aToLinear(buf[i]);int j=(i-header)*2;pcm[j]=(byte)x;pcm[j+1]=(byte)(x>>>8);}
                write.invoke(track,pcm,0,pcm.length);
            }
        } catch(Exception ignored) { }
    }
    private static void release(Object o){try{if(o!=null)o.getClass().getMethod("release").invoke(o);}catch(Exception ignored){}}
    static byte linearToMu(short sample){int x=sample;int sign=(x>>8)&0x80;if(sign!=0)x=-x;if(x>32635)x=32635;x+=0x84;int exponent=7;for(int mask=0x4000;(x&mask)==0&&exponent>0;mask>>=1)exponent--;int mantissa=(x>>(exponent+3))&0x0f;return(byte)~(sign|(exponent<<4)|mantissa);}
    static short muToLinear(byte value){int u=(~value)&255;int t=((u&15)<<3)+0x84;t<<=(u&0x70)>>4;return(short)(((u&0x80)!=0)?(0x84-t):(t-0x84));}
    static byte linearToA(short sample){int x=sample;int mask;if(x>=0)mask=0xd5;else{mask=0x55;x=-x-1;}if(x>32767)x=32767;int seg=0;if(x>=256){int v=x>>8;while(v>1&&seg<7){v>>=1;seg++;}}int aval=seg<<4;if(seg<2)aval|=(x>>4)&15;else aval|=(x>>(seg+3))&15;return(byte)(aval^mask);}
    static short aToLinear(byte value){int a=(value&255)^0x55;int t=(a&15)<<4;int seg=(a&0x70)>>4;if(seg==0)t+=8;else if(seg==1)t+=0x108;else{t+=0x108;t<<=seg-1;}return(short)(((a&0x80)!=0)?t:-t);}
}
