# QuickJS Android pointer tags

启动闪退的系统记录为 `REASON_CRASH_NATIVE` / SIGABRT，abort message 为
`Pointer tag ... was truncated`。崩溃线程 `OperitQuickJsRu` 在安装兼容脚本时进入
`libquickjsjni.so`，随后在 `malloc_usable_size` 中终止。

上游 QuickJS 的 64 位 NaN boxing 假设指针只有 47 位；`JS_VALUE_GET_PTR`
掩掉高位，破坏 Android 堆分配器的 top-byte tag。不能通过清理用户数据库修复。

构建固定 QuickJS 到 `e621135dc542e000e31038bedc52944aa0adaf1a`，在每个 ABI
独立的源码副本中让 Android 64 位使用上游已有的完整指针 JSValue 表示。
所有 C 源码和 JNI 桥接使用同一份头文件，JNI 编译期断言其大小为 16 字节。
Android 32 位表示不变，也没有关闭 Android 的堆指针标签检查。

更换依赖版本时需要复核该补丁。若上游条件不匹配，CMake 显式失败，避免静默产出
仍然截断指针的库。不同表示生成的 QuickJS 字节码不能混用；当前 JNI 接口仅执行源码，
该修改不迁移用户数据。

验证：以带标签的 `0xb4000079c9f1a700` 构造 `JS_TAG_OBJECT` 并读回指针。
原头文件得到 `0x79c9f1a700`，修正后 C 和 C++ 均保留完整指针与对象类型。
这是主机上的值表示回归检查；Android 原生库构建由发布 CI 验证，目标手机启动仍需实测。

参考：https://source.android.com/docs/security/test/tagged-pointers
