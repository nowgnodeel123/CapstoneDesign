package com.example.blinking_eyes;

import static android.Manifest.permission.CAMERA;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;

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

public class MainActivity extends AppCompatActivity
        implements CameraBridgeViewBase.CvCameraViewListener2 {

    private final int m_Camidx = 1; // front : 1, back : 0
    private CameraBridgeViewBase m_CameraView;
    private static final int CAMERA_PERMISSION_CODE = 200;
    private static final String TAG = "opencv";

    static {
        System.loadLibrary("blinking_eyes");
    }

    private Mat matInput;

    // 멤버 변수 추가
    private CascadeClassifier cascadeClassifier;

    private int absoluteEyeSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        m_CameraView = (CameraBridgeViewBase) findViewById(R.id.activity_surface_view);
        m_CameraView.setVisibility(SurfaceView.VISIBLE);
        m_CameraView.setCvCameraViewListener(this);
        m_CameraView.setCameraIndex(m_Camidx); // 카메라 인덱스 사용
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
            onCameraPermissionGranted();
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

    protected void onCameraPermissionGranted() {
        List<? extends CameraBridgeViewBase> cameraViews = getCameraViewList();
        if (cameraViews == null) {
            return;
        }
        for (CameraBridgeViewBase cameraBridgeViewBase : cameraViews) {
            if (cameraBridgeViewBase != null) {
                cameraBridgeViewBase.setCameraPermissionGranted();
                cameraBridgeViewBase.enableView(); // 카메라 뷰 활성화
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

        if (cascadeClassifier != null) {
            MatOfRect eyes = new MatOfRect();
            // grayscale 이미지가 더 빠른 검출 속도와 정확도를 제공합니다.
            Mat gray = inputFrame.gray();
            // 눈을 검출합니다.
            cascadeClassifier.detectMultiScale(gray, eyes, 1.1, 2, 2,
                    new Size(absoluteEyeSize, absoluteEyeSize), new Size());
            Rect[] eyesArray = eyes.toArray();
            for (Rect rect : eyesArray) {
                // 검출된 눈 주위에 사각형을 그립니다.
                Imgproc.rectangle(matInput, rect.tl(), rect.br(), new Scalar(0, 255, 0), 2);
            }
        }

        return matInput;
    }
}