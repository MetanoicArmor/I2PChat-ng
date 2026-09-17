#pragma once

#include <optional>
#include <string>
#include <string_view>

/// The platform half of the keyring. One translation unit per OS implements
/// these; `keyring.cpp` owns the enable switch and forwards to them.
namespace i2pchat::storage::keyring::backend {

[[nodiscard]] bool available();

[[nodiscard]] std::optional<std::string> get(std::string_view service,
                                             std::string_view account);

[[nodiscard]] bool set(std::string_view service, std::string_view account,
                       std::string_view secret);

[[nodiscard]] bool erase(std::string_view service, std::string_view account);

#if defined(__ANDROID__)
/// Called from JNI_OnLoad so wrap-key lookups can reach Android Keystore.
void set_java_vm(void* vm);
/// Cache NativeKeyring jclass/method IDs (FindClass fails from native worker threads).
void init_jni(void* jni_env);
#endif

}  // namespace i2pchat::storage::keyring::backend
