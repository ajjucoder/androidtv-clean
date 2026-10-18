package com.parents.tvhome;

import android.content.Context;
import android.media.AudioManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A remote for any phone on the home Wi-Fi: http://<tv-ip>:8765.
 * Volume and mute go to the TV, which passes them to the JBL bar over HDMI-CEC,
 * so they work whatever app is on screen. It can also open the big tiles and the TV box.
 */
class RemoteServer {
    static final int PORT = 8765;

    final MainActivity home;
    final AudioManager audio;
    volatile ServerSocket server;
    /** Android reports 0 while muted; the dial keeps showing the level it will come back to. */
    volatile int lastVol = -1;

    RemoteServer(MainActivity home) {
        this.home = home;
        this.audio = (AudioManager) home.getSystemService(Context.AUDIO_SERVICE);
    }

    void start() {
        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(PORT)) {
                server = ss;
                while (!ss.isClosed()) {
                    Socket s = ss.accept();
                    new Thread(() -> handle(s)).start();
                }
            } catch (Exception ignored) {
                // Port busy or closed on shutdown: the remote is just unavailable.
            }
        }, "remote");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) { }
    }

    void handle(Socket s) {
        try (Socket sock = s) {
            // Home network only: anything not from a private address is dropped unanswered.
            java.net.InetAddress from = sock.getInetAddress();
            if (!(from.isSiteLocalAddress() || from.isLoopbackAddress() || from.isLinkLocalAddress())) return;
            sock.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], path = parts[1];
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip headers */ }

            OutputStream out = sock.getOutputStream();
            if (method.equals("GET") && (path.equals("/") || path.startsWith("/?"))) {
                send(out, 200, "text/html; charset=utf-8", asset("remote.html"));
            } else if (method.equals("GET") && path.equals("/medium.ttf")) {
                send(out, 200, "font/ttf", asset("fonts/Geist-Medium.ttf"));
            } else if (method.equals("GET") && path.equals("/semibold.ttf")) {
                send(out, 200, "font/ttf", asset("fonts/Geist-SemiBold.ttf"));
            } else if (method.equals("GET") && path.equals("/state")) {
                json(out);
            } else if (method.equals("POST") && path.startsWith("/")) {
                act(path.substring(1));
                // Give the bar a moment to report its new level back over CEC.
                Thread.sleep(450);
                json(out);
            } else {
                send(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    void act(String what) {
        int m = AudioManager.STREAM_MUSIC, ui = AudioManager.FLAG_SHOW_UI;
        switch (what) {
            case "up": audio.adjustStreamVolume(m, AudioManager.ADJUST_RAISE, ui); break;
            case "down": audio.adjustStreamVolume(m, AudioManager.ADJUST_LOWER, ui); break;
            case "mute": audio.adjustStreamVolume(m, AudioManager.ADJUST_TOGGLE_MUTE, ui); break;
            default:
                if (what.startsWith("open/")) home.remoteOpen(what.substring(5));
        }
    }

    void json(OutputStream out) throws Exception {
        int m = AudioManager.STREAM_MUSIC;
        JSONObject o = new JSONObject();
        boolean muted = audio.isStreamMute(m);
        int vol = audio.getStreamVolume(m);
        if (!muted || lastVol < 0) lastVol = vol;
        o.put("vol", muted ? lastVol : vol);
        o.put("max", audio.getStreamMaxVolume(m));
        o.put("muted", muted);
        JSONArray apps = new JSONArray();
        for (Map.Entry<String, String> e : home.remoteTargets.entrySet())
            apps.put(new JSONObject().put("k", e.getKey()).put("l", e.getValue()));
        o.put("apps", apps);
        send(out, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    byte[] asset(String name) throws Exception {
        try (InputStream in = home.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    static void send(OutputStream out, int code, String type, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
