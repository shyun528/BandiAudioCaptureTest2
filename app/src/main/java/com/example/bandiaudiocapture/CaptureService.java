package com.example.bandiaudiocapture;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

public class CaptureService extends Service {

    private static final String CHANNEL = "capture_channel";

    private AudioRecord recorder;
    private MediaProjection projection;
    private volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager nm =
                (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL,
                        "Bandi Audio Capture",
                        NotificationManager.IMPORTANCE_LOW
                );

        nm.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId) {

        Notification notification =
                new Notification.Builder(this, CHANNEL)
                        .setContentTitle("Bandi Audio Test")
                        .setContentText("내부 오디오를 확인하고 있습니다.")
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

        try {

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
                    new AudioPlaybackCaptureConfiguration
                            .Builder(projection)
                            .addMatchingUsage(
                                    AudioAttributes.USAGE_MEDIA
                            )
                            .build();

            int sampleRate = 44100;

            AudioFormat format =
                    new AudioFormat.Builder()
                            .setEncoding(
                                    AudioFormat.ENCODING_PCM_16BIT
                            )
                            .setSampleRate(sampleRate)
                            .setChannelMask(
                                    AudioFormat.CHANNEL_IN_STEREO
                            )
                            .build();

            int minBuffer =
                    AudioRecord.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_IN_STEREO,
                            AudioFormat.ENCODING_PCM_16BIT
                    );

            recorder =
                    new AudioRecord.Builder()
                            .setAudioFormat(format)
                            .setBufferSizeInBytes(
                                    Math.max(
                                            minBuffer * 2,
                                            sampleRate
                                    )
                            )
                            .setAudioPlaybackCaptureConfig(
                                    config
                            )
                            .build();

            recorder.startRecording();

            running = true;

            getSharedPreferences(
                    "capture",
                    MODE_PRIVATE
            ).edit()
                    .putBoolean("active", true)
                    .putFloat("rms", 0f)
                    .apply();

            new Thread(this::captureLoop).start();

        } catch (Exception e) {

            getSharedPreferences(
                    "capture",
                    MODE_PRIVATE
            ).edit()
                    .putBoolean("active", false)
                    .putFloat("rms", -1f)
                    .apply();

            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void captureLoop() {

        short[] buffer =
                new short[4096];

        while (running && recorder != null) {

            int n =
                    recorder.read(
                            buffer,
                            0,
                            buffer.length
                    );

            if (n > 0) {

                double sum = 0;

                for (int i = 0; i < n; i++) {

                    double value = buffer[i];

                    sum += value * value;
                }

                float rms =
                        (float)
                                Math.sqrt(
                                        sum / n
                                );

                getSharedPreferences(
                        "capture",
                        MODE_PRIVATE
                ).edit()
                        .putFloat("rms", rms)
                        .apply();
            }
        }
    }

    @Override
    public void onDestroy() {

        running = false;

        getSharedPreferences(
                "capture",
                MODE_PRIVATE
        ).edit()
                .putBoolean("active", false)
                .apply();

        try {

            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }

        } catch (Exception ignored) {
        }

        if (projection != null) {
            projection.stop();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
