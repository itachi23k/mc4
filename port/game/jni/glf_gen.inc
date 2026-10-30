static void Glf_gen_0(JNIEnv *env, jclass c, jstring a0, jint a1, jint a2) { (void)env; GEN_SAID("BrowserLaunch(Ljava/lang/String;II)V");  }
static void Glf_gen_1(JNIEnv *env, jclass c, jint a0) { (void)env; GEN_SAID("GLLiveLaunch(I)V");  }
static void Glf_gen_2(JNIEnv *env, jclass c, jint a0) { (void)env; GEN_SAID("GLLiveNotifyTrophy(I)V");  }
static void Glf_gen_3(JNIEnv *env, jclass c, jint a0, jstring a1) { (void)env; GEN_SAID("IGPLaunch(ILjava/lang/String;)V");  }
static void Glf_gen_4(JNIEnv *env, jclass c, jint a0) { (void)env; GEN_SAID("SetOrientation(I)V");  }
static void Glf_gen_5(JNIEnv *env, jclass c, jboolean a0, jfloat a1) { (void)env; GEN_SAID("enableAccelerometer(ZF)V");  }
static void Glf_gen_6(JNIEnv *env, jclass c, jboolean a0, jfloat a1) { (void)env; GEN_SAID("enableGyroscope(ZF)V");  }
static void Glf_gen_7(JNIEnv *env, jclass c) { (void)env; GEN_SAID("resetGyroCalibration()V");  }
static void Glf_gen_8(JNIEnv *env, jclass c, jboolean a0) { (void)env; GEN_SAID("setCalibratingGyro(Z)V");  }

#define GLF_GEN_METHODS \
    ManagedMethod::RegisterStatic<&Glf_gen_0>(CLS::clazz, "BrowserLaunch", "(Ljava/lang/String;II)V"), \
    ManagedMethod::RegisterStatic<&Glf_gen_1>(CLS::clazz, "GLLiveLaunch", "(I)V"), \
    ManagedMethod::RegisterStatic<&Glf_gen_2>(CLS::clazz, "GLLiveNotifyTrophy", "(I)V"), \
    ManagedMethod::RegisterStatic<&Glf_gen_3>(CLS::clazz, "IGPLaunch", "(ILjava/lang/String;)V"), \
    ManagedMethod::RegisterStatic<&Glf_gen_4>(CLS::clazz, "SetOrientation", "(I)V"), \
    ManagedMethod::RegisterStatic<&Glf_gen_5>(CLS::clazz, "enableAccelerometer", "(ZF)V"), \
    ManagedMethod::RegisterStatic<&Glf_gen_6>(CLS::clazz, "enableGyroscope", "(ZF)V"), \
    ManagedMethod::RegisterStatic<&Glf_gen_7>(CLS::clazz, "resetGyroCalibration", "()V"), \
    ManagedMethod::RegisterStatic<&Glf_gen_8>(CLS::clazz, "setCalibratingGyro", "(Z)V"), \
    /* end */
