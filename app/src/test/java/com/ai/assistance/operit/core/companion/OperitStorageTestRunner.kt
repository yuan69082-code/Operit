package com.ai.assistance.operit.core.companion

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/** NIO discovers Operit's SFTP provider while Robolectric loads native SQLite resources.
 * Keep SSHD's ServiceLoader interfaces and implementations in the same parent loader. */
class OperitStorageTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotAcquirePackage("org.apache.sshd.")
            .build()
}
