package com.example.blinking_eyes;

import static android.Manifest.permission.CAMERA;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CustomOpenCVLoader;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

// 지금 3분 단위로 카메라가 on/off는 되는데 지금 다시 on 되고 나서 눈 인식 후 밝기 볼륨 조절 메소드가 동작을
// 하지 않고 있음.

public class MainActivity extends AppCompatActivity
        implements CameraBridgeViewBase.CvCameraViewListener2 {

    private WebView webView;
    private EditText urlField;
    private Button goButton;
    private final int m_Camidx = 1; // front : 1, back : 0
    private CameraBridgeViewBase m_CameraView;
    private static final int CAMERA_PERMISSION_CODE = 200;
    private static final String TAG = "opencv";
    // 카메라 상태를 추적 하기 위한 클래스 멤버 변수 추가
    private boolean isCameraEnabled = false;


    static {
        System.loadLibrary("blinking_eyes");
    }

    private Mat matInput;

    // 멤버 변수 추가
    private CascadeClassifier cascadeClassifier;

    private int absoluteEyeSize;
    private long lastEyeDetectedTime = 0; // 마지막으로 눈이 검출된 시간
    private AudioManager audioManager;

    @SuppressLint("SetJavaScriptEnabled") // 경고 무시함..
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = (WebView) findViewById(R.id.webview);
        urlField = (EditText) findViewById(R.id.urlField);
        goButton = (Button) findViewById(R.id.goButton);

        webView.setWebViewClient(new WebViewClient());
        webView.getSettings().setJavaScriptEnabled(true);

        Button btnToggleCamera = (Button) findViewById(R.id.btnToggleCamera);
        btnToggleCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleCamera();
            }
        });

        goButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String url = urlField.getText().toString().trim();
                if (!url.startsWith("http://") && !url.startsWith("http://")) {
                    url = "http://" + url; // http://로 시작하지 않는 경우에는 자동으로 추가해주도록..
                }
                webView.loadUrl(url);
            }
        });

        m_CameraView = (CameraBridgeViewBase) findViewById(R.id.activity_surface_view);
        m_CameraView.setVisibility(SurfaceView.VISIBLE);
        m_CameraView.setCvCameraViewListener(this);
        m_CameraView.setCameraIndex(m_Camidx); // 카메라 인덱스 사용

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    private void toggleCamera() {
        if (isCameraEnabled) {
            // 현재 카메라가 켜져 있으면 끕니다
            m_CameraView.setVisibility(SurfaceView.GONE);
            m_CameraView.disableView();
        } else {
            // 현재 카메라가 꺼져 있으면 켭니다
            m_CameraView.setVisibility(SurfaceView.VISIBLE);
            m_CameraView.enableView();
            m_CameraView.setCvCameraViewListener(this); // 선택적으로 리스너를 재설정합니다
        }
        isCameraEnabled = !isCameraEnabled; // 카메라 상태를 토글합니다
    }

    @Override
    protected void onStart() {
        super.onStart();
        boolean _Permission = true; // 변수 추가
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // 최소 버전 보다 버전이 높은지 확인
            if (checkSelfPermission(CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{CAMERA}, CAMERA_PERMISSION_CODE);
                _Permission = false;
            }
        }
        if (_Permission) {
            // 여기서 카메라 뷰 받아옴
            activeCamera();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!OpenCVLoader.initLocal()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            CustomOpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, m_LoaderCallback);
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!");
            m_LoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (m_CameraView != null)
            m_CameraView.disableView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (m_CameraView != null)
            m_CameraView.disableView();
    }

    private final BaseLoaderCallback m_LoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS: {
                    Log.i(TAG, "OpenCV loaded successfully");

                    try {
                        // haarcascade_eye 파일을 로드합니다.
                        InputStream is = getResources().openRawResource(R.raw.haarcascade_eye);
                        File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
                        File mCascadeFile = new File(cascadeDir, "haarcascade_eye.xml");
                        FileOutputStream os = new FileOutputStream(mCascadeFile);

                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                        is.close();
                        os.close();

                        cascadeClassifier = new CascadeClassifier(mCascadeFile.getAbsolutePath());
                        if (!cascadeClassifier.empty()) {
                            Log.i(TAG, "Loaded cascade classifier from " + mCascadeFile.getAbsolutePath());
                        } else {
                            Log.i(TAG, "Failed to load cascade classifier");
                            cascadeClassifier = null;
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        Log.i(TAG, "Failed to load cascade. Exception thrown: " + e);
                    }
                }
                break;
                default: {
                    super.onManagerConnected(status);
                }
                break;
            }
        }
    };

    // 2분 주기로 실행 시키는 메소드
    private void activeCamera() {
        final Handler handler = new Handler();
        final Runnable activeCamera = new Runnable() {
            @Override
            public void run() {
                onCameraPermissionGranted();
                // 2분 후에 다시 이 코드를 실행
                handler.postDelayed(this, 120000);
            }
        };
        // 최초 실행을 위해 0ms 후에 실행
        handler.postDelayed(activeCamera, 0);
    }

    // 20초 동안 활성화 되로록 함.
    protected void onCameraPermissionGranted() {
        List<? extends CameraBridgeViewBase> cameraViews = getCameraViewList();
        if (cameraViews == null) {
            return;
        }
        for (CameraBridgeViewBase cameraBridgeViewBase : cameraViews) {
            if (cameraBridgeViewBase != null) {
                cameraBridgeViewBase.setCameraPermissionGranted();
                cameraBridgeViewBase.enableView(); // 카메라 뷰 활성화
                new Handler().postDelayed(cameraBridgeViewBase::disableView, 20000);
            }
        }
    }

    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(m_CameraView);
    }

    public native String stringFromJNI();

    @Override
    public void onCameraViewStarted(int width, int height) {
        m_CameraView.setMaxFrameSize(width, height); // 프레임 크기 설정
        m_CameraView.setCameraIndex(m_Camidx); // 전면 카메라 인덱스 사용
    }

    @Override
    public void onCameraViewStopped() {
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        matInput = inputFrame.rgba();
        boolean eyesDetected = false;

        if (cascadeClassifier != null) {
            MatOfRect eyes = new MatOfRect();
            Mat gray = inputFrame.gray();
            cascadeClassifier.detectMultiScale(gray, eyes, 1.2, 3, 2,
                    new Size(absoluteEyeSize, absoluteEyeSize), new Size());
            Rect[] eyesArray = eyes.toArray();
            for (Rect rect : eyesArray) {
                Imgproc.rectangle(matInput, rect.tl(), rect.br(), new Scalar(0, 255, 0), 2);
                eyesDetected = true;
            }
        }

        if (eyesDetected) {
            lastEyeDetectedTime = System.currentTimeMillis();
        } else if ((System.currentTimeMillis() - lastEyeDetectedTime) > 2000) { // 2초 동안 눈이 검출되지 않음
            setBrightnessAndVolumeMinimum(this);
        }

        return matInput;
    }

    private void setBrightnessAndVolumeMinimum(Context context) {
        Activity activity = (Activity) context;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.System.canWrite(context)) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                        intent.setData(Uri.parse("package:" + context.getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                        return; // 사용자가 권한을 허용할 때까지 기다립니다.
                    }
                }
                final Handler handler = new Handler(Looper.getMainLooper());
                final int duration = 10000; // 10초
                final int interval = 1000; // 1초마다 단계마다 변화
                final int steps = duration / interval; // 단계 수 (10단계)
                final float brightnessStep = 1.0f / (float) steps;
                final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                final int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                final float volumeStep = maxVolume / (float) steps; // 소수점을 유지하기 위해 float로 나눗셈

                // 초기 밝기와 소리 설정
                final WindowManager.LayoutParams layoutParams = activity.getWindow().getAttributes();

                final Runnable reduceBrightnessAndVolume = new Runnable() {
                    int currentStep = 0;

                    @Override
                    public void run() {
                        if (currentStep < steps) {
                            // 밝기 줄이기
                            layoutParams.screenBrightness = Math.max(layoutParams.screenBrightness - brightnessStep, 0.01f);
                            activity.getWindow().setAttributes(layoutParams);

                            // 소리 줄이기
                            int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                            // volumeStep을 반올림하여 정수로 변환
                            int newVolume = Math.max(currentVolume - (int) Math.round(volumeStep), 0);
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0);

                            currentStep++;
                            handler.postDelayed(this, interval);
                        } else {
                            // 3초 후 애플리케이션 종료
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    activity.finishAffinity(); // 액티비티 스택에 있는 모든 액티비티를 종료함.
                                    System.exit(0); // 애플리케이션 프로세스를 종료함.
                                }
                            }, 5000); // 5초 지연 시킴.
                        }
                    }
                };

                // 처음 실행
                handler.post(reduceBrightnessAndVolume);
            }
        });
    }
}