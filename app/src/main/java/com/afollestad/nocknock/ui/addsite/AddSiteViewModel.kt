/**
 * Designed and developed by Aidan Follestad (@afollestad)
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
 */
package com.afollestad.nocknock.ui.addsite

import androidx.annotation.CheckResult
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.afollestad.nocknock.R
import com.afollestad.nocknock.data.AppDatabase
import com.afollestad.nocknock.data.model.Site
import com.afollestad.nocknock.data.model.SiteSettings
import com.afollestad.nocknock.data.model.ValidationMode
import com.afollestad.nocknock.data.model.ValidationMode.JAVASCRIPT
import com.afollestad.nocknock.data.model.ValidationMode.STATUS_CODE
import com.afollestad.nocknock.data.model.ValidationMode.TERM_SEARCH
import com.afollestad.nocknock.data.putSite
import com.afollestad.nocknock.di.viewmodels.ScopedViewModel
import com.afollestad.nocknock.engine.validation.ValidationManager
import com.afollestad.nocknock.di.qualifiers.IoDispatcher
import com.afollestad.nocknock.viewcomponents.ext.isNullOrLessThan
import com.afollestad.nocknock.viewcomponents.ext.map
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import javax.inject.Inject

/** @author Aidan Follestad (@afollestad) */
class AddSiteViewModel @Inject constructor(
  private val database: AppDatabase,
  private val validationManager: ValidationManager,
  @field:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ScopedViewModel(), LifecycleObserver {

  // Public properties
  val name = MutableLiveData<String>()
  val url = MutableLiveData<String>()
  val timeout = MutableLiveData<Int>()
  val validationMode = MutableLiveData<ValidationMode>()
  val validationSearchTerm = MutableLiveData<String>()
  val validationScript = MutableLiveData<String>()
  val checkIntervalValue = MutableLiveData<Int>()
  val checkIntervalUnit = MutableLiveData<Long>()

  // Private properties
  private val isLoading = MutableLiveData<Boolean>()
  private val nameError = MutableLiveData<Int?>()
  private val urlError = MutableLiveData<Int?>()
  private val timeoutError = MutableLiveData<Int?>()
  private val validationSearchTermError = MutableLiveData<Int?>()
  private val validationScriptError = MutableLiveData<Int?>()
  private val checkIntervalValueError = MutableLiveData<Int?>()

  // Expose private properties or calculated properties
  @CheckResult fun onIsLoading(): LiveData<Boolean> = isLoading

  @CheckResult fun onNameError(): LiveData<Int?> = nameError

  @CheckResult fun onUrlError(): LiveData<Int?> = urlError

  @CheckResult fun onUrlWarningVisibility(): LiveData<Boolean> {
    return url.map {
      val parsed = HttpUrl.parse(it)
      return@map it.isNotEmpty() &&
          parsed != null &&
          parsed.scheme() != "http" &&
          parsed.scheme() != "https"
    }
  }

  @CheckResult fun onTimeoutError(): LiveData<Int?> = timeoutError

  @CheckResult fun onValidationModeDescription(): LiveData<Int> {
    return validationMode.map {
      when (it) {
        STATUS_CODE -> R.string.validation_mode_status_desc
        TERM_SEARCH -> R.string.validation_mode_term_desc
        JAVASCRIPT -> R.string.validation_mode_javascript_desc
        else -> throw IllegalStateException("Unknown validation mode: $it")
      }
    }
  }

  @CheckResult fun onValidationSearchTermError(): LiveData<Int?> = validationSearchTermError

  @CheckResult fun onValidationSearchTermVisibility() =
    validationMode.map { it == TERM_SEARCH }

  @CheckResult fun onValidationScriptError(): LiveData<Int?> = validationScriptError

  @CheckResult fun onValidationScriptVisibility() =
    validationMode.map { it == JAVASCRIPT }

  @CheckResult fun onCheckIntervalError(): LiveData<Int?> = checkIntervalValueError

  // Actions
  fun commit(done: () -> Unit) {
    scope.launch {
      val newModel = generateDbModel() ?: return@launch
      isLoading.value = true

      val storedModel = withContext(ioDispatcher) {
        database.putSite(newModel)
      }
      validationManager.scheduleCheck(
          site = storedModel,
          rightNow = true,
          cancelPrevious = true
      )

      isLoading.value = false
      done()
    }
  }

  // Utilities
  private fun getCheckIntervalMs(): Long {
    val value = checkIntervalValue.value ?: return 0
    val unit = checkIntervalUnit.value ?: return 0
    return value * unit
  }

  private fun getValidationArgs(): String? {
    return when (validationMode.value) {
      TERM_SEARCH -> validationSearchTerm.value
      JAVASCRIPT -> validationScript.value
      else -> null
    }
  }

  private fun generateDbModel(): Site? {
    var errorCount = 0

    // Validation name
    if (name.value.isNullOrEmpty()) {
      nameError.value = R.string.please_enter_name
      errorCount++
    } else {
      nameError.value = null
    }

    // Validate URL
    when {
      url.value.isNullOrEmpty() -> {
        urlError.value = R.string.please_enter_url
        errorCount++
      }
      HttpUrl.parse(url.value!!) == null -> {
        urlError.value = R.string.please_enter_valid_url
        errorCount++
      }
      else -> {
        urlError.value = null
      }
    }

    // Validate timeout
    if (timeout.value.isNullOrLessThan(1)) {
      timeoutError.value = R.string.please_enter_networkTimeout
      errorCount++
    } else {
      timeoutError.value = null
    }

    // Validate check interval
    if (checkIntervalValue.value.isNullOrLessThan(1)) {
      checkIntervalValueError.value = R.string.please_enter_check_interval
      errorCount++
    } else {
      checkIntervalValueError.value = null
    }

    // Validate arguments
    if (validationMode == TERM_SEARCH &&
        validationSearchTerm.value.isNullOrEmpty()
    ) {
      errorCount++
      validationSearchTermError.value = R.string.please_enter_search_term
      validationScriptError.value = null
    } else if (validationMode == JAVASCRIPT &&
        validationScript.value.isNullOrEmpty()
    ) {
      errorCount++
      validationSearchTermError.value = null
      validationScriptError.value = R.string.please_enter_javaScript
    } else {
      validationSearchTermError.value = null
      validationScriptError.value = null
    }

    if (errorCount > 0) {
      return null
    }

    val newSettings = SiteSettings(
        validationIntervalMs = getCheckIntervalMs(),
        validationMode = validationMode.value!!,
        validationArgs = getValidationArgs(),
        networkTimeout = timeout.value!!,
        disabled = false
    )
    return Site(
        id = 0,
        name = name.value!!,
        url = url.value!!,
        settings = newSettings,
        lastResult = null
    )
  }
}