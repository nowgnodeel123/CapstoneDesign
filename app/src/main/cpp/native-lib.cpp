#include <jni.h>
#include <string>
#include <opencv2/opencv.hpp>

extern "C" JNIEXPORT jstring

JNICALL
Java_com_example_blinking_1eyes_MainActivity_stringFromJNI(
        JNIEnv *env,
        jobject /* this */) {

    std::string cv_ver = "CV Version is " CV_VERSION;
    return env->NewStringUTF(cv_ver.c_str());
//    std::string hello = "Hello from C++";
//    return env->NewStringUTF(hello.c_str());
}