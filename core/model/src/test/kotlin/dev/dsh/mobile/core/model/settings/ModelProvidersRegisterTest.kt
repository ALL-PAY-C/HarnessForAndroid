package dev.dsh.mobile.core.model.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 插件网关端口每次启动都变，模型源注册必须能改写。
 *
 * 「同一 id 已存在就丢弃」会把上一轮端口定格在表里（真机实测 46729 vs 本次 42117），
 * 模型列表点下去必失败；这里钉住「后注册顶掉前注册」这条语义。
 */
class ModelProvidersRegisterTest {

    private fun provider(baseUrl: String) = object : ModelProvider {
        override val id = "plugin.pl.gateway.local.local-gateway"
        override val label = "Local Gateway"
        override val defaultBaseUrl = baseUrl
        override val defaultModel = "m"
        override val hint = "h"
    }

    @Test fun reRegisterReplacesStaleBaseUrl() {
        ModelProviders.register(provider("http://127.0.0.1:46729/v1"))
        ModelProviders.register(provider("http://127.0.0.1:42117/v1"))

        val hit = ModelProviders.all().filter { it.id == "plugin.pl.gateway.local.local-gateway" }
        assertEquals("同一 id 只留一条", 1, hit.size)
        assertEquals("http://127.0.0.1:42117/v1", hit.single().defaultBaseUrl)
    }
}
