/*
 * Copyright (C) 2017 The Android Open Source Project
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

package androidx.room.solver.binderprovider

import androidx.room.processor.Context
import androidx.room.solver.ObservableQueryResultBinderProvider
import androidx.room.solver.RxType
import androidx.room.solver.query.result.QueryResultAdapter
import androidx.room.solver.query.result.QueryResultBinder
import androidx.room.solver.query.result.RxQueryResultBinder
import isAssignableFrom
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeMirror

class RxQueryResultBinderProvider private constructor(
    context: Context,
    private val rxType: RxType
) : ObservableQueryResultBinderProvider(context) {
    private val typeMirror: TypeMirror? by lazy {
        context.processingEnv.elementUtils
            .getTypeElement(rxType.className.toString())?.asType()
    }
    private val hasRxJavaArtifact by lazy {
        context.processingEnv.elementUtils
            .getTypeElement(rxType.version.rxRoomClassName.toString()) != null
    }

    override fun extractTypeArg(declared: DeclaredType): TypeMirror = declared.typeArguments.first()

    override fun create(
        typeArg: TypeMirror,
        resultAdapter: QueryResultAdapter?,
        tableNames: Set<String>
    ): QueryResultBinder {
        return RxQueryResultBinder(
            rxType = rxType,
            typeArg = typeArg,
            queryTableNames = tableNames,
            adapter = resultAdapter
        )
    }

    override fun matches(declared: DeclaredType): Boolean =
        declared.typeArguments.size == 1 && matchesRxType(declared)

    private fun matchesRxType(declared: DeclaredType): Boolean {
        if (typeMirror == null) {
            return false
        }
        val typeUtils = context.processingEnv.typeUtils
        val erasure = typeUtils.erasure(declared)
        val match = erasure.isAssignableFrom(typeUtils, typeMirror!!)
        if (match && !hasRxJavaArtifact) {
            context.logger.e(rxType.version.missingArtifactMessage)
        }
        return match
    }

    companion object {
        fun getAll(context: Context) = listOf(
            RxType.RX2_FLOWABLE,
            RxType.RX2_OBSERVABLE,
            RxType.RX3_FLOWABLE,
            RxType.RX3_OBSERVABLE
        ).map { RxQueryResultBinderProvider(context, it) }
    }
}