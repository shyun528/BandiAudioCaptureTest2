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
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 100;
    private static final int REQ_CAPTURE = 200;

    private TextView status;
    private TextView transcript;
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(45, 70, 45, 50);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("반디 영어 학습 v4");
        title.setTextSize(25);

        TextView guide = new TextView(this);
        guide.setText(
                "\n반디 내부 음성을 녹음한 뒤\n" +
                "무료 오프라인 STT로 영어 원문을 만듭니다.\n" +
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
        stop.setText("녹음 종료 + 영어 변환");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, CaptureService.class);
            i.setAction(CaptureService.ACTION_STOP);
            startService(i);
            status.setText("녹음을 종료하고 영어로 변환합니다...");
        });

        TextView transcriptTitle = new TextView(this);
        transcriptTitle.setText("\n영어 스크립트");
        transcriptTitle.setTextSize(20);

        transcript = new TextView(this);
        transcript.setText("아직 생성된 스크립트가 없습니다.");
        transcript.setTextSize(17);
        transcript.setTextIsSelectable(true);
        transcript.setPadding(10,20,10,40);

        layout.addView(title);
        layout.addView(guide);
        layout.addView(status);
        layout.addView(start);
        layout.addView(stop);
        layout.addView(transcriptTitle);
        layout.addView(transcript);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);

        handler.post(updateStatus);
    }

    private final Runnable updateStatus = new Runnable() {
        @Override
        public void run() {

            android.content.SharedPreferences p =
                    getSharedPreferences("capture", MODE_PRIVATE);

            boolean active = p.getBoolean("active", false);
            boolean transcribing = p.getBoolean("transcribing", false);
            boolean completed = p.getBoolean("completed", false);

            float rms = p.getFloat("rms", 0f);
            long seconds = p.getLong("seconds", 0);
            String file = p.getString("file", "");
            String text = p.getString("transcript", "");
            String error = p.getString("stt_error", "");

            if (active) {
                status.setText(
                        "● 녹음 중\n\n" +
                        "RMS: " + String.format("%.1f", rms) +
                        "\n녹음 시간: " + seconds + "초\n\n" +
                        "반디로 이동해서 학습하세요."
                );
            } else if (transcribing) {
                status.setText(
                        "영어 음성을 분석하고 있습니다...\n\n" +
                        "녹음 시간: " + seconds + "초\n" +
                        "잠시 기다려 주세요."
                );
            } else if (completed) {
                if (!error.isEmpty()) {
                    status.setText(
                            "녹음은 저장됐지만 STT 오류가 발생했습니다.\n\n" +
                            error + "\n\nWAV:\n" + file
                    );
                } else {
                    status.setText(
                            "✓ 완료\n\n" +
                            "녹음 시간: " + seconds + "초\n\n" +
                            "WAV:\n" + file
                    );
                }
            }

            if (!text.isEmpty()) {
                transcript.setText(text);
            }

            handler.postDelayed(this, 500);
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
                .clear()
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

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_CAPTURE
                && resultCode == RESULT_OK
                && data != null) {

            Intent service =
                    new Intent(this, CaptureService.class);

            service.setAction(CaptureService.ACTION_START);
            service.putExtra("resultCode", resultCode);
            service.putExtra("data", data);

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
