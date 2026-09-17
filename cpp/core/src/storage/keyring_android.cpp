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
jclass g_keyring_class = nullptr;
jmethodID g_get_method = nullptr;
jmethodID g_set_method = nullptr;
jmethodID g_erase_method = nullptr;

void clear_pending(JNIEnv* env) {
    if (env != nullptr && env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

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
    if (g_keyring_class != nullptr) {
        return g_keyring_class;
    }
    jclass local = env->FindClass("org/i2pchat/android/NativeKeyring");
    if (local == nullptr) {
        clear_pending(env);
        return nullptr;
    }
    return local;
}

jstring utf8(JNIEnv* env, std::string_view text) {
    clear_pending(env);
    return env->NewStringUTF(std::string(text).c_str());
}

}  // namespace

void set_java_vm(void* vm) {
    std::lock_guard lock(g_mu);
    g_vm = static_cast<JavaVM*>(vm);
}

void init_jni(void* jni_env) {
    auto* env = static_cast<JNIEnv*>(jni_env);
    if (env == nullptr) {
        return;
    }
    std::lock_guard lock(g_mu);
    if (g_keyring_class != nullptr) {
        return;
    }
    jclass local = env->FindClass("org/i2pchat/android/NativeKeyring");
    if (local == nullptr) {
        clear_pending(env);
        return;
    }
    g_keyring_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_get_method = env->GetStaticMethodID(g_keyring_class, "get",
                                            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    g_set_method = env->GetStaticMethodID(g_keyring_class, "set",
                                            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
    g_erase_method = env->GetStaticMethodID(g_keyring_class, "erase",
                                              "(Ljava/lang/String;Ljava/lang/String;)Z");
    clear_pending(env);
}

bool available() { return g_vm != nullptr && g_keyring_class != nullptr; }

std::optional<std::string> get(std::string_view service, std::string_view account) {
    JNIEnv* env = env_for_current_thread();
    if (env == nullptr) {
        return std::nullopt;
    }
    jclass cls = keyring_class(env);
    if (cls == nullptr) {
        return std::nullopt;
    }
    jmethodID method = g_get_method;
    if (method == nullptr) {
        method = env->GetStaticMethodID(cls, "get",
                                        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        if (method == nullptr) {
            clear_pending(env);
            if (cls != g_keyring_class) {
                env->DeleteLocalRef(cls);
            }
            return std::nullopt;
        }
    }
    jstring j_service = utf8(env, service);
    jstring j_account = utf8(env, account);
    if (j_service == nullptr || j_account == nullptr) {
        clear_pending(env);
        if (j_service != nullptr) {
            env->DeleteLocalRef(j_service);
        }
        if (j_account != nullptr) {
            env->DeleteLocalRef(j_account);
        }
        if (cls != g_keyring_class) {
            env->DeleteLocalRef(cls);
        }
        return std::nullopt;
    }
    auto j_value = static_cast<jstring>(
        env->CallStaticObjectMethod(cls, method, j_service, j_account));
    env->DeleteLocalRef(j_service);
    env->DeleteLocalRef(j_account);
    if (cls != g_keyring_class) {
        env->DeleteLocalRef(cls);
    }
    if (env->ExceptionCheck()) {
        clear_pending(env);
        return std::nullopt;
    }
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
    jmethodID method = g_set_method;
    if (method == nullptr) {
        method = env->GetStaticMethodID(cls, "set",
                                        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
        if (method == nullptr) {
            clear_pending(env);
            if (cls != g_keyring_class) {
                env->DeleteLocalRef(cls);
            }
            return false;
        }
    }
    jstring j_service = utf8(env, service);
    jstring j_account = utf8(env, account);
    jstring j_secret = utf8(env, secret);
    const jboolean ok =
        env->CallStaticBooleanMethod(cls, method, j_service, j_account, j_secret);
    env->DeleteLocalRef(j_service);
    env->DeleteLocalRef(j_account);
    env->DeleteLocalRef(j_secret);
    if (cls != g_keyring_class) {
        env->DeleteLocalRef(cls);
    }
    if (env->ExceptionCheck()) {
        clear_pending(env);
        return false;
    }
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
    jmethodID method = g_erase_method;
    if (method == nullptr) {
        method = env->GetStaticMethodID(cls, "erase", "(Ljava/lang/String;Ljava/lang/String;)Z");
        if (method == nullptr) {
            clear_pending(env);
            if (cls != g_keyring_class) {
                env->DeleteLocalRef(cls);
            }
            return false;
        }
    }
    jstring j_service = utf8(env, service);
    jstring j_account = utf8(env, account);
    const jboolean ok = env->CallStaticBooleanMethod(cls, method, j_service, j_account);
    env->DeleteLocalRef(j_service);
    env->DeleteLocalRef(j_account);
    if (cls != g_keyring_class) {
        env->DeleteLocalRef(cls);
    }
    if (env->ExceptionCheck()) {
        clear_pending(env);
        return false;
    }
    return ok == JNI_TRUE;
}

}  // namespace i2pchat::storage::keyring::backend

#endif  // __ANDROID__
