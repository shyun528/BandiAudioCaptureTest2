package com.example.bandiaudiocapture;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
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
        layout.setPadding(50, 100, 50, 50);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("반디 내부 오디오 테스트");
        title.setTextSize(24);

        status = new TextView(this);
        status.setText("\n준비 완료\n\n아래 버튼을 눌러주세요.\n");
        status.setTextSize(18);

        Button start = new Button(this);
        start.setText("내부 오디오 캡처 시작");
        start.setOnClickListener(v -> beginCapture());

        Button stop = new Button(this);
        stop.setText("캡처 중지");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            status.setText("캡처를 중지했습니다.");
        });

        layout.addView(title);
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

            if (active) {
                if (rms > 30f) {
                    status.setText(
                            "✓ 내부 오디오 감지됨!\n\nRMS: "
                            + String.format("%.1f", rms)
                            + "\n\n반디 오디오가 잡히고 있습니다."
                    );
                } else {
                    status.setText(
                            "캡처 중...\n\nRMS: "
                            + String.format("%.1f", rms)
                            + "\n\n반디에서 영어 음성을 재생하세요."
                    );
                }
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
                requestCode,
                permissions,
                grantResults
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

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode == REQ_CAPTURE
                && resultCode == RESULT_OK
                && data != null) {

            Intent service = new Intent(
                    this,
                    CaptureService.class
            );

            service.putExtra("resultCode", resultCode);
            service.putExtra("data", data);

            startForegroundService(service);

            status.setText(
                    "캡처를 시작합니다...\n\n반디로 이동해 영어를 재생하세요."
            );

        } else if (requestCode == REQ_CAPTURE) {

            status.setText(
                    "화면 공유 권한이 허용되지 않았습니다."
            );
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(updateStatus);
        super.onDestroy();
    }
}
