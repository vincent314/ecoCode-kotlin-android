/*
 * SonarSource Kotlin
 * Copyright (C) 2018-2024 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.kotlin.plugin

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.sonar.api.batch.fs.InputFile
import org.sonar.api.batch.rule.CheckFactory
import org.sonar.api.batch.sensor.SensorContext
import org.sonar.api.batch.sensor.internal.DefaultSensorDescriptor
import org.sonar.api.batch.sensor.internal.SensorContextTester
import org.sonar.api.batch.sensor.issue.internal.DefaultNoSonarFilter
import org.sonar.api.config.internal.ConfigurationBridge
import org.sonar.api.config.internal.MapSettings
import org.sonar.check.Rule
import org.sonarsource.kotlin.api.checks.AbstractCheck
import org.sonarsource.kotlin.api.common.COMPILER_THREAD_COUNT_PROPERTY
import org.sonarsource.kotlin.api.common.FAIL_FAST_PROPERTY_NAME
import org.sonarsource.kotlin.api.common.KOTLIN_LANGUAGE_VERSION
import org.sonarsource.kotlin.api.common.SONAR_JAVA_BINARIES
import org.sonarsource.kotlin.api.frontend.Environment
import org.sonarsource.kotlin.api.frontend.KotlinFileContext
import org.sonarsource.kotlin.api.frontend.analyzeAndGetBindingContext
import org.sonarsource.kotlin.api.sensors.environment
import org.sonarsource.kotlin.checks.environment.optimized_api.BluetoothBleCheck
import org.sonarsource.kotlin.checks.environment.optimized_api.FusedLocationCheck
import org.sonarsource.kotlin.plugin.caching.contentHashKey
import org.sonarsource.kotlin.testapi.AbstractSensorTest
import java.io.IOException
import java.security.MessageDigest
import kotlin.time.ExperimentalTime

private val LOG = LoggerFactory.getLogger(KotlinSensor::class.java)

@ExperimentalTime
internal class KotlinSensorTest : AbstractSensorTest() {

    @AfterEach
    fun cleanupMocks() {
        unmockkAll()
    }

    @Test
    fun testDescribe() {
        val checkFactory = checkFactory("S1764")

        val descriptor = DefaultSensorDescriptor()
        sensor(checkFactory).describe(descriptor)
        assertThat(descriptor.name()).isEqualTo("creedengo Kotlin Sensor")
        assertThat(descriptor.languages()).isEqualTo(listOf("kotlin"))
    }

    @Test
    fun test_one_rule() {
        val inputFile = createInputFile(
            "file1.kt", """
        package checks       
        import android.bluetooth.le.BluetoothLeScanner // Noncompliant {{Using android.bluetooth.le.* is a good practice.}}    
        class OnlyBleCheck{
            var titi : BluetoothLeScanner? = null
        }
     """.trimIndent()
        )
        context.fileSystem().add(inputFile)
        val checkFactory = checkFactory("GCI518")
        sensor(checkFactory).execute(context)
        val issues = context.allIssues()
        assertThat(issues).hasSize(1)
        val issue = issues.iterator().next()
        assertThat(issue.ruleKey().rule()).isEqualTo("GCI518")
        val location = issue.primaryLocation()
        assertThat(location.inputComponent()).isEqualTo(inputFile)
        assertThat(location.message())
            .isEqualTo("Using android.bluetooth.le.* is a good practice.")
        assertTextRange(location.textRange()).hasRange(2, 7, 2, 46)
    }

    @Test
    fun test_issue_suppression() {
        val inputFile = createInputFile(
            "file1.kt", """
     @SuppressWarnings("kotlin:S1764")
     fun main() {
     print (1 == 1);}
     @SuppressWarnings(value=["kotlin:S1764"])
     fun main2() {
     print (1 == 1);}
     """.trimIndent()
        )
        context.fileSystem().add(inputFile)
        val checkFactory = checkFactory("S1764")
        sensor(checkFactory).execute(context)
        val issues = context.allIssues()
        assertThat(issues).isEmpty()
    }

    @Test
    fun `Ensure compiler crashes during BindingContext generation don't crash engine`() {
        context.setCanSkipUnchangedFiles(false)
        executeAnalysisWithInvalidBindingContext()
        assertThat(logTester.logs(Level.ERROR)).containsExactly("Could not generate binding context. Proceeding without semantics.")
    }

    @Test
    fun `BindingContext generation does not crash when there are no files to analyze`() {
        context.setCanSkipUnchangedFiles(true)
        executeAnalysisWithInvalidBindingContext()
        assertThat(logTester.logs(Level.ERROR)).isEmpty()
    }

    private fun executeAnalysisWithInvalidBindingContext() {
        val inputFile = createInputFile(
            "file1.kt", """
        abstract class MyClass {
            abstract fun <P1> foo(): (P1) -> Unknown<String>
        
            private fun callTryConvertConstant() {
                println(foo<String>())
            }
        }
        """.trimIndent(),
            InputFile.Status.SAME
        )
        context.fileSystem().add(inputFile)
        populateCacheWithExpectedEntries(listOf(inputFile), context)
        mockkStatic("org.sonarsource.kotlin.api.sensors.AbstractKotlinSensorExecuteContextKt")
        every { environment(any(), any()) } returns Environment(listOf("file1.kt"), LanguageVersion.LATEST_STABLE)
        mockkStatic("org.sonarsource.kotlin.api.frontend.KotlinCoreEnvironmentToolsKt")
        every { analyzeAndGetBindingContext(any(), any()) } throws IOException("Boom!")

        val checkFactory = checkFactory("S1764")
        assertDoesNotThrow { sensor(checkFactory).execute(context) }

        unmockkAll()
    }

    @Test
    fun test_fail_parsing() {
        val inputFile = createInputFile("file1.kt", "enum class A { <!REDECLARATION!>FOO<!>,<!REDECLARATION!>FOO<!> }")
        context.fileSystem().add(inputFile)
        val checkFactory = checkFactory("S1764")
        sensor(checkFactory).execute(context)
        val analysisErrors = context.allAnalysisErrors()
        assertThat(analysisErrors).hasSize(1)
        val analysisError = analysisErrors.iterator().next()
        assertThat(analysisError.inputFile()).isEqualTo(inputFile)
        assertThat(analysisError.message()).isEqualTo("Unable to parse file: file1.kt")
        val textPointer = analysisError.location()
        assertThat(textPointer).isNotNull
        assertThat(textPointer!!.line()).isEqualTo(1)
        assertThat(textPointer.lineOffset()).isEqualTo(14)
        assertThat(logTester.logs())
            .contains(String.format("Unable to parse file: %s. Parse error at position 1:14", inputFile.uri()))
    }

    @Test
    fun test_fail_reading() {
        val inputFile = spyk(createInputFile("file1.kt", "class A { fun f() = TODO() }"))
        context.fileSystem().add(inputFile)
        every { inputFile.contents() } throws IOException("Can't read")
        every { inputFile.toString() } returns "file1.kt"

        val checkFactory = checkFactory("S1764")
        sensor(checkFactory).execute(context)
        val analysisErrors = context.allAnalysisErrors()
        assertThat(analysisErrors).hasSize(1)
        val analysisError = analysisErrors.iterator().next()
        assertThat(analysisError.inputFile()).isEqualTo(inputFile)
        assertThat(analysisError.message()).isEqualTo("Unable to parse file: file1.kt")
        val textPointer = analysisError.location()
        assertThat(textPointer).isNull()

        assertThat(logTester.logs(Level.ERROR)).contains("Cannot read 'file1.kt': Can't read")
    }

    @Test
    fun test_with_classpath() {
        val settings = MapSettings()
        settings.setProperty(SONAR_JAVA_BINARIES, "classes/")
        context.setSettings(settings)
        val inputFile = createInputFile("file1.kt", "class A { fun f() = TODO() }")
        context.fileSystem().add(inputFile)
        val checkFactory = checkFactory("S1764")
        sensor(checkFactory).execute(context)
        val analysisErrors = context.allAnalysisErrors()
        assertThat(analysisErrors).isEmpty()
    }

    @Test
    fun test_with_blank_classpath() {
        val settings = MapSettings()
        settings.setProperty(SONAR_JAVA_BINARIES, " ")
        context.setSettings(settings)
        val inputFile = createInputFile("file1.kt", "class A { fun f() = TODO() }")
        context.fileSystem().add(inputFile)
        val checkFactory = checkFactory("S1764")
        sensor(checkFactory).execute(context)
        val analysisErrors = context.allAnalysisErrors()
        assertThat(analysisErrors).isEmpty()
    }

    private fun failFastSensorWithEnvironmentSetup(failFast: Boolean?): KotlinSensor {
        mockkStatic("org.sonarsource.kotlin.plugin.KotlinCheckListKt")
        every { KOTLIN_CHECKS } returns listOf(BluetoothBleCheck::class.java, FusedLocationCheck::class.java)

        context.apply {
            setSettings(MapSettings().apply {
                failFast?.let { setProperty(FAIL_FAST_PROPERTY_NAME, it) }
            })

            fileSystem().add(createInputFile("file1.kt", "class A { fun f() = TODO() }"))
        }
        return sensor(checkFactory("ETRule1"))
    }

    @Test
    fun `not setting the kotlin version analyzer property results in Environment with the default Kotlin version`() {
        logTester.setLevel(Level.DEBUG)

        val sensorContext = mockk<SensorContext> {
            every { config() } returns ConfigurationBridge(MapSettings())
        }

        val environment = environment(sensorContext, LOG)

        val expectedKotlinVersion = LanguageVersion.LATEST_STABLE

        assertThat(environment.configuration.languageVersionSettings.languageVersion).isSameAs(expectedKotlinVersion)
        assertThat(logTester.logs(Level.WARN)).isEmpty()
        assertThat(logTester.logs(Level.DEBUG))
            .contains("Using Kotlin ${expectedKotlinVersion.versionString} to parse source code")
    }

    @Test
    fun `setting the kotlin version analyzer property to a valid value is reflected in the Environment`() {
        logTester.setLevel(Level.DEBUG)

        val sensorContext = mockk<SensorContext> {
            every { config() } returns ConfigurationBridge(MapSettings().apply {
                setProperty(KOTLIN_LANGUAGE_VERSION, "1.3")
            })
        }

        val environment = environment(sensorContext, LOG)

        val expectedKotlinVersion = LanguageVersion.KOTLIN_1_3

        assertThat(environment.configuration.languageVersionSettings.languageVersion).isSameAs(expectedKotlinVersion)
        assertThat(logTester.logs(Level.WARN)).isEmpty()
        assertThat(logTester.logs(Level.DEBUG))
            .contains("Using Kotlin ${expectedKotlinVersion.versionString} to parse source code")
    }

    @Test
    fun `setting the kotlin version analyzer property to an invalid value results in log message and the default version to be used`() {
        logTester.setLevel(Level.DEBUG)

        val sensorContext = mockk<SensorContext> {
            every { config() } returns ConfigurationBridge(MapSettings().apply {
                setProperty(KOTLIN_LANGUAGE_VERSION, "foo")
            })
        }

        val environment = environment(sensorContext, LOG)

        val expectedKotlinVersion = LanguageVersion.LATEST_STABLE

        assertThat(environment.configuration.languageVersionSettings.languageVersion).isSameAs(expectedKotlinVersion)
        assertThat(logTester.logs(Level.WARN))
            .containsExactly("Failed to find Kotlin version 'foo'. Defaulting to ${expectedKotlinVersion.versionString}")
        assertThat(logTester.logs(Level.DEBUG))
            .contains("Using Kotlin ${expectedKotlinVersion.versionString} to parse source code")
    }

    @Test
    fun `setting the kotlin version analyzer property to whitespaces only results in the default version to be used`() {
        logTester.setLevel(Level.DEBUG)

        val sensorContext = mockk<SensorContext> {
            every { config() } returns ConfigurationBridge(MapSettings().apply {
                setProperty(KOTLIN_LANGUAGE_VERSION, "  ")
            })
        }

        val environment = environment(sensorContext, LOG)

        val expectedKotlinVersion = LanguageVersion.LATEST_STABLE

        assertThat(environment.configuration.languageVersionSettings.languageVersion).isSameAs(expectedKotlinVersion)
        assertThat(logTester.logs(Level.WARN)).isEmpty()
        assertThat(logTester.logs(Level.DEBUG))
            .contains("Using Kotlin ${expectedKotlinVersion.versionString} to parse source code")
    }

    @Test
    fun `not setting the amount of threads to use explicitly will not set anything in the environment`() {
        logTester.setLevel(Level.DEBUG)

        val sensorContext = mockk<SensorContext> {
            every { config() } returns ConfigurationBridge(MapSettings())
        }

        val environment = environment(sensorContext, LOG)

        assertThat(environment.configuration.get(CommonConfigurationKeys.PARALLEL_BACKEND_THREADS)).isNull()
        assertThat(logTester.logs(Level.WARN)).isEmpty()
        assertThat(logTester.logs(Level.DEBUG))
            .contains("Using the default amount of threads")
    }

    @Test
    fun `setting the amount of threads to use is reflected in the environment`() {
        logTester.setLevel(Level.DEBUG)

        val sensorContext = mockk<SensorContext> {
            every { config() } returns ConfigurationBridge(MapSettings().apply {
                setProperty(COMPILER_THREAD_COUNT_PROPERTY, "42")
            })
        }

        val environment = environment(sensorContext, LOG)

        assertThat(environment.configuration.get(CommonConfigurationKeys.PARALLEL_BACKEND_THREADS)).isEqualTo(42)
        assertThat(logTester.logs(Level.WARN)).isEmpty()
        assertThat(logTester.logs(Level.DEBUG))
            .contains("Using 42 threads")
    }

    @Test
    fun `setting the amount of threads to use to an invalid integer value produces warning`() {
        logTester.setLevel(Level.DEBUG)

        val sensorContext = mockk<SensorContext> {
            every { config() } returns ConfigurationBridge(MapSettings().apply {
                setProperty(COMPILER_THREAD_COUNT_PROPERTY, "0")
            })
        }

        val environment = environment(sensorContext, LOG)

        assertThat(environment.configuration.get(CommonConfigurationKeys.PARALLEL_BACKEND_THREADS)).isNull()
        assertThat(logTester.logs(Level.WARN))
            .containsExactly("Invalid amount of threads specified for ${COMPILER_THREAD_COUNT_PROPERTY}: '0'.")
        assertThat(logTester.logs(Level.DEBUG))
            .contains("Using the default amount of threads")
    }

    @Test
    fun `setting the amount of threads to use to an invalid non-integer value produces warning`() {
        logTester.setLevel(Level.DEBUG)

        val sensorContext = mockk<SensorContext> {
            every { config() } returns ConfigurationBridge(MapSettings().apply {
                setProperty(COMPILER_THREAD_COUNT_PROPERTY, "foo")
            })
        }

        val environment = environment(sensorContext, LOG)

        assertThat(environment.configuration.get(CommonConfigurationKeys.PARALLEL_BACKEND_THREADS)).isNull()
        assertThat(logTester.logs(Level.WARN))
            .containsExactly("${COMPILER_THREAD_COUNT_PROPERTY} needs to be set to an integer value. Could not interpret 'foo' as integer.")
        assertThat(logTester.logs(Level.DEBUG))
            .contains("Using the default amount of threads")
    }

    @Test
    fun `hasFileChanged falls back on the InputFile status when cache is disabled`() {
        logTester.setLevel(Level.DEBUG)

        val files = incrementalAnalysisFileSet()

        context.isCacheEnabled = false
        context.setCanSkipUnchangedFiles(true)

        val unchangedFile = spyk(files[InputFile.Status.SAME]!!)
        context.fileSystem().add(unchangedFile)
        context.fileSystem().add(files[InputFile.Status.CHANGED]!!)
        val checkFactory = checkFactory("S1764")
        sensor(checkFactory).execute(context)

        // The analysis is not incremental because a disabled cache prevents the reuse of CPD tokens
        assertThat(logTester.logs(Level.DEBUG)).contains("Content hash cache is disabled")
        assertThat(logTester.logs(Level.INFO)).contains("Only analyzing 2 changed Kotlin files out of 2.")
        verify { unchangedFile.status() }
    }

    @Test
    fun `test same file passed twice to content hash cache`() {
        val files = incrementalAnalysisFileSet()
        val key = contentHashKey(files[InputFile.Status.CHANGED]!!)
        val messageDigest = MessageDigest.getInstance("MD5")
        val readCache = files[InputFile.Status.CHANGED]!!.contents().byteInputStream().use {
            DummyReadCache(mapOf(key to messageDigest.digest(it.readAllBytes())))
        }
        val writeCache = DummyWriteCache(readCache = readCache)
        context.setNextCache(writeCache)
        context.setPreviousCache(readCache)
        val changedFile = files[InputFile.Status.CHANGED]!!
        context.fileSystem().add(changedFile)
        val sameKeyFile = spyk(files[InputFile.Status.SAME]!!)
        every { sameKeyFile.key() } returns changedFile.key()
        every { sameKeyFile.contents() } returns changedFile.contents()
        context.fileSystem().add(sameKeyFile)
        val checkFactory = checkFactory("S1764")
        sensor(checkFactory).execute(context)
        assertThat(logTester.logs(Level.WARN)).contains("Cannot copy key $key from cache as it has already been written")
    }

    private fun incrementalAnalysisFileSet(): Map<InputFile.Status, InputFile> {
        context.setCanSkipUnchangedFiles(true)
        context.isCacheEnabled = false
        val changedFile = createInputFile(
            "changed.kt",
            """
                fun main(args: Array<String>) {
                    print (1 == 1);
                }
                """.trimIndent(),
            status = InputFile.Status.CHANGED
        )
        val addedFile = createInputFile(
            "added.kt",
            """
                fun isAlsoIdentical(input: Int): Boolean = input == input
                """.trimIndent(),
            status = InputFile.Status.ADDED
        )
        val unchangedFile = createInputFile(
            "unchanged.kt",
            """
                fun isIdentical(input: Int): Boolean = input == input
                """.trimIndent(),
            status = InputFile.Status.SAME
        )
        val files = mapOf(
            InputFile.Status.ADDED to addedFile,
            InputFile.Status.CHANGED to changedFile,
            InputFile.Status.SAME to unchangedFile,
        )
        populateCacheWithExpectedEntries(files.values, context)
        return files
    }

    private fun populateCacheWithExpectedEntries(files: Iterable<InputFile>, context: SensorContextTester) {
        val cacheContentBeforeAnalysis = mutableMapOf<String, ByteArray>()
        val messageDigest = MessageDigest.getInstance("MD5")
        files
            .filter { it.status() != InputFile.Status.ADDED }
            .forEach {
                // Add content hashes
                val contentHashKey = contentHashKey(it)
                if (it.status() == InputFile.Status.SAME) {
                    val digest = messageDigest.digest(it.contents().byteInputStream().readAllBytes())
                    cacheContentBeforeAnalysis[contentHashKey] = digest
                } else if (it.status() == InputFile.Status.CHANGED) {
                    cacheContentBeforeAnalysis[contentHashKey] = ByteArray(0)
                }
                // Add CPD tokens
                cacheContentBeforeAnalysis["kotlin:cpdTokens:${it.key()}"] = ByteArray(0)
            }

        context.isCacheEnabled = true
        if (context.previousCache() != null) {
            val readCache = context.previousCache() as DummyReadCache
            readCache.cache.entries.forEach { cacheContentBeforeAnalysis[it.key] = it.value }
        }

        val previousCache = DummyReadCache(cacheContentBeforeAnalysis)
        val nextCache = DummyWriteCache(readCache = previousCache)
        context.setPreviousCache(previousCache)
        context.setNextCache(nextCache)
    }


    private fun sensor(checkFactory: CheckFactory): KotlinSensor {
        return KotlinSensor(checkFactory, fileLinesContextFactory, DefaultNoSonarFilter())
    }
}

@Rule(key = "ETRule1")
internal class ExceptionThrowingCheck : AbstractCheck() {
    override fun visitNamedFunction(function: KtNamedFunction, data: KotlinFileContext?) {
        throw TestException("This is a test message")
    }
}

@Rule(key = "AndroidOnlyRule")
internal class AndroidOnlyCheck : AbstractCheck() {
    override fun visitNamedFunction(function: KtNamedFunction, kfc: KotlinFileContext) {
        if (kfc.isInAndroid()) {
            kfc.reportIssue(function.nameIdentifier!!, "Boom!")
        }
    }
}

internal class TestException(msg: String) : Exception(msg)
