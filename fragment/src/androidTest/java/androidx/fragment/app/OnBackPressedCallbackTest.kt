/*
 * Copyright 2018 The Android Open Source Project
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

package androidx.fragment.app

import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.test.FragmentTestActivity
import androidx.fragment.test.R
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class OnBackPressedCallbackTest {

    @Suppress("DEPRECATION")
    @Test
    fun testBackPressWithFrameworkFragment() {
        with(ActivityScenario.launch(FragmentTestActivity::class.java)) {
            val fragmentManager = withActivity { fragmentManager }
            val fragment = android.app.Fragment()

            fragmentManager.beginTransaction()
                .add(R.id.content, fragment)
                .addToBackStack(null)
                .commit()
            onActivity {
                fragmentManager.executePendingTransactions()
            }
            assertThat(fragmentManager.findFragmentById(R.id.content))
                .isSameAs(fragment)

            withActivity { onBackPressed() }

            assertThat(fragmentManager.findFragmentById(R.id.content))
                .isNull()
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun testBackPressWithFragmentOverFrameworkFragment() {
        with(ActivityScenario.launch(FragmentTestActivity::class.java)) {
            val fragmentManager = withActivity { fragmentManager }
            val fragment = android.app.Fragment()

            fragmentManager.beginTransaction()
                .add(R.id.content, fragment)
                .addToBackStack(null)
                .commit()
            onActivity {
                fragmentManager.executePendingTransactions()
            }
            assertThat(fragmentManager.findFragmentById(R.id.content))
                .isSameAs(fragment)

            val supportFragmentManager = withActivity { supportFragmentManager }
            val supportFragment = StrictFragment()

            supportFragmentManager.beginTransaction()
                .add(R.id.content, supportFragment)
                .addToBackStack(null)
                .commit()
            onActivity {
                supportFragmentManager.executePendingTransactions()
            }
            assertThat(supportFragmentManager.findFragmentById(R.id.content))
                .isSameAs(supportFragment)

            withActivity { onBackPressed() }

            assertThat(supportFragmentManager.findFragmentById(R.id.content))
                .isNull()
            assertThat(fragmentManager.findFragmentById(R.id.content))
                .isSameAs(fragment)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun testBackPressWithCallbackOverFrameworkFragment() {
        with(ActivityScenario.launch(FragmentTestActivity::class.java)) {
            val fragmentManager = withActivity { fragmentManager }
            val fragment = android.app.Fragment()

            fragmentManager.beginTransaction()
                .add(R.id.content, fragment)
                .addToBackStack(null)
                .commit()
            onActivity {
                fragmentManager.executePendingTransactions()
            }
            assertThat(fragmentManager.findFragmentById(R.id.content))
                .isSameAs(fragment)

            val callback = CountingOnBackPressedCallback()
            withActivity {
                onBackPressedDispatcher.addCallback(callback)

                onBackPressed()
            }

            assertThat(callback.count)
                .isEqualTo(1)
            assertThat(fragmentManager.findFragmentById(R.id.content))
                .isSameAs(fragment)
        }
    }

    @Test
    fun testBackPressWithCallbackOverFragment() {
        with(ActivityScenario.launch(FragmentTestActivity::class.java)) {
            val fragmentManager = withActivity { supportFragmentManager }
            val fragment = StrictFragment()
            fragmentManager.beginTransaction()
                .replace(R.id.content, fragment)
                .addToBackStack("back_stack")
                .commit()
            onActivity {
                fragmentManager.executePendingTransactions()
            }
            assertThat(fragmentManager.findFragmentById(R.id.content))
                .isSameAs(fragment)

            val callback = CountingOnBackPressedCallback()
            withActivity {
                onBackPressedDispatcher.addCallback(callback)

                onBackPressed()
            }

            assertWithMessage("OnBackPressedCallbacks should be called before FragmentManager")
                .that(callback.count)
                .isEqualTo(1)
            assertThat(fragmentManager.findFragmentById(R.id.content))
                .isSameAs(fragment)
        }
    }
}

class CountingOnBackPressedCallback(enabled: Boolean = true) : OnBackPressedCallback(enabled) {
    var count = 0

    override fun handleOnBackPressed() {
        count++
    }
}
