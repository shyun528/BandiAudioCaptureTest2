package com.example.bandiaudiocapture;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 100;
    private static final int REQ_CAPTURE = 200;

    private TextView status;
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50,100,50,50);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("반디 학습 녹음 테스트 v3");
        title.setTextSize(24);

        TextView guide = new TextView(this);
        guide.setText(
            "\n반디의 내부 음성을 WAV 파일로 저장합니다.\n" +
            "블루투스 이어폰을 그대로 사용하셔도 됩니다.\n"
        );
        guide.setTextSize(16);

        status = new TextView(this);
        status.setText("\n준비 완료\n");
        status.setTextSize(18);

        Button start = new Button(this);
        start.setText("학습 녹음 시작");
        start.setOnClickListener(v -> beginCapture());

        Button stop = new Button(this);
        stop.setText("학습 녹음 종료");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            status.setText("녹음을 종료하고 WAV 파일을 완성하는 중입니다...");
        });

        layout.addView(title);
        layout.addView(guide);
        layout.addView(status);
        layout.addView(start);
        layout.addView(stop);

        setContentView(layout);

        handler.post(updateStatus);
    }

    private final Runnable updateStatus = new Runnable() {
        @Override
        public void run() {

            boolean active = getSharedPreferences("capture", MODE_PRIVATE)
                    .getBoolean("active", false);

            float rms = getSharedPreferences("capture", MODE_PRIVATE)
                    .getFloat("rms", 0f);

            String file = getSharedPreferences("capture", MODE_PRIVATE)
                    .getString("file", "");

            long bytes = getSharedPreferences("capture", MODE_PRIVATE)
                    .getLong("bytes", 0);

            long seconds = getSharedPreferences("capture", MODE_PRIVATE)
                    .getLong("seconds", 0);

            boolean completed = getSharedPreferences("capture", MODE_PRIVATE)
                    .getBoolean("completed", false);

            if (active) {

                status.setText(
                    "● 녹음 중\n\n" +
                    "RMS: " + String.format("%.1f", rms) +
                    "\n녹음 시간: " + seconds + "초" +
                    "\n\n반디로 이동해서 학습하세요."
                );

            } else if (completed) {

                status.setText(
                    "✓ 녹음 완료\n\n" +
                    "녹음 시간: " + seconds + "초\n" +
                    "파일 크기: " + (bytes / 1024) + " KB\n\n" +
                    "저장 위치:\n" + file
                );
            }

            handler.postDelayed(this,500);
        }
    };

    private void beginCapture() {

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_AUDIO
            );
            return;
        }

        getSharedPreferences("capture", MODE_PRIVATE)
                .edit()
                .putBoolean("completed", false)
                .apply();

        MediaProjectionManager manager =
                (MediaProjectionManager)
                        getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        startActivityForResult(
                manager.createScreenCaptureIntent(),
                REQ_CAPTURE
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode, permissions, grantResults
        );

        if (requestCode == REQ_AUDIO
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            beginCapture();
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(requestCode,resultCode,data);

        if (requestCode == REQ_CAPTURE
                && resultCode == RESULT_OK
                && data != null) {

            Intent service =
                    new Intent(this,CaptureService.class);

            service.putExtra("resultCode",resultCode);
            service.putExtra("data",data);

            startForegroundService(service);

            status.setText(
                    "녹음을 시작합니다...\n\n" +
                    "반디로 이동해서 영어 음성을 재생하세요."
            );

        } else if (requestCode == REQ_CAPTURE) {
            status.setText("화면 공유 권한이 허용되지 않았습니다.");
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(updateStatus);
        super.onDestroy();
    }
}
