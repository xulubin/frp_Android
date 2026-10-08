package com.wordbuddy.frpc

/** 由 [FrpcConfig] 生成 frpc 0.71 的 TOML 文本 */
object TomlBuilder {

    fun build(c: FrpcConfig): String = buildString {
        appendLine("serverAddr = \"${c.serverAddr}\"")
        appendLine("serverPort = ${c.serverPort}")
        appendLine()
        appendLine("transport.tcpMux = true")
        appendLine("transport.protocol = \"tcp\"")
        appendLine()
        appendLine("auth.method = \"token\"")
        appendLine("auth.token = \"${c.authToken}\"")
        if (c.user.isNotBlank()) appendLine("user = \"${c.user}\"")
        if (c.dnsServer.isNotBlank()) appendLine("dnsServer = \"${c.dnsServer}\"")
        appendLine()
        appendLine("transport.tls.enable = ${c.tlsEnable}")
        appendLine()
        appendLine("[[proxies]]")
        appendLine("name = \"${c.proxyName}\"")
        appendLine("type = \"${c.proxyType}\"")
        appendLine("localIP = \"${c.localIP}\"")
        appendLine("localPort = ${c.localPort}")
        appendLine("remotePort = ${c.remotePort}")
        appendLine("transport.useEncryption = ${c.useEncryption}")
        appendLine("transport.useCompression = ${c.useCompression}")
    }
}
