#include "keyring_backend.hpp"

#if defined(__ANDROID__)

#include <jni.h>
#include <mutex>
#include <string>
#include <vector>

namespace i2pchat::storage::keyring::backend {
namespace {

JavaVM* g_vm = nullptr;
std::mutex g_mu;

JNIEnv* env_for_current_thread() {
    if (g_vm == nullptr) {
        return nullptr;
    }
    JNIEnv* env = nullptr;
    const jint status = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) {
        return env;
    }
    if (status == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
        return env;
    }
    return nullptr;
}

jclass keyring_class(JNIEnv* env) {
    return env->FindClass("org/i2pchat/android/NativeKeyring");
}

jstring utf8(JNIEnv* env, std::string_view text) {
    return env->NewStringUTF(std::string(text).c_str());
}

}  // namespace

void set_java_vm(void* vm) {
    std::lock_guard lock(g_mu);
    g_vm = static_cast<JavaVM*>(vm);
}

bool available() { return g_vm != nullptr; }

std::optional<std::string> get(std::string_view service, std::string_view account) {
    JNIEnv* env = env_for_current_thread();
    if (env == nullptr) {
        return std::nullopt;
    }
    jclass cls = keyring_class(env);
    if (cls == nullptr) {
        return std::nullopt;
    }
    jmethodID method = env->GetStaticMethodID(cls, "get",
                                              "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (method == nullptr) {
        env->DeleteLocalRef(cls);
        return std::nullopt;
    }
    jstring j_service = utf8(env, service);
    jstring j_account = utf8(env, account);
    auto j_value = static_cast<jstring>(
        env->CallStaticObjectMethod(cls, method, j_service, j_account));
    env->DeleteLocalRef(j_service);
    env->DeleteLocalRef(j_account);
    env->DeleteLocalRef(cls);
    if (j_value == nullptr) {
        return std::nullopt;
    }
    const char* chars = env->GetStringUTFChars(j_value, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(j_value, chars);
    }
    env->DeleteLocalRef(j_value);
    return out;
}

bool set(std::string_view service, std::string_view account, std::string_view secret) {
    JNIEnv* env = env_for_current_thread();
    if (env == nullptr) {
        return false;
    }
    jclass cls = keyring_class(env);
    if (cls == nullptr) {
        return false;
    }
    jmethodID method = env->GetStaticMethodID(cls, "set",
                                              "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
    if (method == nullptr) {
        env->DeleteLocalRef(cls);
        return false;
    }
    jstring j_service = utf8(env, service);
    jstring j_account = utf8(env, account);
    jstring j_secret = utf8(env, secret);
    const jboolean ok =
        env->CallStaticBooleanMethod(cls, method, j_service, j_account, j_secret);
    env->DeleteLocalRef(j_service);
    env->DeleteLocalRef(j_account);
    env->DeleteLocalRef(j_secret);
    env->DeleteLocalRef(cls);
    return ok == JNI_TRUE;
}

bool erase(std::string_view service, std::string_view account) {
    JNIEnv* env = env_for_current_thread();
    if (env == nullptr) {
        return false;
    }
    jclass cls = keyring_class(env);
    if (cls == nullptr) {
        return false;
    }
    jmethodID method =
        env->GetStaticMethodID(cls, "erase", "(Ljava/lang/String;Ljava/lang/String;)Z");
    if (method == nullptr) {
        env->DeleteLocalRef(cls);
        return false;
    }
    jstring j_service = utf8(env, service);
    jstring j_account = utf8(env, account);
    const jboolean ok = env->CallStaticBooleanMethod(cls, method, j_service, j_account);
    env->DeleteLocalRef(j_service);
    env->DeleteLocalRef(j_account);
    env->DeleteLocalRef(cls);
    return ok == JNI_TRUE;
}

}  // namespace i2pchat::storage::keyring::backend

#endif  // __ANDROID__
