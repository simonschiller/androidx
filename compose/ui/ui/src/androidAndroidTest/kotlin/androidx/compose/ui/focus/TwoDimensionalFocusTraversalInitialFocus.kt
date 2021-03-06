/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.compose.ui.focus

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection.Down
import androidx.compose.ui.focus.FocusDirection.Left
import androidx.compose.ui.focus.FocusDirection.Right
import androidx.compose.ui.focus.FocusDirection.Up
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.test.filters.MediumTest
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private const val invalid = "Not applicable to a 2D focus search."

@MediumTest
@RunWith(Parameterized::class)
class TwoDimensionalFocusTraversalInitialFocus(private val focusDirection: FocusDirection) {
    @get:Rule
    val rule = createComposeRule()

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun initParameters() = listOf(Left, Right, Up, Down)
    }

    @Test
    fun initialFocus() {
        // Arrange.
        lateinit var view: View
        lateinit var focusManager: FocusManager
        val isFocused = MutableList(4) { mutableStateOf(false) }
        rule.setContent {
            view = LocalView.current
            focusManager = LocalFocusManager.current
            Column {
                Row {
                    FocusableBox(isFocused[0])
                    FocusableBox(isFocused[1])
                }
                Row {
                    FocusableBox(isFocused[2])
                    FocusableBox(isFocused[3])
                }
            }
        }
        rule.runOnIdle { view.requestFocus() }

        // Act.
        focusManager.moveFocus(focusDirection)

        // Assert.
        rule.runOnIdle {
            when (focusDirection) {
                Up -> assertThat(isFocused.values).containsExactly(false, false, true, false)
                Down -> assertThat(isFocused.values).containsExactly(true, false, false, false)
                Left -> assertThat(isFocused.values).containsExactly(false, false, false, true)
                Right -> assertThat(isFocused.values).containsExactly(true, false, false, false)
                else -> error(invalid)
            }
        }
    }

    @Test
    fun initialFocus_whenThereIsOnlyOneFocusable() {
        // Arrange.
        var isFocused = mutableStateOf(false)
        lateinit var view: View
        lateinit var focusManager: FocusManager
        rule.setContent {
            view = LocalView.current
            focusManager = LocalFocusManager.current
            FocusableBox(isFocused)
        }
        rule.runOnIdle { view.requestFocus() }

        // Act.
        focusManager.moveFocus(focusDirection)

        // Assert.
        rule.runOnIdle { assertThat(isFocused.value).isTrue() }
    }

    @Test
    fun doesNotCrash_whenThereIsNoFocusable() {
        // Arrange.
        lateinit var view: View
        lateinit var focusManager: FocusManager
        rule.setContent {
            view = LocalView.current
            focusManager = LocalFocusManager.current
            BasicText("Hello")
        }
        rule.runOnIdle { view.requestFocus() }

        // Act.
        focusManager.moveFocus(focusDirection)
    }

    @Test
    fun initialFocus_notTriggeredIfActiveElementIsNotRoot() {
        // Arrange.
        lateinit var focusManager: FocusManager
        var isColumnFocused = false
        val isFocused = MutableList(4) { mutableStateOf(false) }
        val initialFocusRequester = FocusRequester()
        rule.setContent {
            focusManager = LocalFocusManager.current
            Column(
                Modifier
                    .focusRequester(initialFocusRequester)
                    .onFocusChanged { isColumnFocused = it.isFocused }
                    .focusModifier()
            ) {
                Row {
                    FocusableBox(isFocused[0])
                    FocusableBox(isFocused[1])
                }
                Row {
                    FocusableBox(isFocused[2])
                    FocusableBox(isFocused[3])
                }
            }
        }
        rule.runOnIdle { initialFocusRequester.requestFocus() }

        // Act.
        focusManager.moveFocus(focusDirection)

        // Assert.
        rule.runOnIdle {
            assertThat(isColumnFocused).isTrue()
            assertThat(isFocused.values).containsExactly(false, false, false, false)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun movesFocusAmongSiblingsDeepInTheFocusHierarchy() {
        // Arrange.
        lateinit var focusManager: FocusManager
        val isFocused = MutableList(2) { mutableStateOf(false) }
        val (item1, item2) = FocusRequester.createRefs()
        val siblings = @Composable {
            FocusableBox(isFocused = isFocused[0], focusRequester = item1)
            FocusableBox(isFocused = isFocused[1], focusRequester = item2)
        }
        val initialFocusedItem = when (focusDirection) {
            Up, Left -> item2
            Down, Right -> item1
            else -> error(invalid)
        }
        rule.setContent {
            focusManager = LocalFocusManager.current
            FocusableBox {
                FocusableBox {
                    FocusableBox {
                        FocusableBox {
                            FocusableBox {
                                FocusableBox {
                                    when (focusDirection) {
                                        Up, Down -> Column { siblings() }
                                        Left, Right -> Row { siblings() }
                                        else -> error(invalid)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.runOnIdle { initialFocusedItem.requestFocus() }

        // Act.
        focusManager.moveFocus(focusDirection)

        // Assert.
        rule.runOnIdle {
            when (focusDirection) {
                Up, Left -> assertThat(isFocused.values).containsExactly(true, false)
                Down, Right -> assertThat(isFocused.values).containsExactly(false, true)
                else -> error(invalid)
            }
        }
    }
}

@Composable
private fun FocusableBox(
    isFocused: MutableState<Boolean>? = null,
    x: Int = 0,
    y: Int = 0,
    width: Int = 10,
    height: Int = 10,
    focusRequester: FocusRequester? = null,
    content: @Composable () -> Unit = {}
) {
    Layout(
        content = content,
        modifier = Modifier
            .offset { IntOffset(x, y) }
            .focusRequester(focusRequester ?: FocusRequester())
            .onFocusChanged { if (isFocused != null) isFocused.value = it.isFocused }
            .focusModifier(),
        measurePolicy = remember(width, height) {
            MeasurePolicy { measurables, _ ->
                val constraint = Constraints(width, width, height, height)
                layout(width, height) {
                    measurables.forEach {
                        val placeable = it.measure(constraint)
                        placeable.place(0, 0)
                    }
                }
            }
        }
    )
}

private val MutableList<MutableState<Boolean>>.values get() = this.map { it.value }
