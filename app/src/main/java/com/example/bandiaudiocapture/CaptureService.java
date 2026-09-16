package com.example.bandiaudiocapture;

import android.app.*;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CaptureService extends Service {

    public static final String ACTION_START = "BANDI_START";
    public static final String ACTION_STOP = "BANDI_STOP";

    private static final String CHANNEL = "capture_channel";

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int BITS = 16;
    private static final int STT_RATE = 16000;

    private AudioRecord recorder;
    private MediaProjection projection;

    private volatile boolean running = false;
    private volatile boolean finishing = false;

    private Thread captureThread;

    private File pcmFile;
    private FileOutputStream pcmOutput;

    private long totalBytes = 0;
    private long startTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager nm =
                (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL,
                        "Bandi Audio Recording",
                        NotificationManager.IMPORTANCE_LOW
                );

        nm.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId) {

        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            finishRecording();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        Notification notification =
                new Notification.Builder(this, CHANNEL)
                        .setContentTitle("반디 학습 녹음 중")
                        .setContentText("내부 오디오를 녹음하고 있습니다.")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(1, notification);
        }

        try {

            int resultCode =
                    intent.getIntExtra(
                            "resultCode",
                            Activity.RESULT_CANCELED
                    );

            Intent data =
                    intent.getParcelableExtra("data");

            if (data == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            MediaProjectionManager manager =
                    (MediaProjectionManager)
                            getSystemService(
                                    Context.MEDIA_PROJECTION_SERVICE
                            );

            projection =
                    manager.getMediaProjection(
                            resultCode,
                            data
                    );

            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .build();

            AudioFormat format =
                    new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build();

            int minBuffer =
                    AudioRecord.getMinBufferSize(
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_STEREO,
                            AudioFormat.ENCODING_PCM_16BIT
                    );

            recorder =
                    new AudioRecord.Builder()
                            .setAudioFormat(format)
                            .setBufferSizeInBytes(
                                    Math.max(minBuffer * 2, 65536)
                            )
                            .setAudioPlaybackCaptureConfig(config)
                            .build();

            pcmFile =
                    new File(
                            getCacheDir(),
                            "bandi_recording.pcm"
                    );

            pcmOutput =
                    new FileOutputStream(pcmFile);

            totalBytes = 0;
            startTime = System.currentTimeMillis();

            recorder.startRecording();

            running = true;
            finishing = false;

            getSharedPreferences("capture", MODE_PRIVATE)
                    .edit()
                    .putBoolean("active", true)
                    .putBoolean("transcribing", false)
                    .putBoolean("completed", false)
                    .putString("transcript", "")
                    .putString("stt_error", "")
                    .apply();

            captureThread =
                    new Thread(this::captureLoop);

            captureThread.start();

        } catch (Exception e) {

            getSharedPreferences("capture", MODE_PRIVATE)
                    .edit()
                    .putBoolean("active", false)
                    .putString(
                            "stt_error",
                            "녹음 시작 오류: " +
                                    e.getClass().getSimpleName()
                    )
                    .apply();

            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void captureLoop() {

        short[] samples = new short[4096];
        byte[] bytes = new byte[samples.length * 2];

        try {

            while (running && recorder != null) {

                int n =
                        recorder.read(
                                samples,
                                0,
                                samples.length
                        );

                if (n > 0) {

                    double sum = 0;

                    for (int i = 0; i < n; i++) {

                        short s = samples[i];

                        sum += (double) s * s;

                        bytes[i * 2] =
                                (byte) (s & 0xff);

                        bytes[i * 2 + 1] =
                                (byte) ((s >> 8) & 0xff);
                    }

                    int byteCount = n * 2;

                    pcmOutput.write(
                            bytes,
                            0,
                            byteCount
                    );

                    totalBytes += byteCount;

                    float rms =
                            (float) Math.sqrt(sum / n);

                    long seconds =
                            (System.currentTimeMillis()
                                    - startTime) / 1000;

                    getSharedPreferences(
                            "capture",
                            MODE_PRIVATE
                    ).edit()
                            .putFloat("rms", rms)
                            .putLong("bytes", totalBytes)
                            .putLong("seconds", seconds)
                            .apply();
                }
            }

        } catch (Exception ignored) {
        }
    }

    private synchronized void finishRecording() {

        if (finishing) return;
        finishing = true;

        new Thread(() -> {

            running = false;

            try {
                if (recorder != null) recorder.stop();
            } catch (Exception ignored) {}

            try {
                if (captureThread != null) captureThread.join(3000);
            } catch (Exception ignored) {}

            try {
                if (pcmOutput != null) {
                    pcmOutput.flush();
                    pcmOutput.close();
                }
            } catch (Exception ignored) {}

            try {
                if (recorder != null) recorder.release();
            } catch (Exception ignored) {}

            recorder = null;

            String savedPath;

            try {
                savedPath = createWav();
            } catch (Exception e) {
                savedPath =
                        "WAV 저장 실패: " +
                                e.getClass().getSimpleName();
            }

            long seconds =
                    startTime == 0
                            ? 0
                            : (System.currentTimeMillis()
                            - startTime) / 1000;

            getSharedPreferences("capture", MODE_PRIVATE)
                    .edit()
                    .putBoolean("active", false)
                    .putBoolean("transcribing", true)
                    .putBoolean("completed", false)
                    .putLong("seconds", seconds)
                    .putString("file", savedPath)
                    .apply();

            if (projection != null) {
                try {
                    projection.stop();
                } catch (Exception ignored) {}
                projection = null;
            }

            startVosk();

        }).start();
    }

    private void startVosk() {

        try {

            StorageService.unpack(
                    this,
                    "model-en-us",
                    "model-en-us",
                    model -> new Thread(() ->
                            transcribe(model)
                    ).start(),
                    exception -> {
                        finishWithError(
                                "영어 모델 로딩 실패: " +
                                        exception.getMessage()
                        );
                    }
            );

        } catch (Exception e) {
            finishWithError(
                    "영어 모델 시작 실패: " +
                            e.getClass().getSimpleName()
            );
        }
    }

    private void transcribe(Model model) {

        Recognizer recognizer = null;

        try {

            recognizer =
                    new Recognizer(
                            model,
                            (float) STT_RATE
                    );

            FileInputStream in =
                    new FileInputStream(pcmFile);

            byte[] stereoBytes =
                    new byte[16384];

            StringBuilder transcript =
                    new StringBuilder();

            long phase = 0;

            int read;

            while ((read = in.read(stereoBytes)) > 0) {

                int usable = read - (read % 4);

                ByteArrayOutputStream mono16 =
                        new ByteArrayOutputStream();

                for (int i = 0; i < usable; i += 4) {

                    short left =
                            (short) (
                                    (stereoBytes[i] & 0xff) |
                                    (stereoBytes[i + 1] << 8)
                            );

                    short right =
                            (short) (
                                    (stereoBytes[i + 2] & 0xff) |
                                    (stereoBytes[i + 3] << 8)
                            );

                    short mono =
                            (short) (
                                    ((int) left + (int) right) / 2
                            );

                    phase += STT_RATE;

                    if (phase >= SAMPLE_RATE) {

                        phase -= SAMPLE_RATE;

                        mono16.write(
                                mono & 0xff
                        );

                        mono16.write(
                                (mono >> 8) & 0xff
                        );
                    }
                }

                byte[] speech =
                        mono16.toByteArray();

                if (speech.length > 0) {

                    if (recognizer.acceptWaveForm(
                            speech,
                            speech.length)) {

                        appendText(
                                transcript,
                                recognizer.getResult()
                        );
                    }
                }
            }

            in.close();

            appendText(
                    transcript,
                    recognizer.getFinalResult()
            );

            String finalText =
                    transcript.toString()
                            .trim()
                            .replaceAll("\\s+", " ");

            String txtPath = "";

            if (!finalText.isEmpty()) {
                txtPath = saveTranscript(finalText);
            }

            getSharedPreferences("capture", MODE_PRIVATE)
                    .edit()
                    .putBoolean("transcribing", false)
                    .putBoolean("completed", true)
                    .putString("transcript", finalText)
                    .putString("transcript_file", txtPath)
                    .putString("stt_error", "")
                    .apply();

        } catch (Exception e) {

            finishWithError(
                    "STT 변환 오류: " +
                            e.getClass().getSimpleName() +
                            " / " +
                            String.valueOf(e.getMessage())
            );

        } finally {

            if (recognizer != null) {
                try {
                    recognizer.close();
                } catch (Exception ignored) {}
            }

            try {
                model.close();
            } catch (Exception ignored) {}

            if (pcmFile != null) {
                pcmFile.delete();
            }

            stopForeground(true);
            stopSelf();
        }
    }

    private void appendText(
            StringBuilder builder,
            String json) {

        try {

            JSONObject obj =
                    new JSONObject(json);

            String text =
                    obj.optString("text", "").trim();

            if (!text.isEmpty()) {

                if (builder.length() > 0) {
                    builder.append(" ");
                }

                builder.append(text);
            }

        } catch (Exception ignored) {}
    }

    private void finishWithError(String message) {

        getSharedPreferences("capture", MODE_PRIVATE)
                .edit()
                .putBoolean("active", false)
                .putBoolean("transcribing", false)
                .putBoolean("completed", true)
                .putString("stt_error", message)
                .apply();

        if (pcmFile != null) {
            pcmFile.delete();
        }

        stopForeground(true);
        stopSelf();
    }

    private String createWav()
            throws IOException {

        String stamp =
                new SimpleDateFormat(
                        "yyyyMMdd_HHmmss",
                        Locale.KOREA
                ).format(new Date());

        String fileName =
                "Bandi_" + stamp + ".wav";

        ContentValues values =
                new ContentValues();

        values.put(
                MediaStore.MediaColumns.DISPLAY_NAME,
                fileName
        );

        values.put(
                MediaStore.MediaColumns.MIME_TYPE,
                "audio/wav"
        );

        values.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "Download/BandiStudy"
        );

        Uri uri =
                getContentResolver()
                        .insert(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                values
                        );

        if (uri == null) {
            throw new IOException(
                    "MediaStore insert failed"
            );
        }

        OutputStream out =
                getContentResolver()
                        .openOutputStream(uri);

        if (out == null) {
            throw new IOException(
                    "OutputStream failed"
            );
        }

        writeWavHeader(
                out,
                totalBytes,
                SAMPLE_RATE,
                CHANNELS,
                BITS
        );

        FileInputStream in =
                new FileInputStream(pcmFile);

        byte[] buffer = new byte[65536];

        int read;

        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }

        in.close();
        out.flush();
        out.close();

        return "Download/BandiStudy/" + fileName;
    }

    private String saveTranscript(String text)
            throws IOException {

        String stamp =
                new SimpleDateFormat(
                        "yyyyMMdd_HHmmss",
                        Locale.KOREA
                ).format(new Date());

        String fileName =
                "Bandi_" + stamp + "_English.txt";

        ContentValues values =
                new ContentValues();

        values.put(
                MediaStore.MediaColumns.DISPLAY_NAME,
                fileName
        );

        values.put(
                MediaStore.MediaColumns.MIME_TYPE,
                "text/plain"
        );

        values.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "Download/BandiStudy"
        );

        Uri uri =
                getContentResolver()
                        .insert(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                values
                        );

        if (uri == null) {
            throw new IOException("TXT insert failed");
        }

        OutputStream out =
                getContentResolver()
                        .openOutputStream(uri);

        if (out == null) {
            throw new IOException("TXT output failed");
        }

        out.write(
                text.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8
                )
        );

        out.flush();
        out.close();

        return "Download/BandiStudy/" + fileName;
    }

    private void writeWavHeader(
            OutputStream out,
            long audioLength,
            int sampleRate,
            int channels,
            int bits)
            throws IOException {

        long byteRate =
                sampleRate * channels * bits / 8;

        long dataLength =
                audioLength + 36;

        byte[] header = new byte[44];

        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';

        writeInt(header, 4, (int) dataLength);

        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';

        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';

        writeInt(header, 16, 16);

        header[20] = 1;
        header[21] = 0;

        header[22] = (byte) channels;
        header[23] = 0;

        writeInt(header, 24, sampleRate);
        writeInt(header, 28, (int) byteRate);

        int blockAlign =
                channels * bits / 8;

        header[32] = (byte) blockAlign;
        header[33] = 0;

        header[34] = (byte) bits;
        header[35] = 0;

        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';

        writeInt(
                header,
                40,
                (int) audioLength
        );

        out.write(header);
    }

    private void writeInt(
            byte[] data,
            int offset,
            int value) {

        data[offset] =
                (byte) (value & 0xff);

        data[offset + 1] =
                (byte) ((value >> 8) & 0xff);

        data[offset + 2] =
                (byte) ((value >> 16) & 0xff);

        data[offset + 3] =
                (byte) ((value >> 24) & 0xff);
    }

    @Override
    public void onDestroy() {

        running = false;

        if (!finishing) {

            try {
                if (recorder != null) recorder.stop();
            } catch (Exception ignored) {}

            try {
                if (pcmOutput != null) pcmOutput.close();
            } catch (Exception ignored) {}

            try {
                if (recorder != null) recorder.release();
            } catch (Exception ignored) {}

            if (projection != null) {
                try {
                    projection.stop();
                } catch (Exception ignored) {}
            }
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
