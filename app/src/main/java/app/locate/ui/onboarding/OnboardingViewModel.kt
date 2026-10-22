package app.locate.ui.onboarding

import android.os.Build
import androidx.lifecycle.ViewModel
import app.locate.core.permissions.PermissionChecker
import app.locate.core.permissions.PermissionSpec
import app.locate.core.permissions.PermissionStep
import app.locate.core.permissions.PermissionStepId
import app.locate.core.prefs.AppPrefs
import app.locate.service.ServiceStarter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

sealed interface OnboardingPage {
    data object Intro : OnboardingPage
    data class Permission(val step: PermissionStep) : OnboardingPage
    data object Battery : OnboardingPage
    data object Limits : OnboardingPage
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    val permissions: PermissionChecker,
    private val prefs: AppPrefs,
    private val serviceStarter: ServiceStarter,
) : ViewModel() {

    val sdk = Build.VERSION.SDK_INT

    /** First run shows everything; a re-run from a home-screen banner shows only what's missing. */
    private val pages: List<OnboardingPage> = buildList {
        val firstRun = !prefs.onboardingDone
        if (firstRun) add(OnboardingPage.Intro)
        PermissionSpec.steps(sdk).forEach { if (firstRun || !permissions.isGranted(it)) add(OnboardingPage.Permission(it)) }
        if (firstRun || !permissions.isBatteryOptimizationIgnored()) add(OnboardingPage.Battery)
        if (firstRun) add(OnboardingPage.Limits)
    }

    private val index = MutableStateFlow(0)
    private val _page = MutableStateFlow(pages.firstOrNull())
    /** Null once onboarding is finished. */
    val page: StateFlow<OnboardingPage?> = _page.asStateFlow()

    val progress: String get() = "${index.value + 1} of ${pages.size}"

    fun next() {
        var i = index.value + 1
        // Background location can only be requested once foreground location is granted.
        while (i < pages.size && pages[i].let { it is OnboardingPage.Permission && it.step.id == PermissionStepId.BACKGROUND_LOCATION && !permissions.hasFineLocation() }) i++
        index.value = i
        _page.value = pages.getOrNull(i)
        if (_page.value == null) finish()
    }

    private fun finish() {
        prefs.onboardingDone = true
        serviceStarter.ensureRunning()
    }
}
