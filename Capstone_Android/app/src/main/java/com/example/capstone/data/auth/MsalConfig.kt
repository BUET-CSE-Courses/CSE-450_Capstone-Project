package com.example.capstone.data.auth

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * MSAL for Android configuration, built from `local.properties` values
 * instead of a checked-in `res/raw` file, so no client id or machine hash is
 * committed. There is one config per web end: flavor `local` uses
 * webend.local.clientId (the user's own registration), flavor `deployed`
 * uses webend.deployed.clientId (the teammate's). webend.signatureHash is
 * shared, so the redirect URI is identical in both.
 *
 * Single-account mode, authority "common" (any Entra tenant plus personal
 * Microsoft accounts), which matches the web end's `AZURE_TENANT_ID=common`
 * (`backend/config.py`) and its multi-tenant token validation
 * (`backend/security.py`).
 */
object MsalConfig {

    /** The one scope the web end exposes: `api://<AZURE_CLIENT_ID>/access_as_user` (`security.py`). */
    fun scope(clientId: String): String = "api://$clientId/access_as_user"

    /**
     * `msauth://<package>/<url-encoded signature hash>`. The Entra portal shows
     * exactly this after you enter the package name and hash. The manifest
     * intent filter takes the hash raw, not encoded.
     */
    fun redirectUri(packageName: String, signatureHash: String): String =
        "msauth://$packageName/${URLEncoder.encode(signatureHash, "UTF-8")}"

    fun json(clientId: String, redirectUri: String): String = JSONObject()
        .put("client_id", clientId)
        .put("authorization_user_agent", "DEFAULT")
        .put("redirect_uri", redirectUri)
        .put("account_mode", "SINGLE")
        .put("broker_redirect_uri_registered", true)
        .put(
            "authorities",
            JSONArray().put(
                JSONObject()
                    .put("type", "AAD")
                    .put("default", true)
                    .put(
                        "audience",
                        JSONObject()
                            .put("type", "AzureADandPersonalMicrosoftAccount")
                            .put("tenant_id", "common")
                    )
            )
        )
        .toString(2)

    /**
     * What is missing for sign-in to work, as lines a person can act on. Empty
     * when the build is configured.
     */
    fun problems(flavor: String, clientId: String, signatureHash: String, baseUrl: String): List<String> = buildList {
        if (clientId.isBlank()) {
            add(
                "Add webend.$flavor.clientId (the $flavor web end's AZURE_CLIENT_ID) " +
                    "to local.properties and rebuild."
            )
        }
        if (signatureHash.isBlank()) {
            add("Add webend.signatureHash (your signing certificate's hash) to local.properties and rebuild.")
        }
        if (baseUrl.isBlank()) {
            add("Add webend.deployedUrl to local.properties and rebuild, or install the local flavor.")
        }
    }
}
