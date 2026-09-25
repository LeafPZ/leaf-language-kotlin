/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file has been modified from the original Fabric Language Kotlin
 * project (https://github.com/FabricMC/fabric-language-kotlin) as part
 * of the Leaf Language Kotlin project.
 *
 * Modifications Copyright 2026 LeafPZ, licensed under the Apache License, Version 2.0.
 */

package dev.aoqia.leaf.language.kotlin.test

import dev.aoqia.leaf.api.ModInitializer
import dev.aoqia.leaf.language.kotlin.KotlinAdapter
import dev.aoqia.leaf.loader.api.LeafLoader
import kotlin.test.Test

class KotlinAdapterTest {
    @Test
    fun classEntrypoint() {
        testEntrypoint("net.fabricmc.language.kotlin.test.entrypoints.ClassEntrypoint")
    }

    @Test
    fun objectClassEntrypoint() {
        testEntrypoint("net.fabricmc.language.kotlin.test.entrypoints.ObjectClassEntrypoint")
    }

    @Test
    fun objectFunctionEntrypoint() {
        testEntrypoint("net.fabricmc.language.kotlin.test.entrypoints.ObjectFunctionEntrypoint::init")
    }

    @Test
    fun objectFieldEntrypoint() {
        testEntrypoint("net.fabricmc.language.kotlin.test.entrypoints.ObjectFieldEntrypoint::initializer")
    }

    @Test
    fun companionClassEntrypoint() {
        testEntrypoint("net.fabricmc.language.kotlin.test.entrypoints.CompanionClassEntrypoint\$Companion")
    }

    @Test
    fun companionFunctionEntrypoint() {
        testEntrypoint("net.fabricmc.language.kotlin.test.entrypoints.CompanionFunctionEntrypoint\$Companion::init")
    }

    @Test
    fun companionFieldEntrypoint() {
        testEntrypoint("net.fabricmc.language.kotlin.test.entrypoints.CompanionFieldEntrypoint\$Companion::initializer")
    }

    @Test
    fun topLevelEntrypoint() {
        testEntrypoint("net.fabricmc.language.kotlin.test.entrypoints.TopLevelEntrypointKt::init")
    }

    private fun testEntrypoint(value: String) {
        LeafLoader.getInstance().objectShare.remove("leaf-language-kotlin:test")

        val modContainer = LeafLoader.getInstance().getModContainer("leaf-language-kotlin").get()
        val entrypoint = KotlinAdapter()
            .create(modContainer, value, ModInitializer::class.java)
        entrypoint.onInitialize()

        assert(LeafLoader.getInstance().objectShare.get("leaf-language-kotlin:test") == "true")
    }
}