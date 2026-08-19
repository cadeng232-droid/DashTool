package com.example.dashtool

/**
 * Temporary local-development server configuration.
 *
 * Replace PC_IPV4 with the same IPv4 address that worked in
 * your phone's browser at:
 *
 * http://PC_IPV4:8000/health
 *
 * When DashTool moves to a cloud server, replace ORDERS_URL
 * with the cloud HTTPS endpoint and remove cleartext traffic
 * from AndroidManifest.xml.
 */
object OrderUploadConfig {

    const val PC_IPV4 =
        "192.168.1.203"

    const val SERVER_PORT =
        8000
    fun baseUrl(): String {
        return "http://$PC_IPV4:$SERVER_PORT"
    }

    fun ordersUrl(): String {
        return "${baseUrl()}/orders"
    }

    fun engineConfigUrl(): String {
        return "${baseUrl()}/engine-config"
    }
    val ORDERS_URL: String
        get() =
            "http://$PC_IPV4:$SERVER_PORT/orders"

    const val CONNECT_TIMEOUT_MS =
        5_000

    const val READ_TIMEOUT_MS =
        10_000
}
