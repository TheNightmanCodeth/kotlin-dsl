package org.gradle.kotlin.dsl.plugins.precompiled

import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat

import org.junit.Test


class PrecompiledScriptPluginIdsTest {

    @Test
    fun `can extract qualified package name`() {

        assertThat(
            packageNameOf("package org.acme"),
            equalTo("org.acme"))
    }
}
