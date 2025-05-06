package org.jetbrains.sma

import ai.grazie.api.gateway.client.SuspendableAPIGatewayClient
import ai.grazie.client.common.SuspendableHTTPClient
import ai.grazie.client.common.retry.ClientRetryStrategy
import ai.grazie.client.common.retry.withRetry
import ai.grazie.client.ktor.GrazieKtorHTTPClient
import ai.grazie.model.auth.GrazieAgent
import ai.grazie.model.auth.v5.AuthData
import ai.grazie.model.cloud.exceptions.HTTPStatusException
import ai.grazie.utils.mpp.time.Duration

val client = getClient(GrazieKtorHTTPClient.Client.WithExtendedTimeout)

private fun getClient(grazieClient: GrazieKtorHTTPClient): SuspendableAPIGatewayClient {
    val tokenVal = Config.grazieToken
    if (tokenVal.isNullOrEmpty()) {
        throw IllegalStateException("Grazie token is not set")
    }

    return SuspendableAPIGatewayClient(
        Config.grazieUrl,
        SuspendableHTTPClient.WithV5(
            grazieClient,
            AuthData(
                token = tokenVal,
                grazieAgent = GrazieAgent(
                    name = "Hackathon25",
                    version = "1.0.0"
                )
            )
        ),
        Config.grazieAuthType
    )
}

suspend fun <Client, T> Client.withDefaultRetry(block: suspend Client.() -> T) =
    withRetry(1, ClientRetryStrategy.exponential(Duration.seconds(1)), retryOn = {
        it is HTTPStatusException && it.isServerError()
    }, block = block)