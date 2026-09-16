package com.example.bandiaudiocapture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.*
import androidx.core.app.ActivityCompat
import kotlin.concurrent.thread
import kotlin.math.sqrt

class MainActivity : Activity() {

    private lateinit var status: TextView
    private var recorder: AudioRecord? = null
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40,80,40,40)
        }

        val title = TextView(this).apply {
            text = "반디 내부 오디오 테스트"
            textSize = 24f
        }

        status = TextView(this).apply {
            text = "\n아직 테스트하지 않았습니다.\n"
            textSize = 18f
        }

        val button = Button(this).apply {
            text = "내부 오디오 캡처 시작"
            setOnClickListener { startTest() }
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(button)

        setContentView(layout)
    }

    private fun startTest() {

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )

            status.text = "마이크 권한을 허용한 뒤 버튼을 다시 눌러주세요."
            return
        }

        val manager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            200
        )
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?
    ) {
        super.onActivityResult(requestCode,resultCode,data)

        if (requestCode != 200 ||
            resultCode != RESULT_OK ||
            data == null) {
            status.text = "캡처 권한이 허용되지 않았습니다."
            return
        }

        val manager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        val projection =
            manager.getMediaProjection(resultCode,data)

        val config =
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()

        val format =
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

        val min =
            AudioRecord.getMinBufferSize(
                44100,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        recorder =
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(min * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

        running = true
        recorder!!.startRecording()

        status.text =
            "캡처 중입니다.\n\n반디로 이동해서 영어를 재생하세요."

        thread {

            val buffer = ShortArray(min)

            while (running) {

                val n =
                    recorder?.read(buffer,0,buffer.size) ?: 0

                if (n > 0) {

                    var sum = 0.0

                    for (i in 0 until n) {
                        val v = buffer[i].toDouble()
                        sum += v*v
                    }

                    val rms = sqrt(sum/n)

                    runOnUiThread {

                        if (rms > 30) {
                            status.text =
                                "✓ 내부 오디오 감지됨!\n\nRMS: %.1f\n\n반디 일반 앱 캡처 가능성이 높습니다."
                                    .format(rms)
                        } else {
                            status.text =
                                "캡처 중...\nRMS: %.1f\n\n반디에서 영어를 재생해 주세요."
                                    .format(rms)
                        }
                    }
                }
            }
        }
    }
}
