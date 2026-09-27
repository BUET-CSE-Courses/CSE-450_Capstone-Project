package com.example.capstone.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.capstone.BuildConfig
import com.google.common.truth.Truth.assertThat
import com.microsoft.identity.client.PublicClientApplicationConfigurationFactory
import com.microsoft.identity.client.configuration.AccountMode
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Feeds the generated config to MSAL's own loader and validator, the same
 * path `createSingleAccountPublicClientApplication(context, file)` takes, and
 * checks the merged manifest routes the redirect back to MSAL.
 */
@RunWith(RobolectricTestRunner::class)
class MsalConfigLoadTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val hash = "C3ASDt+nHPY0SKMEXBWNCK5zUic="

    @Test
    fun `MSAL accepts the generated config`() {
        val redirect = MsalConfig.redirectUri(context.packageName, hash)
        val file = File(context.noBackupFilesDir, "msal_config_test.json")
        file.writeText(MsalConfig.json("11111111-2222-3333-4444-555555555555", redirect))

        val config = PublicClientApplicationConfigurationFactory.initializeConfiguration(context, file)

        assertThat(config.clientId).isEqualTo("11111111-2222-3333-4444-555555555555")
        assertThat(config.redirectUri).isEqualTo(redirect)
        assertThat(config.accountMode).isEqualTo(AccountMode.SINGLE)
        assertThat(config.defaultAuthority.authorityURL.toString())
            .isEqualTo("https://login.microsoftonline.com/common")
    }

    @Test
    fun `the redirect uri resolves to MSAL's BrowserTabActivity`() {
        val built = BuildConfig.MSAL_SIGNATURE_HASH
        assumeTrue("webend.signatureHash not set in local.properties", built.isNotBlank())

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MsalConfig.redirectUri(context.packageName, built)))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)
        val match = context.packageManager.queryIntentActivities(intent, 0)

        assertThat(match.map { it.activityInfo.name })
            .containsExactly("com.microsoft.identity.client.BrowserTabActivity")
    }
}
