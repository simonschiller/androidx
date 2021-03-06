/*
 * Copyright (C) 2020 The Android Open Source Project
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

package androidx.room.compiler.processing.javac

import androidx.annotation.VisibleForTesting
import androidx.room.compiler.processing.XElement
import androidx.room.compiler.processing.XRoundEnv
import androidx.room.compiler.processing.XTypeElement
import com.google.auto.common.MoreElements
import javax.annotation.processing.RoundEnvironment

@Suppress("UnstableApiUsage")
@VisibleForTesting
internal class JavacRoundEnv(
    private val env: JavacProcessingEnv,
    val delegate: RoundEnvironment
) : XRoundEnv {
    override val rootElements: Set<XElement> by lazy {
        delegate.rootElements.map {
            check(MoreElements.isType(it))
            env.wrapTypeElement(MoreElements.asType(it))
        }.toSet()
    }

    // TODO this is only for tests but we may need to support more types of elements
    override fun getTypeElementsAnnotatedWith(annotationQualifiedName: String): Set<XTypeElement> {
        val element = env.elementUtils.getTypeElement(annotationQualifiedName)
            ?: error("Cannot find TypeElement: $annotationQualifiedName")
        val result = delegate.getElementsAnnotatedWith(element)
        return result.filter {
            MoreElements.isType(it)
        }.map {
            env.wrapTypeElement(MoreElements.asType(it))
        }.toSet()
    }
}
