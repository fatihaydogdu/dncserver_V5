package com.powerkraft.cnc;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.UUID;
import java.util.regex.Pattern;

/** Cihaz ile HC-06 (Bluetooth SPP) uzerinden konusur. Protokol: PING / LIST / GET / PUT / DEL / EXIT */
public class BtLink {

    public static class DeviceEx extends Exception {
        public DeviceEx(String m) { super(m); }
    }

    public interface Progress { void onProgress(long done, long total); }

    public static class Entry {
        public String name;
        public long size;
    }

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final Pattern NAME = Pattern.compile("^[A-Z0-9_\\-]{1,8}(\\.[A-Z0-9_\\-]{1,3})?$");
    private static final int CHUNK = 64;
    private static final String TIMEOUT =
            "Cihazdan yanıt gelmedi. Cihaz ekranında 'PC BAGLANTISI' yazdığını ve HC-06 baud ayarını kontrol edin.";

    private BluetoothSocket sock;
    private InputStream in;
    private OutputStream out;

    public static String normalize(String n) {
        if (n == null) return null;
        String u = n.trim().toUpperCase();
        return NAME.matcher(u).matches() ? u : null;
    }

    public boolean isOpen() { return sock != null; }

    @SuppressLint("MissingPermission")
    public void open(BluetoothDevice d) throws IOException {
        close();
        BluetoothSocket s = d.createRfcommSocketToServiceRecord(SPP);
        try {
            s.connect();
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignore) { }
            throw new IOException("Bağlanılamadı. HC-06 takılı ve cihaz açık mı? (" + e.getMessage() + ")");
        }
        sock = s;
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    public void close() {
        try { if (sock != null) sock.close(); } catch (IOException ignore) { }
        sock = null; in = null; out = null;
    }

    // ---------------------------------------------------------------
    private void drain() throws IOException {
        while (in.available() > 0) in.read();
    }

    private void send(String cmd) throws IOException {
        drain();
        out.write((cmd + "\n").getBytes("US-ASCII"));
        out.flush();
    }

    private String readLine(int timeoutMs) throws IOException, DeviceEx {
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (in.available() == 0) {
                sleep(2);
                continue;
            }
            int c = in.read();
            if (c < 0) throw new IOException("Bağlantı koptu.");
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
        }
        throw new DeviceEx(TIMEOUT);
    }

    private int readSome(byte[] b, int off, int len, int idleMs) throws IOException, DeviceEx {
        long end = System.currentTimeMillis() + idleMs;
        while (System.currentTimeMillis() < end) {
            int a = in.available();
            if (a > 0) return in.read(b, off, Math.min(a, len));
            sleep(2);
        }
        throw new DeviceEx(TIMEOUT);
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) { }
    }

    private static String translate(String r) {
        if (r == null) return "Bilinmeyen hata.";
        if (r.startsWith("ERR YOK")) return "Dosya kartta bulunamadı.";
        if (r.startsWith("ERR DOLU")) return "SD kart dolu.";
        if (r.startsWith("ERR YAZMA")) return "Karta yazılamadı (SD kart hatası veya işlem cihazdan iptal edildi).";
        if (r.startsWith("ERR AD")) return "Geçersiz dosya adı.";
        if (r.startsWith("ERR SD")) return "SD kart hatası.";
        if (r.startsWith("ERR KOMUT")) return "Cihaz bu komutu tanımadı (cihaz yazılımı eski olabilir).";
        return "Cihazdan beklenmeyen yanıt: " + r;
    }

    // ---------------------------------------------------------------
    public String ping() throws IOException, DeviceEx {
        send("PING");
        String r = readLine(3000);
        if (!r.startsWith("POWERKRAFT")) throw new DeviceEx("Beklenmeyen yanıt: " + r);
        return r.substring(10).trim();
    }

    public ArrayList<Entry> list() throws IOException, DeviceEx {
        send("LIST");
        ArrayList<Entry> res = new ArrayList<Entry>();
        while (true) {
            String line = readLine(6000);
            if (line.equals("END")) return res;
            if (line.startsWith("ERR")) throw new DeviceEx(translate(line));
            int sp = line.lastIndexOf(' ');
            if (sp <= 0) throw new DeviceEx("Beklenmeyen yanıt: " + line);
            Entry e = new Entry();
            e.name = line.substring(0, sp);
            try {
                e.size = Long.parseLong(line.substring(sp + 1).trim());
            } catch (NumberFormatException ex) {
                throw new DeviceEx("Beklenmeyen yanıt: " + line);
            }
            res.add(e);
        }
    }

    public byte[] get(String name, Progress p) throws IOException, DeviceEx {
        String n = normalize(name);
        if (n == null) throw new DeviceEx("Geçersiz dosya adı: " + name);
        send("GET " + n);
        String r = readLine(6000);
        if (r.startsWith("ERR")) throw new DeviceEx(translate(r));
        if (!r.startsWith("OK ")) throw new DeviceEx("Beklenmeyen yanıt: " + r);
        long size = Long.parseLong(r.substring(3).trim());
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        long got = 0;
        while (got < size) {
            int want = (int) Math.min((long) buf.length, size - got);
            int k = readSome(buf, 0, want, 6000);
            bo.write(buf, 0, k);
            got += k;
            if (p != null) p.onProgress(got, size);
        }
        return bo.toByteArray();
    }

    public void put(String name, byte[] data, Progress p) throws IOException, DeviceEx {
        String n = normalize(name);
        if (n == null) throw new DeviceEx("Geçersiz dosya adı: " + name);
        int size = data.length;
        send("PUT " + n + " " + size);
        String r = readLine(30000);
        if (!r.equals("OK")) throw new DeviceEx(translate(r));
        int pos = 0;
        while (pos < size) {
            int len = Math.min(CHUNK, size - pos);
            out.write(data, pos, len);
            out.flush();
            pos += len;
            if (p != null) p.onProgress(pos, size);
            if (pos < size) {
                byte[] ack = new byte[1];
                readSome(ack, 0, 1, 10000);
                if (ack[0] != 'K') {
                    String rest = "";
                    try { rest = readLine(1000); } catch (DeviceEx ignore) { }
                    throw new DeviceEx(translate(((char) ack[0]) + rest));
                }
            }
        }
        String done = readLine(15000);
        if (!done.equals("DONE")) throw new DeviceEx(translate(done));
    }

    public void delete(String name) throws IOException, DeviceEx {
        String n = normalize(name);
        if (n == null) throw new DeviceEx("Geçersiz dosya adı: " + name);
        send("DEL " + n);
        String r = readLine(6000);
        if (!r.equals("OK")) throw new DeviceEx(translate(r));
    }

    public void exit() {
        try {
            if (out == null) return;
            send("EXIT");
            readLine(1500);
        } catch (Exception ignore) {
        }
    }
}
