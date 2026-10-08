package com.wordbuddy.frpc

import org.json.JSONObject

/**
 * frp 客户端配置模型（一个配置 = 一条隧道 = 一个 profile）。
 * 结构化存储（JSON），生成 frpc.toml 时再转文本，避免手写 TOML 出错。
 */
data class FrpcConfig(
    var name: String = "default",
    var serverAddr: String = "",
    var serverPort: Int = 7000,
    var authToken: String = "",
    var user: String = "",
    var tlsEnable: Boolean = true,
    var dnsServer: String = "114.114.114.114",
    var proxyName: String = "helper",
    var proxyType: String = "tcp",
    var localIP: String = "127.0.0.1",
    var localPort: Int = 5555,
    var remotePort: Int = 60000,
    var useEncryption: Boolean = true,
    var useCompression: Boolean = true
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("name", name)
        o.put("serverAddr", serverAddr)
        o.put("serverPort", serverPort)
        o.put("authToken", authToken)
        o.put("user", user)
        o.put("tlsEnable", tlsEnable)
        o.put("dnsServer", dnsServer)
        o.put("proxyName", proxyName)
        o.put("proxyType", proxyType)
        o.put("localIP", localIP)
        o.put("localPort", localPort)
        o.put("remotePort", remotePort)
        o.put("useEncryption", useEncryption)
        o.put("useCompression", useCompression)
        return o.toString(2)
    }

    companion object {
        /** 兜底默认值（当 assets 也读不到时的最后防线） */
        fun fallback() = FrpcConfig()

        fun fromJson(s: String): FrpcConfig {
            val o = JSONObject(s)
            val d = fallback()
            return FrpcConfig(
                name = o.optString("name", d.name),
                serverAddr = o.optString("serverAddr", d.serverAddr),
                serverPort = o.optInt("serverPort", d.serverPort),
                authToken = o.optString("authToken", d.authToken),
                user = o.optString("user", d.user),
                tlsEnable = o.optBoolean("tlsEnable", d.tlsEnable),
                dnsServer = o.optString("dnsServer", d.dnsServer),
                proxyName = o.optString("proxyName", d.proxyName),
                proxyType = o.optString("proxyType", d.proxyType),
                localIP = o.optString("localIP", d.localIP),
                localPort = o.optInt("localPort", d.localPort),
                remotePort = o.optInt("remotePort", d.remotePort),
                useEncryption = o.optBoolean("useEncryption", d.useEncryption),
                useCompression = o.optBoolean("useCompression", d.useCompression)
            )
        }
    }
}
