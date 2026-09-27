package com.example.capstone.data.auth

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class MsalConfigTest {

    @Test
    fun `redirect uri url-encodes the signature hash`() {
        assertThat(MsalConfig.redirectUri("com.example.capstone", "C3ASDt+nHPY0SKMEXBWNCK5zUic="))
            .isEqualTo("msauth://com.example.capstone/C3ASDt%2BnHPY0SKMEXBWNCK5zUic%3D")
        assertThat(MsalConfig.redirectUri("com.example.capstone", "ab/cd"))
            .isEqualTo("msauth://com.example.capstone/ab%2Fcd")
    }

    @Test
    fun `scope is the web end's access_as_user`() {
        assertThat(MsalConfig.scope("11111111-2222-3333-4444-555555555555"))
            .isEqualTo("api://11111111-2222-3333-4444-555555555555/access_as_user")
    }

    @Test
    fun `config is single account on the common authority`() {
        val json = JSONObject(MsalConfig.json("cid", "msauth://pkg/h%3D"))

        assertThat(json.getString("client_id")).isEqualTo("cid")
        assertThat(json.getString("redirect_uri")).isEqualTo("msauth://pkg/h%3D")
        assertThat(json.getString("account_mode")).isEqualTo("SINGLE")
        val authority = json.getJSONArray("authorities").getJSONObject(0)
        assertThat(authority.getString("type")).isEqualTo("AAD")
        val audience = authority.getJSONObject("audience")
        assertThat(audience.getString("type")).isEqualTo("AzureADandPersonalMicrosoftAccount")
        assertThat(audience.getString("tenant_id")).isEqualTo("common")
    }

    @Test
    fun `problems name each missing local property for the flavor`() {
        assertThat(MsalConfig.problems("local", "cid", "hash", "http://localhost:8000/api/")).isEmpty()
        assertThat(MsalConfig.problems("local", "", "hash", "http://localhost:8000/api/").single())
            .contains("webend.local.clientId")
        val problems = MsalConfig.problems("deployed", "", "", "")
        assertThat(problems).hasSize(3)
        assertThat(problems[0]).contains("webend.deployed.clientId")
        assertThat(problems[1]).contains("webend.signatureHash")
        assertThat(problems[2]).contains("webend.deployedUrl")
    }
}
