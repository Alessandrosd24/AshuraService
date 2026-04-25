package com.ashura;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AshuraService extends Service {

    private static final String TAG = "Ashura";
    private static final String CHANNEL_ID = "ashura_channel";
    private static final int NOTIFICATION_ID = 1;

    // Laptop server via Tailscale
    private static final String SERVER_URL = "http://100.80.213.94:8765";
    private static final int SAMPLE_RATE = 16000;
    private static final int AUDIO_CHUNK_MS = 2000; // 2s chunks

    public static boolean isRunning = false;

    private MediaRecorder recorder;
    private ExecutorService executor;
    private boolean listening = false;
    private AudioTrack audioTrack;
    private String currentPlaybackFile;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isRunning = true;
        startForeground(NOTIFICATION_ID, createNotification());
        startListening();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        listening = false;
        if (recorder != null) {
            try { recorder.release(); } catch (Exception ignored) {}
        }
        if (audioTrack != null) {
            try { audioTrack.stop(); audioTrack.release(); } catch (Exception ignored) {}
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ─── NOTIFICATION ────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Ashura Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Ashura background voice service");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ashura")
            .setContentText("Escuchando...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID,
                new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Ashura")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build());
        }
    }

    // ─── AUDIO CAPTURE ───────────────────────────────────────

    private void startListening() {
        listening = true;
        updateNotification("Escuchando...");
        Log.d(TAG, "Ciclo de escucha iniciado");

        executor.execute(() -> {
            while (listening && isRunning) {
                try {
                    File audioFile = captureAudioChunk();
                    if (audioFile != null && audioFile.exists()) {
                        uploadAudio(audioFile);
                        audioFile.delete();
                    } else {
                        // Mic not available, retry
                        Thread.sleep(500);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error en ciclo: " + e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
        });
    }

    private File captureAudioChunk() throws Exception {
        // Use temp file for recording
        File outputFile = new File(getCacheDir(), "ashura_chunk.wav");
        if (outputFile.exists()) outputFile.delete();

        // Android 14+ uses MediaRecorder with better API
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioSamplingRate(SAMPLE_RATE);
        recorder.setAudioChannels(1);
        recorder.setOutputFile(outputFile.getAbsolutePath());

        try {
            recorder.prepare();
            recorder.start();
            Thread.sleep(AUDIO_CHUNK_MS);
            recorder.stop();
            recorder.release();
            recorder = null;

            if (outputFile.exists() && outputFile.length() > 100) {
                Log.d(TAG, "Audio capturado: " + outputFile.length() + " bytes");
                return outputFile;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error capturando audio: " + e.getMessage());
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        return null;
    }

    // ─── NETWORK: Upload to laptop ──────────────────────────

    private void uploadAudio(File audioFile) {
        try {
            URL url = new URL(SERVER_URL + "/audio");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "audio/aac");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            // Write file
            FileInputStream fis = new FileInputStream(audioFile);
            OutputStream os = conn.getOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            fis.close();
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                // Read response - could be TTS audio or command
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] respBuf = new byte[8192];
                int read;
                while ((read = is.read(respBuf)) != -1) {
                    baos.write(respBuf, 0, read);
                }
                is.close();

                byte[] response = baos.toByteArray();
                if (response.length > 0) {
                    // Check if it's an MP3 (starts with ID3 or fffe)
                    if (response.length > 4 &&
                        (response[0] == 'I' || response[0] == (byte)0xff)) {
                        // It's audio - play it
                        playAudio(response);
                    } else {
                        // It's text - show in notification
                        String text = new String(response, "UTF-8").trim();
                        updateNotification("Ashura: " + text);
                    }
                }
            } else {
                Log.d(TAG, "Server responded: " + responseCode);
            }
            conn.disconnect();

        } catch (Exception e) {
            Log.e(TAG, "Error subiendo audio: " + e.getMessage());
        }
    }

    // ─── AUDIO PLAYBACK (native, no apps needed) ────────────

    private void playAudio(byte[] mp3Data) {
        try {
            updateNotification("▶️ Reproduciendo...");

            // Save to temp file for MediaPlayer
            File mp3File = new File(getCacheDir(), "ashura_response.mp3");
            FileOutputStream fos = new FileOutputStream(mp3File);
            fos.write(mp3Data);
            fos.close();

            // Use Android's native MediaPlayer
            android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
            player.setDataSource(mp3File.getAbsolutePath());
            player.prepare();
            player.start();
            player.setOnCompletionListener(mp -> {
                mp.release();
                mp3File.delete();
                if (listening) {
                    updateNotification("Escuchando...");
                } else {
                    updateNotification("Ashura detenido");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error reproduciendo audio: " + e.getMessage());
            updateNotification("Error reproduciendo");
        }
    }
}
