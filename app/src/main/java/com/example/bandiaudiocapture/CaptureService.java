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


import com.whispercpp.java.whisper.WhisperLib;

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

            startWhisper();

        }).start();
    }

    private void startWhisper() {

        new Thread(() -> transcribeWithWhisper()).start();
    }

    private void transcribeWithWhisper() {

        WhisperLib whisper = null;
        long context = 0;

        try {

            float[] audio = convertPcmToWhisperAudio();

            if (audio.length == 0) {
                throw new IOException("녹음된 음성이 없습니다.");
            }

            whisper = new WhisperLib();

            context = whisper.initContextFromAsset(
                    getAssets(),
                    "models/ggml-tiny.en.bin"
            );

            if (context == 0) {
                throw new IOException(
                        "Whisper 모델을 열 수 없습니다."
                );
            }

            int threads =
                    Math.max(
                            2,
                            Math.min(
                                    4,
                                    Runtime.getRuntime()
                                            .availableProcessors()
                            )
                    );

            whisper.fullTranscribe(
                    context,
                    threads,
                    audio
            );

            int count =
                    whisper.getTextSegmentCount(context);

            StringBuilder transcript =
                    new StringBuilder();

            for (int i = 0; i < count; i++) {

                String text =
                        whisper.getTextSegment(
                                context,
                                i
                        );

                if (text != null) {

                    text = text.trim();

                    if (!text.isEmpty()) {

                        if (transcript.length() > 0) {
                            transcript.append(" ");
                        }

                        transcript.append(text);
                    }
                }
            }

            String finalText =
                    transcript.toString()
                            .trim()
                            .replaceAll("\\s+", " ");

            String txtPath = "";

            if (!finalText.isEmpty()) {
                txtPath = saveTranscript(finalText);
            }

            getSharedPreferences(
                    "capture",
                    MODE_PRIVATE
            ).edit()
                    .putBoolean("transcribing", false)
                    .putBoolean("completed", true)
                    .putString("transcript", finalText)
                    .putString(
                            "transcript_file",
                            txtPath
                    )
                    .putString("stt_error", "")
                    .apply();

        } catch (Throwable e) {

            finishWithError(
                    "Whisper 변환 오류: "
                            + e.getClass().getSimpleName()
                            + " / "
                            + String.valueOf(e.getMessage())
            );

            return;

        } finally {

            if (whisper != null && context != 0) {
                try {
                    whisper.freeContext(context);
                } catch (Throwable ignored) {}
            }

            if (pcmFile != null) {
                pcmFile.delete();
            }
        }

        stopForeground(true);
        stopSelf();
    }

    private float[] convertPcmToWhisperAudio()
            throws IOException {

        long stereoFrames =
                pcmFile.length() / 4;

        int outputSamples =
                (int) Math.min(
                        Integer.MAX_VALUE - 8L,
                        stereoFrames * STT_RATE
                                / SAMPLE_RATE + 2
                );

        float[] output =
                new float[outputSamples];

        FileInputStream in =
                new FileInputStream(pcmFile);

        byte[] buffer =
                new byte[65536];

        int outPos = 0;
        long phase = 0;

        int read;

        while ((read = in.read(buffer)) > 0) {

            int usable = read - (read % 4);

            for (int i = 0;
                 i < usable && outPos < output.length;
                 i += 4) {

                short left =
                        (short) (
                                (buffer[i] & 0xff)
                                |
                                (buffer[i + 1] << 8)
                        );

                short right =
                        (short) (
                                (buffer[i + 2] & 0xff)
                                |
                                (buffer[i + 3] << 8)
                        );

                float mono =
                        (((int) left + (int) right)
                                / 2.0f)
                                / 32768.0f;

                phase += STT_RATE;

                if (phase >= SAMPLE_RATE) {

                    phase -= SAMPLE_RATE;

                    output[outPos++] = mono;
                }
            }
        }

        in.close();

        if (outPos == output.length) {
            return output;
        }

        float[] trimmed =
                new float[outPos];

        System.arraycopy(
                output,
                0,
                trimmed,
                0,
                outPos
        );

        return trimmed;
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
