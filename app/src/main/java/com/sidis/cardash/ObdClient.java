package com.sidis.cardash;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

public class ObdClient {
    public interface Listener {
        void onState(VehicleState state);
        void onStatus(String message);
        void onDiagnostic(String line);
    }

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final BluetoothDevice device;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private BluetoothSocket socket;
    private final VehicleState state = new VehicleState();

    public ObdClient(BluetoothDevice device, Listener listener) {
        this.device = device;
        this.listener = listener;
        state.source = device.getName() != null ? device.getName() : "OBD";
    }

    public void start() {
        running = true;
        new Thread(this::runLoop, "OBD-Thread").start();
    }

    public void stop() {
        running = false;
        closeSocket();
    }

    private void runLoop() {
        try {
            postStatus("Connecting to " + state.source + "…");
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
            } catch (Exception first) {
                closeSocket();
                socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
            }
            state.connected = true;
            postStatus("OBD connected");
            postDiagnostic("CONNECTED  " + safeDeviceName() + "  " + safeDeviceAddress());

            send("ATZ", 1400);
            send("ATE0", 400);
            send("ATL0", 300);
            send("ATS0", 300);
            send("ATH0", 300);
            send("ATSP0", 700);
            String protocol = send("ATDP", 700);
            postDiagnostic("PROTOCOL  " + (protocol.isEmpty() ? "unknown" : protocol));

            long slowTick = 0;
            while (running && socket.isConnected()) {
                Integer speed = parseOneBytePid(send("010D", 500), "410D");
                if (speed != null) state.speedKmh = speed;

                int[] rpmBytes = parseTwoBytePid(send("010C", 500), "410C");
                if (rpmBytes != null) state.rpm = ((rpmBytes[0] * 256) + rpmBytes[1]) / 4;

                Integer coolant = parseOneBytePid(send("0105", 500), "4105");
                if (coolant != null) state.coolantC = coolant - 40;

                if ((slowTick++ % 4) == 0) {
                    int[] v = parseTwoBytePid(send("0142", 500), "4142");
                    if (v != null) state.voltage = ((v[0] * 256) + v[1]) / 1000.0;

                    Integer fuel = parseOneBytePid(send("012F", 500), "412F");
                    if (fuel != null) {
                        state.fuelPercent = fuel * 100.0 / 255.0;
                        state.rangeKm = estimateRange(state.fuelPercent, 50.0, 7.5);
                    }

                    int[] fuelRate = parseTwoBytePid(send("015E", 500), "415E");
                    if (fuelRate != null && state.speedKmh > 5) {
                        double litersPerHour = ((fuelRate[0] * 256) + fuelRate[1]) * 0.05;
                        state.consumptionL100 = litersPerHour / state.speedKmh * 100.0;
                    }
                }

                postState();
                Thread.sleep(120);
            }
        } catch (SecurityException e) {
            postStatus("Bluetooth permission missing");
        } catch (Exception e) {
            postStatus("OBD error: " + e.getMessage());
        } finally {
            state.connected = false;
            postState();
            closeSocket();
        }
    }

    private String send(String command, long timeoutMs) throws Exception {
        postDiagnostic("TX  >  " + command);
        OutputStream out = socket.getOutputStream();
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        out.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (running && System.currentTimeMillis() < deadline) {
            while (in.available() > 0) {
                int b = in.read();
                if (b < 0) break;
                char c = (char) b;
                if (c == '>') {
                    String result = normalize(sb.toString());
                    postDiagnostic("RX  <  " + (result.isEmpty() ? "<empty>" : result));
                    return result;
                }
                sb.append(c);
            }
            Thread.sleep(12);
        }
        String result = normalize(sb.toString());
        postDiagnostic("RX  <  " + (result.isEmpty() ? "<timeout/empty>" : result));
        return result;
    }

    private String normalize(String raw) {
        return raw.toUpperCase(Locale.US)
                .replace("SEARCHING...", "")
                .replace(" ", "")
                .replace("\r", "")
                .replace("\n", "")
                .replace(">',", "");
    }

    private Integer parseOneBytePid(String response, String header) {
        int idx = response.indexOf(header);
        if (idx < 0 || response.length() < idx + header.length() + 2) return null;
        try { return Integer.parseInt(response.substring(idx + header.length(), idx + header.length() + 2), 16); }
        catch (Exception ignored) { return null; }
    }

    private int[] parseTwoBytePid(String response, String header) {
        int idx = response.indexOf(header);
        if (idx < 0 || response.length() < idx + header.length() + 4) return null;
        try {
            int a = Integer.parseInt(response.substring(idx + header.length(), idx + header.length() + 2), 16);
            int b = Integer.parseInt(response.substring(idx + header.length() + 2, idx + header.length() + 4), 16);
            return new int[]{a, b};
        } catch (Exception ignored) { return null; }
    }

    private int estimateRange(double fuelPercent, double tankLiters, double avgL100) {
        return (int) Math.max(0, Math.round((fuelPercent / 100.0 * tankLiters) / avgL100 * 100.0));
    }

    private void postState() { main.post(() -> listener.onState(state)); }
    private void postStatus(String s) {
        postDiagnostic("STATUS  " + s);
        main.post(() -> listener.onStatus(s));
    }
    private void postDiagnostic(String s) { main.post(() -> listener.onDiagnostic(s)); }

    private String safeDeviceName() {
        try { return device.getName() != null ? device.getName() : "OBD"; }
        catch (SecurityException ignored) { return "OBD"; }
    }

    private String safeDeviceAddress() {
        try { return device.getAddress(); }
        catch (SecurityException ignored) { return "address unavailable"; }
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}
