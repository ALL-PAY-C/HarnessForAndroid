package dev.dsh.mobile.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** gatewayUiUrl：只看得到「显式 http(s) + 显式端口 + pl. 命名」三条同时成立的地址。 */
class GatewayWebUiTest {

    private val base = "http://127.0.0.1:7788/v1"

    @Test
    fun `正常派生 端口与 bare 路径`() {
        assertEquals(
            "http://127.0.0.1:7788/gateway.local/ui",
            gatewayUiUrl(base, "pl.gateway.local"),
        )
        assertEquals(
            "https://10.0.2.2:9000/gateway.stream/ui",
            gatewayUiUrl("https://10.0.2.2:9000/v1", "pl.gateway.stream"),
        )
    }

    @Test
    fun `baseUrl 为空时没有地址`() {
        assertNull(gatewayUiUrl(null, "pl.gateway.local"))
        assertNull("空白也算没配", gatewayUiUrl("   ", "pl.gateway.local"))
    }

    @Test
    fun `非 http scheme 一律不认`() {
        assertNull(gatewayUiUrl("ws://127.0.0.1:7788/v1", "pl.gateway.local"))
        assertNull(gatewayUiUrl("file:///data/local/tmp/v1", "pl.gateway.local"))
        assertNull("没有 scheme 也不认", gatewayUiUrl("127.0.0.1:7788/v1", "pl.gateway.local"))
    }

    @Test
    fun `非 pl 前缀的插件没有网关页面`() {
        assertNull(gatewayUiUrl(base, "np.other"))
        assertNull("只剩 pl. 也是空壳", gatewayUiUrl(base, "pl."))
    }

    @Test
    fun `端口 0 不算可打开地址`() {
        assertNull(gatewayUiUrl("http://127.0.0.1:0/v1", "pl.gateway.local"))
    }

    @Test
    fun `缺省端口不算可打开地址`() {
        assertNull(gatewayUiUrl("http://127.0.0.1/v1", "pl.gateway.local"))
    }
}
