package com.sheen.rokid.phone.model
import com.sheen.rokid.phone.ui.profile.profile
import com.sheen.rokid.phone.ui.profile.TestBlobs
import com.sheen.rokid.phone.ui.profile.ProfileViewModel
import com.sheen.rokid.phone.storage.KeystoreVault
import com.sheen.rokid.phone.storage.AndroidProfileRepository

import com.sheen.rokid.model.ProfileTester
import com.sheen.rokid.phone.storage.*
import com.sheen.rokid.plugin.*
import org.junit.Assert.*
import org.junit.Test

class ProfileFormTest {
    private fun form() = ProfileForm.from(profile("form"))

    @Test fun aNewProfileDefaultsToACloudSizedWindowNotAnEightKOne() {
        // 2026-10-22 acceptance: the assistant's model is a cloud service (large context on the provider side); the
        // on-device models are Laya/embedding/ASR only. Defaulting a new profile to 8 K/1 K made the prompt budget so
        // tight that a one-line safety hint summarised skill bodies - a configuration defect, not a model limit.
        val fresh = ProfileForm.from(null)
        assertEquals("128", fresh.maxInputK)
        assertEquals("8", fresh.maxOutputK)
        val draft = fresh.copy(
            name = "cloud", service = "deepseek", baseUrl = "https://api.deepseek.com",
            model = "deepseek-chat", protocolId = "DEEPSEEK_RESPONSES",
        ).draft("new", null, hasNewSecret = true)
        assertEquals(131_072, draft.usableWindowTokens)
        assertEquals(8_192, draft.maxOutputTokens)
        assertEquals(256, draft.safetyTokens)
        assertEquals(122_624, draft.inputBudgetTokens)
    }

    @Test fun newProfileUsesDeclaredDecimalKCapacityAndReservesOutputAndSafety() {
        val draft = form().copy(maxInputK = "8.0009765625", maxOutputK = "1.5")
            .draft("new", null, true)
        assertEquals(8193, draft.usableWindowTokens)
        assertEquals(1536, draft.maxOutputTokens)
        assertEquals(256, draft.safetyTokens)
        assertEquals(6401, draft.inputBudgetTokens)
    }

    @Test fun editingOtherFieldsRoundTripsNonKAlignedSettingsAndKeepsStricterAllowance() {
        val old = profile("existing").copy(usableWindowTokens = 4097, maxOutputTokens = 1025, safetyTokens = 129)
        val displayed = ProfileForm.from(old)
        assertEquals("4.0009765625", displayed.maxInputK)
        assertEquals("1.0009765625", displayed.maxOutputK)
        val edited = displayed.copy(name = "Renamed", model = "changed-model", timeoutMillis = "9000")
            .draft(old.id, old, false)
        assertEquals(4097, edited.usableWindowTokens); assertEquals(1025, edited.maxOutputTokens)
        assertEquals(129, edited.safetyTokens); assertEquals(2048, edited.inputBudgetTokens)
        assertEquals(old.secret, edited.secret)
    }

    @Test fun equivalentFormattingDoesNotRelaxAnExistingRequestAllowance() {
        val old = profile("existing")
        val draft = form().copy(maxInputK = "04.000", maxOutputK = "1.0000").draft(old.id, old, false)
        assertEquals(2048, draft.inputBudgetTokens)
    }

    @Test fun intentionallyChangedCapacityRecalculatesAllowanceWithExistingSafety() {
        val old = profile("existing")
        val draft = form().copy(maxInputK = "8").draft(old.id, old, false)
        assertEquals(8192, draft.usableWindowTokens); assertEquals(7040, draft.inputBudgetTokens)
        assertEquals(128, draft.safetyTokens)
    }

    @Test fun intentionallyChangedOutputRecalculatesAllowanceWithExistingSafety() {
        val old = profile("existing")
        val draft = form().copy(maxOutputK = "2").draft(old.id, old, false)
        assertEquals(2048, draft.maxOutputTokens); assertEquals(1920, draft.inputBudgetTokens)
    }

    @Test fun everyMissingRequiredFieldIdentifiesItsSafeFieldWithoutEchoingValues() {
        val cases = listOf(
            form().copy(name = " ") to ProfileField.NAME,
            form().copy(service = " ") to ProfileField.SERVICE,
            form().copy(baseUrl = " ") to ProfileField.BASE_URL,
            form().copy(model = " ") to ProfileField.MODEL,
            form().copy(protocolId = " ") to ProfileField.PROTOCOL,
            form().copy(inputs = emptySet()) to ProfileField.INPUTS,
            form().copy(outputs = emptySet()) to ProfileField.OUTPUTS,
        )
        cases.forEach { (invalid, field) ->
            val error = assertThrows(ProfileFormException::class.java) { invalid.draft("new", null, true) }
            assertEquals(field, error.field)
        }
        val key = assertThrows(ProfileFormException::class.java) { form().draft("new", null, false) }
        assertEquals(ProfileField.API_KEY, key.field)
    }

    @Test fun invalidUrlsUseSafeSpecificErrorInsteadOfExposingEnteredUrl() {
        listOf("http://synthetic.example", "https://synthetic-user:synthetic-password@synthetic.example", ":bad url", "https://synthetic.example/#fragment")
            .forEach { url ->
                val error = assertThrows(ProfileFormException::class.java) { form().copy(baseUrl = url).draft("new", null, true) }
                assertEquals(ProfileField.BASE_URL, error.field); assertEquals("BASE_URL_INVALID", error.code)
                assertFalse(error.message.orEmpty().contains(url))
            }
    }

    @Test fun invalidFractionOverflowAndNonpositiveKAreRejectedAtTheEditedField() {
        listOf("0", "-1", "NaN", "1e4", "0.0001", "2097152", "999999999999999999999999999999999999999999999999999999999999999999999")
            .forEach { value ->
                val input = assertThrows(ProfileFormException::class.java) { form().copy(maxInputK = value).draft("new", null, true) }
                assertEquals(ProfileField.MAX_INPUT, input.field)
                val output = assertThrows(ProfileFormException::class.java) { form().copy(maxOutputK = value).draft("new", null, true) }
                assertEquals(ProfileField.MAX_OUTPUT, output.field)
            }
    }

    @Test fun largestIntCapacityAndOneTokenOutputDoNotOverflowDerivedAllowance() {
        val draft = form().copy(maxInputK = "2097151.9990234375", maxOutputK = "0.0009765625")
            .draft("existing", profile("existing"), false)
        assertEquals(Int.MAX_VALUE, draft.usableWindowTokens)
        assertEquals(1, draft.maxOutputTokens); assertEquals(2147483518, draft.inputBudgetTokens)
    }

    @Test fun capacityOutputConflictAndInvalidTimeoutAreDistinct() {
        val conflict = assertThrows(ProfileFormException::class.java) { form().copy(maxInputK = "1.25", maxOutputK = "1").draft("new", null, true) }
        assertEquals(ProfileField.MAX_INPUT, conflict.field); assertEquals("CAPACITY_OUTPUT_CONFLICT", conflict.code)
        listOf("0", "-1", "abc", "9223372036854775808").forEach { value ->
            val timeout = assertThrows(ProfileFormException::class.java) { form().copy(timeoutMillis = value).draft("new", null, true) }
            assertEquals(ProfileField.TIMEOUT, timeout.field)
        }
    }

    @Test fun failedSaveRetainsEnteredSecretForRetryAndWipesEveryScopedCopyAndSuccess() {
        val blobs = TestBlobs(); val vault = KeystoreVault(blobs); val repo = AndroidProfileRepository(blobs, vault)
        val vm = ProfileViewModel(repo, object : ProfileTester {
            override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle = throw AssertionError("No network")
        })
        val entered = "synthetic-retry-secret".toCharArray()
        val draft = profile("retry")
        var scoped: CharArray? = null
        val save: (ModelProfile, CharArray?) -> Boolean = { value, secret ->
            scoped = secret; vm.save(value, secret); vm.state.value.error == null
        }
        try {
            blobs.failId = "profiles-manifest"
            assertFalse(saveProfileDraft(draft, entered, save))
            assertEquals("SAVE_FAILED", vm.state.value.error); assertTrue(repo.list().isEmpty())
            assertEquals("synthetic-retry-secret", String(entered)); assertTrue(scoped!!.all { it == '\u0000' })
            blobs.failId = null
            assertTrue(saveProfileDraft(draft, entered, save)); assertNull(vm.state.value.error)
            assertTrue(entered.all { it == '\u0000' }); assertTrue(scoped!!.all { it == '\u0000' })
            vault.withSecret(repo.get("retry")!!.secret) { assertEquals("synthetic-retry-secret", String(it)) }
        } finally { entered.fill('\u0000'); vm.close() }
    }

    @Test fun throwingSaveCallbackCannotLeakScopedSecretOrDiscardRetryValue() {
        val entered = "synthetic-retry-secret".toCharArray(); var scoped: CharArray? = null
        try {
            assertFalse(saveProfileDraft(profile("new"), entered) { _, secret ->
                scoped = secret; throw IllegalStateException("synthetic private diagnostic")
            })
            assertEquals("synthetic-retry-secret", String(entered)); assertTrue(scoped!!.all { it == '\u0000' })
        } finally { entered.fill('\u0000') }
    }
}
