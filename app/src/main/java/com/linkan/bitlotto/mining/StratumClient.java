package com.linkan.bitlotto.mining;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal Stratum v1 client (plain TCP) with automatic reconnect. */
public final class StratumClient {

    public interface Listener {
        void onStatus(String status);
        void onSubscribed(String extranonce1, int extranonce2Size);
        void onAuthorized(boolean ok, String error);
        void onDifficulty(double difficulty);
        void onJob(StratumJob job);
        void onShareResult(boolean accepted, String error);
        void onDisconnected(String reason);
    }

    private static final int ID_SUBSCRIBE = 1;
    private static final int ID_AUTHORIZE = 2;
    private static final int ID_SUGGEST = 3;

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final double suggestedDifficulty;
    private final Listener listener;

    private final AtomicInteger nextId = new AtomicInteger(100);
    private final Set<Integer> pendingSubmits = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private Thread thread;

    public StratumClient(String host, int port, String user, String password,
                         double suggestedDifficulty, Listener listener) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.suggestedDifficulty = suggestedDifficulty;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "stratum");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        closeSocket();
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        long backoff = 2000;
        while (running) {
            long started = System.currentTimeMillis();
            try {
                session();
            } catch (Exception e) {
                if (!running) break;
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                listener.onDisconnected(msg);
            } finally {
                closeSocket();
                pendingSubmits.clear();
            }
            if (!running) break;
            if (System.currentTimeMillis() - started > 120_000) backoff = 2000;
            listener.onStatus("Återansluter om " + (backoff / 1000) + " s…");
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 60_000);
        }
    }

    private void session() throws IOException, JSONException {
        listener.onStatus("Ansluter till " + host + ":" + port + "…");
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 15_000);
        s.setSoTimeout(300_000);
        s.setKeepAlive(true);
        s.setTcpNoDelay(true);
        socket = s;
        writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

        send(ID_SUBSCRIBE, "mining.subscribe", new JSONArray().put("BitLotto/1.0"));

        String line;
        while (running && (line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            handle(new JSONObject(line));
        }
        if (running) throw new IOException("Poolen stängde anslutningen");
    }

    private void handle(JSONObject msg) throws JSONException, IOException {
        String method = msg.isNull("method") ? null : msg.optString("method", null);
        if (method != null) {
            JSONArray params = msg.optJSONArray("params");
            switch (method) {
                case "mining.notify":
                    if (params != null) listener.onJob(parseJob(params));
                    break;
                case "mining.set_difficulty":
                    if (params != null && params.length() > 0) listener.onDifficulty(params.getDouble(0));
                    break;
                case "client.reconnect":
                    listener.onStatus("Poolen bad om återanslutning");
                    closeSocket();
                    break;
                default:
                    // mining.set_extranonce, client.show_message etc. are not needed here.
                    break;
            }
            return;
        }

        int id = msg.optInt("id", -1);
        String error = msg.isNull("error") ? null : String.valueOf(msg.opt("error"));
        if (id == ID_SUBSCRIBE) {
            JSONArray result = msg.optJSONArray("result");
            if (result == null || result.length() < 3) {
                throw new IOException("Ogiltigt subscribe-svar" + (error != null ? ": " + error : ""));
            }
            listener.onSubscribed(result.getString(1), result.getInt(2));
            send(ID_AUTHORIZE, "mining.authorize", new JSONArray().put(user).put(password));
            if (suggestedDifficulty > 0) {
                send(ID_SUGGEST, "mining.suggest_difficulty", new JSONArray().put(suggestedDifficulty));
            }
        } else if (id == ID_AUTHORIZE) {
            boolean ok = msg.optBoolean("result", false);
            listener.onAuthorized(ok, error);
            if (!ok) {
                running = false;
                closeSocket();
            }
        } else if (id == ID_SUGGEST) {
            // Optional extension; pools may ignore or reject it.
        } else if (pendingSubmits.remove(id)) {
            listener.onShareResult(msg.optBoolean("result", false), error);
        }
    }

    private static StratumJob parseJob(JSONArray p) throws JSONException {
        JSONArray branchJson = p.getJSONArray(4);
        List<String> branch = new ArrayList<>(branchJson.length());
        for (int i = 0; i < branchJson.length(); i++) branch.add(branchJson.getString(i));
        return new StratumJob(
                p.getString(0), p.getString(1), p.getString(2), p.getString(3), branch,
                p.getString(5), p.getString(6), p.getString(7), p.optBoolean(8, false));
    }

    public void submit(String jobId, String extranonce2, String ntime, String nonceHex) {
        int id = nextId.getAndIncrement();
        pendingSubmits.add(id);
        try {
            send(id, "mining.submit",
                    new JSONArray().put(user).put(jobId).put(extranonce2).put(ntime).put(nonceHex));
        } catch (Exception e) {
            pendingSubmits.remove(id);
            listener.onShareResult(false, "Kunde inte skicka: " + e.getMessage());
        }
    }

    private synchronized void send(int id, String method, JSONArray params) throws IOException, JSONException {
        BufferedWriter w = writer;
        if (w == null) throw new IOException("Inte ansluten");
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("method", method);
        o.put("params", params);
        w.write(o.toString());
        w.write('\n');
        w.flush();
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        writer = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
