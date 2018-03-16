/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.kotlin.dsl.plugins.precompiled

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginConvention

import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.creating
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.the
import org.gradle.kotlin.dsl.withConvention
import org.gradle.kotlin.dsl.withType

import org.gradle.kotlin.dsl.precompile.PrecompiledInitScript
import org.gradle.kotlin.dsl.precompile.PrecompiledProjectScript
import org.gradle.kotlin.dsl.precompile.PrecompiledScriptDependenciesResolver
import org.gradle.kotlin.dsl.precompile.PrecompiledSettingsScript

import org.gradle.kotlin.dsl.support.ImplicitImports
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension
import org.gradle.plugin.devel.plugins.JavaGradlePluginPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.NameUtils

import java.io.File


/*
 * Enables the compilation of `*.gradle.kts` scripts in regular Kotlin source-sets.
 */
open class PrecompiledScriptPlugins : Plugin<Project> {

    override fun apply(project: Project) = project.run {

        plugins.withType<JavaGradlePluginPlugin> {

            val scriptPluginFiles = fileTree("src/main/kotlin") {
                it.include("**/*.gradle.kts")
            }

            val scriptPlugins by lazy {
                scriptPluginFiles.map(::ScriptPlugin)
            }

            tasks {

                val inferGradlePluginDeclarations by creating {
                    doLast {
                        project.configure<GradlePluginDevelopmentExtension> {
                            for (scriptPlugin in scriptPlugins) {
                                plugins.create(scriptPlugin.id) {
                                    it.id = scriptPlugin.id
                                    it.implementationClass = scriptPlugin.implementationClass
                                }
                            }
                        }
                    }
                }

                getByName("pluginDescriptors") {
                    it.dependsOn(inferGradlePluginDeclarations)
                }

                val generatedSourcesDir = layout.buildDirectory.dir("generated-sources/kotlin-dsl-plugins/kotlin")
                project.the<JavaPluginConvention>().sourceSets["main"].withConvention(KotlinSourceSet::class) {
                    kotlin.srcDir(generatedSourcesDir)
                }

                val generateScriptPluginWrappers by creating {
                    inputs.files(scriptPluginFiles)
                    outputs.dir(generatedSourcesDir)
                    doLast {
                        for (scriptPlugin in scriptPlugins) {
                            scriptPlugin.writeScriptPluginWrapperTo(generatedSourcesDir.get().asFile)
                        }
                    }
                }

                getByName("compileKotlin") {
                    it.dependsOn(generateScriptPluginWrappers)
                }
            }
        }

        afterEvaluate {

            tasks {

                "compileKotlin"(KotlinCompile::class) {
                    kotlinOptions {
                        freeCompilerArgs += listOf(
                            "-script-templates", scriptTemplates,
                            // Propagate implicit imports and other settings
                            "-Xscript-resolver-environment=${resolverEnvironment()}"
                        )
                    }
                }
            }
        }
    }

    private
    val scriptTemplates by lazy {
        listOf(
            // treat *.settings.gradle.kts files as Settings scripts
            PrecompiledSettingsScript::class.qualifiedName!!,
            // treat *.init.gradle.kts files as Gradle scripts
            PrecompiledInitScript::class.qualifiedName!!,
            // treat *.gradle.kts files as Project scripts
            PrecompiledProjectScript::class.qualifiedName!!
        ).joinToString(separator = ",")
    }

    private
    fun Project.resolverEnvironment() =
        (PrecompiledScriptDependenciesResolver.EnvironmentProperties.kotlinDslImplicitImports
            + "=\"" + implicitImports().joinToString(separator = ":") + "\"")

    private
    fun Project.implicitImports(): List<String> =
        serviceOf<ImplicitImports>().list
}


internal
data class ScriptPlugin(val sourceFile: File) {

    val id by lazy {
        packagePrefixed(fileNameWithoutScriptExtension)
    }

    val fileNameWithoutScriptExtension by lazy {
        sourceFile.name.removeSuffix(".gradle.kts")
    }

    val compiledScriptTypeName by lazy {
        val scriptName = NameUtils.getScriptNameForFile(sourceFile.name).asString()
        packagePrefixed(scriptName)
    }

    val implementationClass by lazy {
        fileNameWithoutScriptExtension.kebabCaseToPascalCase()
    }

    val packageName: String? by lazy {
        packageNameOf(sourceFile)
    }

    private
    fun packagePrefixed(id: String) =
        packageName?.let { "$it.$id" } ?: id
}


internal
fun packageNameOf(file: File): String? =
    packageNameOf(file.readText())


internal
fun packageNameOf(code: String): String? =
    KotlinLexer().run {
        start(code)
        while (tokenType in KtTokens.WHITE_SPACE_OR_COMMENT_BIT_SET) {
            advance()
        }
        if (tokenType == KtTokens.PACKAGE_KEYWORD) {
            advance()
            while (tokenType in KtTokens.WHITE_SPACE_OR_COMMENT_BIT_SET) {
                advance()
            }
            val packageName = StringBuilder()
            while (tokenType == KtTokens.IDENTIFIER || tokenType == KtTokens.DOT) {
                packageName.append(tokenText)
                advance()
            }
            packageName.toString()
        } else null
    }


internal
fun ScriptPlugin.writeScriptPluginWrapperTo(outputDir: File) =
    File(outputDir, "$implementationClass.kt").writeText(
        """
            import org.gradle.api.Plugin
            import org.gradle.api.Project

            class $implementationClass : Plugin<Project> {
                override fun apply(target: Project) {
                    Class
                        .forName("$compiledScriptTypeName")
                        .getDeclaredConstructor(Project::class.java)
                        .newInstance(target)
                }
            }
        """.replaceIndent())


internal
fun CharSequence.kebabCaseToPascalCase() =
    kebabCaseToCamelCase().capitalize()


internal
fun CharSequence.kebabCaseToCamelCase() =
    replace("-[a-z]".toRegex()) { it.value.drop(1).toUpperCase() }
