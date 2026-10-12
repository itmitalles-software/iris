package io.wutz.apollo.data.ws

import io.wutz.apollo.data.remote.OkHttpProvider
import io.wutz.apollo.data.ws.contract.HermesRpcCaller
import io.wutz.apollo.data.ws.contract.PluginsManageParams
import io.wutz.apollo.data.ws.contract.RpcMethods
import io.wutz.apollo.data.ws.contract.TypedRpcCaller
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Manages plugin settings and inspection through the gateway's canonical `plugins.manage` RPC.
 */
class PluginManageRepository(
    private val caller: TypedRpcCaller = HermesRpcCaller,
) {
    suspend fun listPlugins(profile: String? = null): List<AgentPluginRow> {
        val element =
            caller.call(
                RpcMethods.PLUGINS_MANAGE,
                PluginsManageParams(action = "list", profile = profile.orNullIfBlank()),
            )
        val pluginsElement = (element as? JsonObject)?.get("plugins") ?: return emptyList()
        return OkHttpProvider.json.decodeFromJsonElement<List<AgentPluginRow>>(pluginsElement)
    }

    suspend fun saveSettings(
        key: String,
        values: Map<String, JsonElement>,
        profile: String? = null,
    ): PluginsManageSettingsResult {
        val params =
            PluginsManageParams(
                action = "settings",
                key = key,
                values = JsonObject(values),
                profile = profile.orNullIfBlank(),
            )
        val result = caller.call(RpcMethods.PLUGINS_MANAGE, params)
        return OkHttpProvider.json.decodeFromJsonElement<PluginsManageSettingsResult>(result)
    }

    private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}
